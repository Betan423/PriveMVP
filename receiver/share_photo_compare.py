"""Scenario 1 (SHARE_PHOTO): compare Raw, Masked and Task-Minimal views of the same task.

python3 -m receiver.share_photo_compare --dataset <source_raw_v5.json> --out <dir>

For every SHARE_PHOTO task in the dataset three versions of "what the planner would see" are built:

  Raw           the whole phone context the task could read (environment + utterance + parameters),
                which is what an agent gets when the app hands it the full Context.
  Masked        the same context after fixed-field masking and pseudonymisation only: names, phone and
                LINE refs, media URIs, EXIF locations, message texts and screen text are replaced, but
                nothing is dropped and no task whitelist is applied.
  Task-Minimal  the planner packet the policy engine actually emits (receiver/core_tasks.py): task-
                scoped tokens and five booleans, or nothing when the decision is ASK / LOCAL / STOP.

Outputs (all in --out): comparison.json (aggregate metrics with denominators), pairs.jsonl (per-task
sizes and checks), manifest.json (input / output SHA-256). The dataset is synthetic; every
"identifier" below is a SYN_* placeholder, so the counts measure structure, not real-world risk.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import random
import sys
from pathlib import Path
from typing import Any

from .assistant import forbidden_hits
from .core_tasks import decide_task
from .phone_sim import redact

VERSIONS = ("raw", "masked", "task_minimal")
# Fields the SHARE_PHOTO task needs (the blueprint's Required Data) and nothing else.
TASK_SECTIONS = {"contacts", "photos", "installed_apps"}
# Context sections that exist on the phone but have nothing to do with sharing one photo.
NON_TASK_SECTIONS = ("user_profile", "device", "saved_destinations", "permissions", "battery", "network", "system",
                     "screen", "messages", "notifications", "calendar", "location", "clipboard", "call", "app_usage")
# Direct identifiers, by key, as they appear in the synthetic environment.
IDENTIFIER_KEYS = {"display_name", "phone_ref", "line_ref", "media_ref", "exif_location_ref", "address_ref",
                   "text", "text_ref", "title_ref", "sender_ref", "caller_ref", "wifi_ssid_ref", "location_ref",
                   "user_id", "device_id"}
MASK = {"display_name": "[NAME]", "phone_ref": "[PHONE_NUMBER]", "line_ref": "[LINE_ID]", "media_ref": "[MEDIA_URI]",
        "exif_location_ref": "[GPS]", "address_ref": "[ADDRESS]", "text_ref": "[TEXT]", "title_ref": "[TEXT]",
        "sender_ref": "[SENDER]", "caller_ref": "[CALLER]", "wifi_ssid_ref": "[SSID]", "location_ref": "[LOCATION]",
        "user_id": "[USER]", "device_id": "[DEVICE]"}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _leaves(value: Any) -> int:
    if isinstance(value, dict):
        return sum(_leaves(v) for v in value.values())
    if isinstance(value, list):
        return sum(_leaves(v) for v in value)
    return 1


def _bytes(value: Any) -> int:
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def _identifier_values(value: Any, key: str | None = None) -> int:
    """Count leaves under identifier keys that still hold a non-placeholder value."""
    if isinstance(value, dict):
        return sum(_identifier_values(v, k) for k, v in value.items())
    if isinstance(value, list):
        return sum(_identifier_values(v, key) for v in value)
    if key == "text" and isinstance(value, str):
        # Message bodies count only while they still hold a direct identifier the Receiver would reject.
        return 1 if forbidden_hits(value) else 0
    if key in IDENTIFIER_KEYS and isinstance(value, str) and value and not value.startswith("["):
        return 1
    return 0


def _sections(value: dict[str, Any]) -> set[str]:
    found: set[str] = set()
    for group in ("initialization_data", "continuous_state_data"):
        found |= set(value.get(group, {}))
    return found


def raw_view(task: dict[str, Any], environment: dict[str, Any]) -> dict[str, Any]:
    return {"utterance": task["utterance"], "parameters": task["parameters"],
            "initialization_data": copy.deepcopy(environment["initialization_data"]),
            "continuous_state_data": copy.deepcopy(environment["continuous_state_data"])}


def masked_view(task: dict[str, Any], environment: dict[str, Any]) -> dict[str, Any]:
    def mask(value: Any, key: str | None = None) -> Any:
        if isinstance(value, dict):
            return {k: mask(v, k) for k, v in value.items()}
        if isinstance(value, list):
            return [mask(v, key) for v in value]
        if key == "text" and isinstance(value, str):
            return redact(value)
        if key in MASK and isinstance(value, str) and value:
            return MASK[key]
        return value
    return mask(raw_view(task, environment))


def task_minimal_view(task: dict[str, Any], environment: dict[str, Any], rng: random.Random) -> tuple[dict[str, Any] | None, Any]:
    record = decide_task(task, environment, rng)
    return record.packet, record


def necessary_conditions(version: str, view: dict[str, Any] | None, task: dict[str, Any], environment: dict[str, Any]) -> dict[str, bool]:
    """Fidelity: can the recipient, the photo and the share app still be resolved from this view?"""
    params = task["parameters"]
    if view is None:
        return {"recipient_resolvable": False, "photo_resolvable": False, "share_app_known": False, "location_flag_kept": False}
    if version == "task_minimal":
        facts = view["facts"]
        return {"recipient_resolvable": facts["recipient_ready"] and view["recipient_token"].startswith("CONTACT_"),
                "photo_resolvable": facts["photo_ready"] and view["photo_token"].startswith("PHOTO_"),
                "share_app_known": facts["share_app_ready"], "location_flag_kept": "location_removed" in facts}
    contacts = view["initialization_data"]["contacts"]
    photos = view["continuous_state_data"]["photos"]
    apps = view["initialization_data"]["installed_apps"]
    photo = next((p for p in photos if p["photo_id"] == params["photo_id"]), None)
    return {"recipient_resolvable": sum(1 for c in contacts if c["relationship"] == params["recipient_relation"]) == 1,
            "photo_resolvable": photo is not None,
            "share_app_known": any(a["name"] == params.get("app_name") for a in apps),
            "location_flag_kept": photo is not None and "exif_location_ref" in photo}


def run(dataset_path: Path, out: Path, seed: int) -> dict[str, Any]:
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    environments = {e["environment_id"]: e for e in data["phone_environments"]}
    tasks = [t for t in data["task_requests"] if t["scenario_id"] == "SHARE_PHOTO"]
    rng = random.Random(seed)
    out.mkdir(parents=True, exist_ok=True)

    totals = {v: {"leaves": 0, "bytes": 0, "identifier_values": 0, "non_task_sections": 0, "tasks_with_output": 0}
              for v in VERSIONS}
    fidelity = {v: {k: 0 for k in ("recipient_resolvable", "photo_resolvable", "share_app_known", "location_flag_kept")}
                for v in VERSIONS}
    linkable = {v: 0 for v in VERSIONS}
    decisions: dict[str, int] = {}
    stable_tokens: dict[str, set[str]] = {}
    with (out / "pairs.jsonl").open("w", encoding="utf-8") as pairs:
        for task in tasks:
            env = environments[task["environment_id"]]
            views: dict[str, dict[str, Any] | None] = {"raw": raw_view(task, env), "masked": masked_view(task, env)}
            packet, record = task_minimal_view(task, env, rng)
            views["task_minimal"] = packet
            decisions[record.decision] = decisions.get(record.decision, 0) + 1
            row: dict[str, Any] = {"task_id": task["task_id"], "environment_id": task["environment_id"],
                                   "decision": record.decision, "reason_codes": record.reason_codes}
            for version in VERSIONS:
                view = views[version]
                t = totals[version]
                if view is not None:
                    t["tasks_with_output"] += 1
                    t["leaves"] += _leaves(view)
                    t["bytes"] += _bytes(view)
                    t["identifier_values"] += _identifier_values(view)
                    t["non_task_sections"] += len(_sections(view) & set(NON_TASK_SECTIONS))
                    # Linkability: a raw or masked view still carries the environment's stable ids
                    # (contact_id, photo_id, app_id); a packet carries only task-scoped tokens.
                    if version != "task_minimal":
                        linkable[version] += 1
                    else:
                        for key in ("recipient_token", "photo_token", "task_id_alias"):
                            stable_tokens.setdefault(view[key], set()).add(task["task_id"])
                checks = necessary_conditions(version, view, task, env)
                for k, ok in checks.items():
                    fidelity[version][k] += int(ok)
                row[version] = {"leaves": _leaves(view) if view is not None else 0,
                                "bytes": _bytes(view) if view is not None else 0,
                                "identifier_values": _identifier_values(view) if view is not None else 0, **checks}
            pairs.write(json.dumps(row, ensure_ascii=False) + "\n")

    n = len(tasks)
    allow = totals["task_minimal"]["tasks_with_output"]
    reused = sum(1 for owners in stable_tokens.values() if len(owners) > 1)
    comparison = {
        "schema": "privagent.share_photo_comparison.v1",
        "tasks": n, "decisions": decisions, "seed": seed,
        "versions": {
            v: {
                "tasks_with_output": totals[v]["tasks_with_output"],
                "mean_leaves_per_task": round(totals[v]["leaves"] / n, 1),
                "mean_bytes_per_task": round(totals[v]["bytes"] / n, 1),
                "mean_leaves_per_output": round(totals[v]["leaves"] / max(totals[v]["tasks_with_output"], 1), 1),
                "mean_bytes_per_output": round(totals[v]["bytes"] / max(totals[v]["tasks_with_output"], 1), 1),
                "identifier_values_total": totals[v]["identifier_values"],
                "non_task_sections_per_output": round(totals[v]["non_task_sections"] / max(totals[v]["tasks_with_output"], 1), 1),
                "cross_task_linkable_outputs": linkable[v] if v != "task_minimal" else reused,
                "fidelity": {k: f"{c}/{n}" for k, c in fidelity[v].items()},
            } for v in VERSIONS
        },
        "task_minimal_fidelity_over_allowed": {k: f"{fidelity['task_minimal'][k]}/{allow}" for k in fidelity["task_minimal"]},
        "notes": [
            "Raw and Masked always produce an output; Task-Minimal produces a packet only for ALLOW decisions, "
            "so its per-task means include zeros for ASK / LOCAL / STOP tasks (the planner sees nothing).",
            "Masked keeps relationships, app lists, message semantics and all non-task sections: it hides values, "
            "not the fact that the phone holds them.",
            "identifier_values counts leaves under identifier keys that are not placeholders; the data is synthetic.",
            "cross_task_linkable_outputs: Raw/Masked outputs carry stable contact/photo/app ids per environment; "
            "for Task-Minimal it is the number of tokens seen in more than one task (expected 0).",
        ],
    }
    (out / "comparison.json").write_text(json.dumps(comparison, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {"inputs": {str(dataset_path): _sha256(dataset_path)},
                "outputs": {p.name: _sha256(out / p.name) for p in map(Path, ("comparison.json", "pairs.jsonl"))},
                "command": f"python -m receiver.share_photo_compare --dataset {dataset_path} --out {out} --seed {seed}"}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return comparison


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20261107)
    args = parser.parse_args(argv)
    comparison = run(args.dataset, args.out, args.seed)
    print(json.dumps({k: comparison[k] for k in ("tasks", "decisions", "versions", "task_minimal_fidelity_over_allowed")},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
