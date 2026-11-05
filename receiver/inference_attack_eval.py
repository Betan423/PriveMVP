"""Scenario 2 (CHECK_MESSAGE): what can an attacker infer from the packet facts alone?

python3 -m receiver.inference_attack_eval --dataset <source_raw_v5.json> --catalog <template_catalog.json> --out <dir>

The PETs Prize red team recovered sensitive columns from boolean flags almost perfectly. The CHECK_MESSAGE
packet is a set of such flags, so this evaluation plays that attacker: it sees only ``packet["facts"]``
(never the text, never the excerpt) for every v5 message and tries to recover

  1. the template family (32 classes) and the exact message text, using a lookup table learned on other
     phone environments (leave-environments-out folds; the lookup is the Bayes-optimal classifier for
     discrete features, so no model choice can do better on this data);
  2. equivalence classes: how many messages share each feature signature (k-anonymity style k), how many
     families and distinct texts sit inside each class, and the conditional entropy of family given the
     signature;
  3. cross-message accumulation: whether the packets of one phone can be told apart from another phone's
     (environment profile k) and which packet fields could link packets at all.

The optional redacted excerpt is measured separately, as the view an attacker gets when the user opts in.
Outputs (all in --out): inference_attack.json, classes.csv, manifest.json. The data is synthetic and the
template bank is finite, so "recovering the family" means recovering the authored scam category, which is
the signal the packet exists to carry; the numbers bound what else leaks, not real-world re-identification.
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any

from .assistant import forbidden_hits
from .phone_sim import build_packet, extract_facts

FOLDS = 4
RANDOM_FIELDS = ("task_id_alias", "request_nonce", "issued_at_ms")
VIEWS = ("raw_text", "facts_and_excerpt", "facts_only")
TARGETS = ("template_id", "text", "expected_level")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _signature(facts: dict[str, Any]) -> str:
    return json.dumps(facts, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _entropy(counter: collections.Counter[str]) -> float:
    total = sum(counter.values())
    return -sum(c / total * math.log2(c / total) for c in counter.values()) if total else 0.0


def _load_messages(dataset_path: Path) -> list[dict[str, Any]]:
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    rows = []
    for index, environment in enumerate(data["phone_environments"]):
        for message in environment["continuous_state_data"]["messages"]:
            facts = extract_facts(message["text"], "notification")
            excerpt = build_packet(message["text"], source_kind="notification", allow_excerpt=True)["redacted_excerpt"]
            rows.append({
                "environment_id": environment["environment_id"],
                "fold": index % FOLDS,
                "message_id": message["message_id"],
                "template_id": message["template_id"],
                "text": message["text"],
                # The raw-text attacker reads the message; for family guessing he only has to ignore the numbers.
                "lookup": {"raw_text": re.sub(r"\d+", "#", message["text"])},
                "views": {"raw_text": message["text"],
                          "facts_and_excerpt": _signature({**facts, "redacted_excerpt": excerpt}),
                          "facts_only": _signature(facts)},
            })
    return rows


def lookup_attack(rows: list[dict[str, Any]], view: str, target: str) -> dict[str, Any]:
    """Leave-environments-out: guess the most common target value seen with the same signature."""
    if view == "raw_text" and target == "text":
        n = len(rows)
        return {"target": target, "view": view, "folds": 0, "accuracy": 1.0, "correct": n, "denominator": n,
                "unseen_signatures_in_test": 0, "majority_class_baseline": round(
                    collections.Counter(r[target] for r in rows).most_common(1)[0][1] / n, 4),
                "distinct_targets": len({r[target] for r in rows}),
                "accuracy_by_expected_level": {level: f"{c}/{c}" for level, c in sorted(
                    collections.Counter(r["expected_level"] for r in rows).items())},
                "note": "the attacker holds the text; nothing to infer"}
    correct = 0
    unseen = 0
    by_level: dict[str, list[int]] = collections.defaultdict(lambda: [0, 0])
    for fold in range(FOLDS):
        table: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
        overall: collections.Counter[str] = collections.Counter()
        for row in rows:
            if row["fold"] != fold:
                table[row["lookup"].get(view, row["views"][view])][row[target]] += 1
                overall[row[target]] += 1
        fallback = overall.most_common(1)[0][0]
        for row in rows:
            if row["fold"] != fold:
                continue
            seen = table.get(row["lookup"].get(view, row["views"][view]))
            if seen is None:
                unseen += 1
            guess = seen.most_common(1)[0][0] if seen else fallback
            hit = int(guess == row[target])
            correct += hit
            by_level[row["expected_level"]][0] += hit
            by_level[row["expected_level"]][1] += 1
    n = len(rows)
    majority = collections.Counter(r[target] for r in rows).most_common(1)[0][1]
    return {
        "target": target, "view": view, "folds": FOLDS,
        "accuracy": round(correct / n, 4), "correct": correct, "denominator": n,
        "unseen_signatures_in_test": unseen,
        "majority_class_baseline": round(majority / n, 4),
        "distinct_targets": len({r[target] for r in rows}),
        "accuracy_by_expected_level": {level: f"{hit}/{total}" for level, (hit, total) in sorted(by_level.items())},
    }


def equivalence_classes(rows: list[dict[str, Any]], view: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    groups: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        groups[row["views"][view]].append(row)
    classes = []
    family_entropy_given_sig = 0.0
    text_entropy_given_sig = 0.0
    n = len(rows)
    for signature, members in groups.items():
        families = collections.Counter(m["template_id"] for m in members)
        texts = collections.Counter(m["text"] for m in members)
        family_entropy_given_sig += len(members) / n * _entropy(families)
        text_entropy_given_sig += len(members) / n * _entropy(texts)
        classes.append({
            "view": view, "signature": signature, "k": len(members),
            "families": len(families), "distinct_texts": len(texts),
            "environments": len({m["environment_id"] for m in members}),
            "majority_family": families.most_common(1)[0][0],
            "majority_family_share": round(families.most_common(1)[0][1] / len(members), 4),
        })
    classes.sort(key=lambda c: (-c["k"], c["signature"]))
    sizes = sorted(c["k"] for c in classes)
    single_family = [c for c in classes if c["families"] == 1]
    single_text = [c for c in classes if c["distinct_texts"] == 1]
    summary = {
        "view": view, "classes": len(classes), "min_k": sizes[0], "median_k": sizes[len(sizes) // 2], "max_k": sizes[-1],
        "messages_in_classes_with_k_below_5": sum(c["k"] for c in classes if c["k"] < 5),
        "classes_with_one_family": len(single_family),
        "messages_in_one_family_classes": sum(c["k"] for c in single_family),
        "classes_with_one_text": len(single_text),
        "messages_in_one_text_classes": sum(c["k"] for c in single_text),
        "family_entropy_bits": round(_entropy(collections.Counter(r["template_id"] for r in rows)), 4),
        "family_entropy_given_signature_bits": round(family_entropy_given_sig, 4),
        "text_entropy_bits": round(_entropy(collections.Counter(r["text"] for r in rows)), 4),
        "text_entropy_given_signature_bits": round(text_entropy_given_sig, 4),
    }
    return summary, classes


def cross_message_accumulation(rows: list[dict[str, Any]], view: str) -> dict[str, Any]:
    """Can the 32 packets of one phone be told apart from another phone's 32 packets?"""
    profiles: collections.Counter[tuple[str, ...]] = collections.Counter()
    per_env: dict[str, list[str]] = collections.defaultdict(list)
    for row in rows:
        per_env[row["environment_id"]].append(row["views"][view])
    for signatures in per_env.values():
        profiles[tuple(sorted(signatures))] += 1
    sizes = sorted(profiles.values())
    return {
        "view": view, "environments": len(per_env), "distinct_environment_profiles": len(profiles),
        "min_environment_k": sizes[0], "max_environment_k": sizes[-1],
        "environments_uniquely_identified": sum(1 for s in sizes if s == 1),
    }


