"""Run the core-scenario policy engine over every v5 task and write evidence files.

python3 -m receiver.core_eval --dataset <source_raw_v5.json> --out <dir>

Outputs (all in --out): derived_tasks.csv, packets.jsonl, failures.json, summary.json, manifest.json.
Every metric carries numerator, denominator and the failing task ids. Interpreter accuracy is
measured against the dataset's authored parameters, which the rules were written against:
it shows consistency with the templates, not accuracy on unseen phrasing.
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import random
import sys
from pathlib import Path
from typing import Any

from .assistant import forbidden_hits, validate_packet
from .core_tasks import (POLICY_VERSION, RESCUE_FACTS_BY_CATEGORY, SHARE_FACTS, decide_task, validate_plan_packet)
from .contracts import ReceiverError

CORE = ("SHARE_PHOTO", "CHECK_MESSAGE", "RESCUE_PHONE")
# Facts each intent may disclose; anything else in a packet counts as unnecessary disclosure.
ALLOWED_FACTS = {
    "SHARE_PHOTO": set(SHARE_FACTS) | {"selection"},
    "CHECK_MESSAGE": {"asks_transfer", "requests_secret", "urgency", "has_link", "offers_reward",
                      "requests_remote_control", "context_sufficient", "impersonation", "source_kind"},
}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _metric(failures: list[str], denominator: int) -> dict[str, Any]:
    return {"numerator": len(failures), "denominator": denominator,
            "rate": round(len(failures) / denominator, 6) if denominator else None, "failures": failures[:50]}


def run(dataset_path: Path, out: Path, seed: int) -> dict[str, Any]:
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    environments = {e["environment_id"]: e for e in data["phone_environments"]}
    tasks = [t for t in data["task_requests"] if t["scenario_id"] in CORE]
    rng = random.Random(seed)
    records = [decide_task(t, environments[t["environment_id"]], rng) for t in tasks]

    leaks, unnecessary, violations, invalid, interpret_errors, label_mismatch = [], [], [], [], [], []
    token_owner: dict[str, str] = {}
    reused: list[str] = []
    for task, record in zip(tasks, records):
        labels = task["fixture_labels"]
        # Interpreter vs authored parameters.
        expected_intent = task["scenario_id"]
        got = record.interpreted
        params = task["parameters"]
        ok = got.intent == expected_intent
        if ok and expected_intent == "SHARE_PHOTO":
            ok = got.relation == params["recipient_relation"] and got.selection == params["selection_method"]
        if ok and expected_intent == "RESCUE_PHONE":
            ok = got.symptom_category == params["symptom_category"] and got.requested_scope == params["requested_scope"]
        if not ok:
            interpret_errors.append(record.task_id)
        # Engine's own recipient resolution vs the fixture label.
        if expected_intent == "SHARE_PHOTO" and not task["cancelled"] and labels["recipient_binding_case"] != "unique":
            if not any(c in record.reason_codes for c in ("RECIPIENT_NOT_BOUND", "RECIPIENT_AMBIGUOUS")) \
                    and record.decision in ("ALLOW", "LOCAL"):
                label_mismatch.append(record.task_id)
        packet = record.packet
        if packet is None:
            continue
        # Authorization: a cloud packet needs consent, a non-local mode, all grants and no cancellation.
        if (task["cancelled"] or task["privacy_mode"] == "STRICT_LOCAL" or not labels["cloud_features_consent"]
                or "cloud_features" not in task["grants"] or record.decision != "ALLOW"
                or (expected_intent == "SHARE_PHOTO" and task["confirmation"] != "approved")):
            violations.append(record.task_id)
        if forbidden_hits(packet):
            leaks.append(record.task_id)
        allowed = (ALLOWED_FACTS.get(expected_intent) or
                   {"symptom_category", "symptom_detail", "requested_scope"} |
                   set(RESCUE_FACTS_BY_CATEGORY[packet["facts"]["symptom_category"]]))
        if set(packet["facts"]) - allowed:
            unnecessary.append(record.task_id)
        try:
            (validate_packet if expected_intent == "CHECK_MESSAGE" else validate_plan_packet)(packet)
        except ReceiverError:
            invalid.append(record.task_id)
        for key in ("task_id_alias", "recipient_token", "photo_token"):
            token = packet.get(key)
            if token is None:
                continue
            if token in token_owner and token_owner[token] != record.task_id:
                reused.append(record.task_id)
            token_owner[token] = record.task_id

    out.mkdir(parents=True, exist_ok=True)
    with (out / "derived_tasks.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["task_id", "environment_id", "intent", "subscenario", "decision", "sop_id", "reason_codes",
                         "required_data", "accessed_data", "disclosed_data", "interpreted_intent", "policy_version"])
        for r in records:
            writer.writerow([r.task_id, r.environment_id, r.intent, r.subscenario, r.decision, r.sop_id,
                             "|".join(r.reason_codes), "|".join(r.required_data), "|".join(r.accessed_data),
                             "|".join(r.disclosed_data), r.interpreted.intent, POLICY_VERSION])
    with (out / "packets.jsonl").open("w", encoding="utf-8") as handle:
        for r in records:
            if r.packet is not None:
                handle.write(json.dumps({"task_id": r.task_id, "packet": r.packet}, ensure_ascii=False) + "\n")

    decisions: dict[str, dict[str, int]] = {}
    for intent in CORE:
        counter = collections.Counter(r.decision for r in records if r.intent == intent)
        decisions[intent] = {k: counter.get(k, 0) for k in ("ALLOW", "ASK", "LOCAL", "STOP")}
    by_sub = collections.defaultdict(collections.Counter)
    for r in records:
        by_sub[f"{r.intent}/{r.subscenario}"][r.decision] += 1
    packets = sum(1 for r in records if r.packet is not None)
    summary = {
        "policy_version": POLICY_VERSION,
        "tasks": len(records),
        "packets": packets,
        "decisions": decisions,
        "decisions_by_subscenario": {k: dict(v) for k, v in sorted(by_sub.items())},
        "metrics": {
            "direct_identifier_leak": _metric(leaks, packets),
            "unnecessary_disclosure": _metric(unnecessary, packets),
            "authorization_violation": _metric(violations, packets),
            "cross_task_token_reuse": _metric(reused, packets),
            "packet_contract_rejected": _metric(invalid, packets),
            "interpreter_mismatch_vs_authored_params": _metric(interpret_errors, len(records)),
            "recipient_label_mismatch": _metric(label_mismatch, len(records)),
        },
        "notes": [
            "Interpreter and rules were written against these templates; this measures consistency, not generalisation.",
            "CHECK_MESSAGE ignores snapshot age because the selected message is a fixed reference.",
            "Mapping (token -> contact/photo id) exists only inside decide_task and is dropped when it returns.",
        ],
    }
    failures = {name: m["failures"] for name, m in summary["metrics"].items() if m["failures"]}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "failures.json").write_text(json.dumps(failures, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {
        "command": f"python3 -m receiver.core_eval --dataset {dataset_path} --out {out} --seed {seed}",
        "input": {"path": str(dataset_path), "sha256": _sha256(dataset_path)},
        "engine_sha256": {p.name: _sha256(p) for p in sorted(Path(__file__).parent.glob("*.py"))},
        "outputs": {p.name: _sha256(out / p.name) for p in
                    map(Path, ("derived_tasks.csv", "packets.jsonl", "summary.json", "failures.json"))},
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20261107)
    args = parser.parse_args(argv)
    summary = run(args.dataset, args.out, args.seed)
    print(json.dumps({"tasks": summary["tasks"], "packets": summary["packets"], "decisions": summary["decisions"],
                      "metrics": {k: f"{v['numerator']}/{v['denominator']}" for k, v in summary["metrics"].items()}},
                     ensure_ascii=False, indent=2))
    hard = ("direct_identifier_leak", "unnecessary_disclosure", "authorization_violation", "cross_task_token_reuse",
            "packet_contract_rejected")
    return 1 if any(summary["metrics"][name]["numerator"] for name in hard) else 0


if __name__ == "__main__":
    sys.exit(main())
