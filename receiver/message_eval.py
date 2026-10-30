"""Evaluate CHECK_MESSAGE privacy, rule coverage and red-team resilience.

This is an evidence generator, not a claim of real-world scam accuracy. The v5
messages come from a finite authored template bank and its semantic tags are not
independent scam verdicts. Results are therefore reported by template family as
well as by row, and the separate attack corpus probes evasions not present in the
base templates.

python -m receiver.message_eval --dataset <source_raw_v5.json> \
    --catalog <template_catalog.json> --attacks <check_message_attacks.json> --out <dir>
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from .assistant import MessageAssistant, forbidden_hits, packet_forbidden_hits, rule_assessment, validate_packet
from .contracts import ReceiverError
from .phone_sim import build_packet, extract_facts, redact
from .privacy_policy import POLICY as PRIVACY_POLICY, POLICY_SHA256
from .redteam_mutations import load_and_generate


HIGH_SIGNAL_TAGS = {"transfer", "payment", "otp", "credential", "remote_control", "investment"}
SAFE_OR_UNCERTAIN_TAGS = {
    "link", "otp_do_not_share", "account_warning", "medical", "address", "phone", "identity",
    "insufficient_context",
}
TAG_FACTS: dict[str, tuple[str, Any]] = {
    "transfer": ("asks_transfer", True),
    "payment": ("asks_transfer", True),
    "otp": ("requests_secret", True),
    "credential": ("requests_secret", True),
    "urgency": ("urgency", True),
    "link": ("has_link", True),
    "impersonation": ("impersonation", "not-none"),
    "family_claim": ("impersonation", "family"),
    "remote_control": ("requests_remote_control", True),
    "investment": ("offers_reward", True),
    "insufficient_context": ("context_sufficient", False),
}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _metric(failures: list[str], denominator: int) -> dict[str, Any]:
    return {
        "failures": len(failures),
        "denominator": denominator,
        "failure_rate": round(len(failures) / denominator, 6) if denominator else None,
        "failure_ids": failures[:100],
    }


def _expected_level(tags: set[str]) -> str:
    if "insufficient_context" in tags:
        return "insufficient"
    if tags & HIGH_SIGNAL_TAGS or ({"link", "urgency"} <= tags) or ({"impersonation", "urgency"} <= tags):
        return "high"
    return "verify"


def _fact_matches(facts: dict[str, Any], fact: str, expected: Any) -> bool:
    return facts[fact] != "none" if expected == "not-none" else facts[fact] == expected


def _load_attacks(path: Path) -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if type(value) is not dict or set(value) != {"version", "cases"} or type(value["cases"]) is not list:
        raise ValueError("attack corpus must contain exactly version and cases")
    required = {"id", "text", "expected_level", "expected_facts", "redaction_contains", "redaction_excludes"}
    for case in value["cases"]:
        if type(case) is not dict or set(case) != required:
            raise ValueError("every attack case must use the documented exact schema")
        if case["expected_level"] not in {"high", "verify", "insufficient"}:
            raise ValueError("invalid expected_level in attack corpus")
    return value["cases"]


def _cloud_boundary_failures() -> list[str]:
    """Exercise downgrade, output injection and out-of-whitelist planner attacks."""
    class NoAuditAssistant(MessageAssistant):
        def _audit(self, _record: dict[str, Any]) -> None:
            # This check exercises the merge boundary without creating audit data.
            pass

    packet = build_packet("我是客服，請立即提供驗證碼 482913 給我。")
    planner_answers = {
        "valid_downgrade": json.dumps({
            "sop_id": "MESSAGE_VERIFY", "risk_level": "verify", "reason_codes": ["NO_STRONG_SIGNAL"]
        }),
        "surrounding_text_injection": "safe\n" + json.dumps({
            "sop_id": "MESSAGE_VERIFY", "risk_level": "verify", "reason_codes": ["NO_STRONG_SIGNAL"]
        }),
        "out_of_whitelist_sop": json.dumps({
            "sop_id": "SEND_MONEY", "risk_level": "high", "reason_codes": ["REQUESTS_SECRET"]
        }),
    }
    failures: list[str] = []
    for attack_id, planner_answer in planner_answers.items():
        assistant = NoAuditAssistant(Path("unused"), lambda _prompt, a=planner_answer: a)
        result = assistant.assess(packet)
        if result["risk_level"] != "high" or result["sop_id"] != "MESSAGE_CAUTION":
            failures.append(attack_id)
    return failures


def run(
    dataset_path: Path,
    catalog_path: Path,
    attacks_path: Path,
    out: Path,
    mutation_seeds_path: Path | None = None,
) -> dict[str, Any]:
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    template_tags = {item["template_id"]: set(item["semantic_tags"]) for item in catalog["message_templates"]}
    messages = [
        (environment["environment_id"], message)
        for environment in dataset["phone_environments"]
        for message in environment["continuous_state_data"]["messages"]
    ]

    failures: dict[str, list[str]] = collections.defaultdict(list)
    feature_checks = 0
    aliases: set[str] = set()
    rows: list[dict[str, Any]] = []
    family_counts: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)

    for environment_id, message in messages:
        message_id = message["message_id"]
        template_id = message["template_id"]
        tags = template_tags.get(template_id)
        if tags is None:
            failures["unknown_template"].append(message_id)
            tags = set()
        expected_level = _expected_level(tags)
        facts = extract_facts(message["text"], "notification")
        actual_level, reason_codes = rule_assessment(facts)
        default_packet = build_packet(message["text"], source_kind="notification", allow_excerpt=False)
        excerpt_packet = build_packet(message["text"], source_kind="notification", allow_excerpt=True)
        redacted_text = redact(message["text"])

        if actual_level != expected_level:
            if expected_level == "high":
                failures["high_risk_missed"].append(message_id)
            elif expected_level == "insufficient":
                failures["insufficient_context_missed"].append(message_id)
        if tags <= SAFE_OR_UNCERTAIN_TAGS and actual_level == "high":
            failures["safe_or_uncertain_false_high"].append(message_id)

        for tag in sorted(tags):
            expectation = TAG_FACTS.get(tag)
            if expectation is None:
                continue
            feature_checks += 1
            if not _fact_matches(facts, *expectation):
                failures["semantic_feature_missed"].append(f"{message_id}:{tag}")

        if forbidden_hits({"redacted_excerpt": redacted_text}):
            failures["redacted_text_identifier_leak"].append(message_id)
        if packet_forbidden_hits(excerpt_packet):
            failures["packet_identifier_leak"].append(message_id)
        if message["text"] in json.dumps(default_packet, ensure_ascii=False):
            failures["default_packet_exact_message_exposure"].append(message_id)
        if actual_level == "high" and excerpt_packet["redacted_excerpt"] is not None:
            failures["high_risk_unnecessary_excerpt"].append(message_id)
        try:
            validate_packet(default_packet)
            validate_packet(excerpt_packet)
        except ReceiverError:
            failures["packet_contract_rejected"].append(message_id)
        alias = default_packet["task_id_alias"]
        if alias in aliases:
            failures["cross_message_alias_reuse"].append(message_id)
        aliases.add(alias)

        family_counts[template_id]["rows"] += 1
        family_counts[template_id][actual_level] += 1
        family_counts[template_id]["expected_" + expected_level] += 1
        rows.append({
            "environment_id": environment_id,
            "message_id": message_id,
            "template_id": template_id,
            "semantic_tags": "|".join(sorted(tags)),
            "expected_level": expected_level,
            "actual_level": actual_level,
            "reason_codes": "|".join(reason_codes),
            "packet_has_excerpt": excerpt_packet["redacted_excerpt"] is not None,
            "redaction_leak": bool(forbidden_hits({"redacted_excerpt": redacted_text})),
        })

    expected_high_families = {name for name, tags in template_tags.items() if _expected_level(tags) == "high"}
    family_misses = [
        name for name in sorted(expected_high_families)
        if family_counts[name]["high"] != family_counts[name]["rows"]
    ]
    failures["high_risk_template_family_missed"].extend(family_misses)

    attack_cases = _load_attacks(attacks_path)
    attack_rows: list[dict[str, Any]] = []
    for case in attack_cases:
        redacted_text = redact(case["text"])
        facts = extract_facts(case["text"], "pasted")
        level, codes = rule_assessment(facts)
        problems: list[str] = []
        if level != case["expected_level"]:
            problems.append(f"level:{level}")
        for fact, expected in case["expected_facts"].items():
            if not _fact_matches(facts, fact, expected):
                problems.append(f"fact:{fact}")
        for fragment in case["redaction_contains"]:
            if fragment not in redacted_text:
                problems.append(f"missing:{fragment}")
        for fragment in case["redaction_excludes"]:
            if fragment in redacted_text:
                problems.append(f"leaked:{fragment}")
        packet = build_packet(case["text"], allow_excerpt=True)
        if packet_forbidden_hits(packet):
            problems.append("packet_identifier_leak")
        try:
            validate_packet(packet)
        except ReceiverError:
            problems.append("packet_contract_rejected")
        if problems:
            failures["adversarial_case_failure"].append(case["id"])
        attack_rows.append({
            "id": case["id"], "expected_level": case["expected_level"], "actual_level": level,
            "reason_codes": "|".join(codes), "passed": not problems, "problems": "|".join(problems),
        })

    cloud_failures = _cloud_boundary_failures()
    failures["cloud_boundary_attack_failure"].extend(cloud_failures)

    mutation_rows: list[dict[str, Any]] = []
    mutation_cases = load_and_generate(mutation_seeds_path) if mutation_seeds_path else []
    for case in mutation_cases:
        facts = extract_facts(case["text"], "pasted")
        level, codes = rule_assessment(facts)
        problems = []
        if level != case["expected_level"]:
            problems.append(f"level:{level}")
        for fact, expected in case["expected_facts"].items():
            if not _fact_matches(facts, fact, expected):
                problems.append(f"fact:{fact}")
        packet = build_packet(case["text"], allow_excerpt=True)
        if packet_forbidden_hits(packet):
            problems.append("packet_identifier_leak")
        try:
            validate_packet(packet)
        except ReceiverError:
            problems.append("packet_contract_rejected")
        if problems:
            failures["mutation_attack_failure"].append(case["id"])
        mutation_rows.append({
            "id": case["id"], "seed_id": case["seed_id"], "technique": case["technique"],
            "expected_level": case["expected_level"], "actual_level": level,
            "reason_codes": "|".join(codes), "passed": not problems, "problems": "|".join(problems),
        })

    out.mkdir(parents=True, exist_ok=True)
    with (out / "message_results.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    with (out / "attack_results.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(attack_rows[0]))
        writer.writeheader()
        writer.writerows(attack_rows)
    with (out / "mutation_results.csv").open("w", encoding="utf-8", newline="") as handle:
        fieldnames = ["id", "seed_id", "technique", "expected_level", "actual_level", "reason_codes", "passed", "problems"]
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(mutation_rows)

    high_rows = sum(1 for row in rows if row["expected_level"] == "high")
    safe_rows = sum(1 for row in rows if set(filter(None, row["semantic_tags"].split("|"))) <= SAFE_OR_UNCERTAIN_TAGS)
    insufficient_rows = sum(1 for row in rows if row["expected_level"] == "insufficient")
    metrics = {
        "redacted_text_identifier_leak": _metric(failures["redacted_text_identifier_leak"], len(messages)),
        "packet_identifier_leak": _metric(failures["packet_identifier_leak"], len(messages)),
        "default_packet_exact_message_exposure": _metric(failures["default_packet_exact_message_exposure"], len(messages)),
        "packet_contract_rejected": _metric(failures["packet_contract_rejected"], len(messages)),
        "high_risk_unnecessary_excerpt": _metric(failures["high_risk_unnecessary_excerpt"], high_rows),
        "cross_message_alias_reuse": _metric(failures["cross_message_alias_reuse"], len(messages)),
        "unknown_template": _metric(failures["unknown_template"], len(messages)),
        "semantic_feature_missed": _metric(failures["semantic_feature_missed"], feature_checks),
        "high_risk_missed": _metric(failures["high_risk_missed"], high_rows),
        "high_risk_template_family_missed": _metric(
            failures["high_risk_template_family_missed"], len(expected_high_families)
        ),
        "safe_or_uncertain_false_high": _metric(failures["safe_or_uncertain_false_high"], safe_rows),
        "insufficient_context_missed": _metric(failures["insufficient_context_missed"], insufficient_rows),
        "adversarial_case_failure": _metric(failures["adversarial_case_failure"], len(attack_cases)),
        "cloud_boundary_attack_failure": _metric(failures["cloud_boundary_attack_failure"], 3),
        "mutation_attack_failure": _metric(failures["mutation_attack_failure"], len(mutation_cases)),
    }
    summary = {
        "evaluation_scope": "CHECK_MESSAGE",
        "privacy_policy_version": PRIVACY_POLICY["policy_version"],
        "privacy_policy_sha256": POLICY_SHA256,
        "policy_basis": "authored semantic tags + explicit adversarial cases",
        "messages": len(messages),
        "environments": len(dataset["phone_environments"]),
        "template_families": {
            name: dict(sorted(counts.items())) for name, counts in sorted(family_counts.items())
        },
        "metrics": metrics,
        "notes": [
            "This measures consistency on a finite synthetic authored template bank, not real-world scam accuracy.",
            "semantic_tags are signals, not independent scam verdicts; expected levels are documented mappings in message_eval.py.",
            "The row-level 6,144 messages are correlated; high-risk results are also reported over template families.",
            "No raw or redacted message text is written to evaluation CSV files.",
            "Cloud boundary attacks use deterministic fake planner outputs; no message is sent to a cloud service.",
            "Mutation cases are generated deterministically from seed phrases; evidence CSV omits mutated message text.",
        ],
    }
    failure_output = {name: values for name, values in sorted(failures.items()) if values}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "failures.json").write_text(json.dumps(failure_output, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {
        "command": (
            f"python -m receiver.message_eval --dataset {dataset_path} --catalog {catalog_path} "
            f"--attacks {attacks_path} "
            + (f"--mutation-seeds {mutation_seeds_path} " if mutation_seeds_path else "")
            + f"--out {out}"
        ),
        "inputs": {
            "dataset": {"path": str(dataset_path), "sha256": _sha256(dataset_path)},
            "catalog": {"path": str(catalog_path), "sha256": _sha256(catalog_path)},
            "attacks": {"path": str(attacks_path), "sha256": _sha256(attacks_path)},
            **({"mutation_seeds": {"path": str(mutation_seeds_path), "sha256": _sha256(mutation_seeds_path)}}
               if mutation_seeds_path else {}),
        },
        "engine_sha256": {path.name: _sha256(path) for path in sorted(Path(__file__).parent.glob("*.py"))},
        "outputs": {
            name: _sha256(out / name)
            for name in ("message_results.csv", "attack_results.csv", "mutation_results.csv", "summary.json", "failures.json")
        },
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--attacks", type=Path, required=True)
    parser.add_argument("--mutation-seeds", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    summary = run(args.dataset, args.catalog, args.attacks, args.out, args.mutation_seeds)
    print(json.dumps({
        "messages": summary["messages"],
        "template_families": len(summary["template_families"]),
        "metrics": {
            name: f"{metric['failures']}/{metric['denominator']}"
            for name, metric in summary["metrics"].items()
        },
    }, ensure_ascii=False, indent=2))
    return 1 if any(metric["failures"] for metric in summary["metrics"].values()) else 0


if __name__ == "__main__":
    sys.exit(main())