def packet_field_audit(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Which packet fields change per send, change with the message, or never change?"""
    sample = rows[0]["text"]
    first, second = (build_packet(sample, source_kind="notification") for _ in range(2))
    per_send_random = sorted(k for k in first if first[k] != second[k])
    varying: set[str] = set()
    reference: dict[str, Any] | None = None
    for row in rows:
        packet = build_packet(row["text"], source_kind="notification", issued_at_ms=0, request_nonce="REQ_FIXED")
        packet["task_id_alias"] = "TASK_FIXED"
        if reference is None:
            reference = packet
            continue
        varying.update(key for key, value in packet.items() if value != reference[key])
    assert reference is not None
    return {
        "fields_random_per_send": per_send_random,
        "fields_varying_with_message": sorted(varying),
        "fields_constant_for_every_message": sorted(k for k in reference if k not in varying and k not in RANDOM_FIELDS),
        "stable_identifier_fields": sorted(k for k in varying if k not in {"facts", "redacted_excerpt"}),
    }


def run(dataset_path: Path, catalog_path: Path, out: Path) -> dict[str, Any]:
    from .message_eval import _expected_level  # one definition of the expected level

    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    template_tags = {item["template_id"]: set(item["semantic_tags"]) for item in catalog["message_templates"]}
    rows = _load_messages(dataset_path)
    for row in rows:
        row["expected_level"] = _expected_level(template_tags.get(row["template_id"], set()))
    out.mkdir(parents=True, exist_ok=True)

    attacks = [lookup_attack(rows, view, target) for view in VIEWS for target in TARGETS]
    identifier_leaks = {
        "raw_text": sum(1 for r in rows if forbidden_hits({"text": r["text"]})),
        "facts_and_excerpt": sum(1 for r in rows if forbidden_hits({"excerpt": json.loads(r["views"]["facts_and_excerpt"])["redacted_excerpt"] or ""})),
        "facts_only": 0,
    }
    classes_summary: dict[str, Any] = {}
    all_classes: list[dict[str, Any]] = []
    for view in VIEWS:
        summary, classes = equivalence_classes(rows, view)
        classes_summary[view] = summary
        all_classes.extend(classes)
    with (out / "classes.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(all_classes[0]))
        writer.writeheader()
        writer.writerows(all_classes)

    result = {
        "schema": "privagent.check_message_inference_attack.v1",
        "messages": len(rows), "environments": len({r["environment_id"] for r in rows}),
        "template_families": len({r["template_id"] for r in rows}),
        "distinct_texts": len({r["text"] for r in rows}),
        "fact_fields": len(json.loads(rows[0]["views"]["facts_only"])),
        "attacker_view": {
            "raw_text": "the message as it sits on the phone, nothing masked and nothing dropped (what an agent gets "
                        "when the app forwards the notification)",
            "facts_only": "packet facts as sent by default (no excerpt); task_id_alias, request_nonce and issued_at_ms "
                          "are fresh per send and carry nothing about the message",
            "facts_and_excerpt": "facts plus the redacted excerpt the user can opt in to (dropped on high risk)",
        },
        "inference_attacks": attacks,
        "messages_with_direct_identifier_visible": identifier_leaks,
        "equivalence_classes": classes_summary,
        "cross_message_accumulation": {view: cross_message_accumulation(rows, view) for view in VIEWS},
        "packet_fields": packet_field_audit(rows),
        "notes": [
            "Lookup-table attack = Bayes-optimal classifier on discrete features; folds split by phone environment "
            "so the attacker never trains on the phone it attacks.",
            "The template bank is finite and the facts are designed to encode the scam category, so family accuracy "
            "on scam families is expected to be high; the privacy question is what leaks beyond that category.",
            "k counts messages in the whole set sharing one signature; a one-family class still only names an "
            "authored template, not a person, sender or exact wording unless distinct_texts is 1.",
            "Synthetic data: no real sender, phone number or wording is at stake; numbers describe the channel, "
            "not people.",
        ],
    }
    (out / "inference_attack.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {"inputs": {str(p): _sha256(p) for p in (dataset_path, catalog_path)},
                "outputs": {name: _sha256(out / name) for name in ("inference_attack.json", "classes.csv")},
                "command": f"python -m receiver.inference_attack_eval --dataset {dataset_path} "
                           f"--catalog {catalog_path} --out {out}"}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    result = run(args.dataset, args.catalog, args.out)
    keys = ("messages", "fact_fields", "inference_attacks", "equivalence_classes", "cross_message_accumulation",
            "packet_fields")
    print(json.dumps({k: result[k] for k in keys}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
