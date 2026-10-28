"""Offline synthetic task simulator. Never calls AI, accesses a phone, or executes SOPs."""

import argparse
import csv
import json
import re
import sys
from collections import Counter
from pathlib import Path

BASE = Path(__file__).resolve().parent
MAX_BYTES = 20 * 1024 * 1024
MAX_ROWS = 10000


def strict_pairs(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("Duplicate key")
        obj[key] = value
    return obj


def reject_constant(_value):
    raise ValueError("Non-finite number")


def parse(text):
    return json.loads(text, object_pairs_hook=strict_pairs, parse_constant=reject_constant)


def read_config(name):
    return parse((BASE / name).read_text(encoding="utf-8"))


SCHEMA = read_config("task_schema.json")
POLICY = read_config("privacy_policy.json")
SOPS = read_config("sops.json")["definitions"]


def equal(a, b):
    return type(a) is type(b) and a == b


def matches_type(value, kind):
    return type(value) is {"object": dict, "array": list, "string": str, "boolean": bool, "null": type(None)}[kind]


def contract_errors(value, spec, path="$", depth=0):
    """Enforces the subset of JSON Schema used by this exact task contract."""
    if depth > 32:
        return ["schema_depth"]
    if "$ref" in spec:
        if not spec["$ref"].startswith("#/"):
            raise ValueError("Only internal references supported")
        target = SCHEMA
        for name in spec["$ref"][2:].split("/"):
            target = target[name]
        return contract_errors(value, target, path, depth + 1)
    errors = []
    if "const" in spec and not equal(value, spec["const"]):
        errors.append(path + ":const")
    if "enum" in spec and not any(equal(value, v) for v in spec["enum"]):
        errors.append(path + ":enum")
    if "type" in spec:
        kinds = spec["type"] if type(spec["type"]) is list else [spec["type"]]
        if not any(matches_type(value, kind) for kind in kinds):
            return errors + [path + ":type"]
    if type(value) is str and "pattern" in spec and not re.fullmatch(spec["pattern"], value):
        errors.append(path + ":pattern")
    if type(value) is dict:
        props = spec.get("properties", {})
        if any(key not in value for key in spec.get("required", [])):
            errors.append(path + ":required")
        if spec.get("additionalProperties") is False and set(value) - set(props):
            errors.append(path + ":additionalProperties")
        for key, rule in props.items():
            if key in value:
                errors.extend(contract_errors(value[key], rule, path + "." + key, depth + 1))
    if type(value) is list:
        if len(value) > spec.get("maxItems", MAX_ROWS):
            errors.append(path + ":maxItems")
        if spec.get("uniqueItems"):
            strings = [json.dumps(v, sort_keys=True, ensure_ascii=True) for v in value]
            if len(strings) != len(set(strings)):
                errors.append(path + ":uniqueItems")
        if "items" in spec:
            for item in value:
                errors.extend(contract_errors(item, spec["items"], path + "[]", depth + 1))
    for rule in spec.get("allOf", []):
        errors.extend(contract_errors(value, rule, path, depth + 1))
    if "if" in spec and not contract_errors(value, spec["if"], path, depth + 1):
        errors.extend(contract_errors(value, spec.get("then", {}), path, depth + 1))
    return errors


def validate_task(row):
    errors = contract_errors(row, SCHEMA)
    if not errors and row["scenario_id"] == "CHECK_MESSAGE":
        if row["utterance"] == "這個通知要我轉帳怎麼辦" and not row["local_context"]["features"]["asks_transfer"]:
            errors.append("utterance_feature_conflict")
    return errors


def gate_and_route(row):
    scenario = row["scenario_id"]
    required = POLICY["required_data"][scenario]
    if row["cancelled"]:
        return "STOP", "CANCELLED", "STOP", [], "USER_CANCELLED"
    if row["confirmation"] == "denied":
        return "STOP", "BLOCKED", "STOP", [], "USER_DENIED"
    if not set(required).issubset(row["grants"]):
        accessed = ["voice_command"] if "voice_command" in row["grants"] else []
        return "STOP", "BLOCKED", "STOP", accessed, "MISSING_PURPOSE_GRANT"
    accessed = list(required)
    context = row["local_context"]
    if scenario == "SHARE_PHOTO":
        if context["recipient_binding"] != "ready" or context["photo_state"] != "selected":
            return "ASK_USER", "NEEDS_CLARIFICATION", "ASK_USER", accessed, "RESOURCE_NOT_UNIQUE_OR_MISSING"
        if context["share_app"] != "available":
            return "PHOTO_HELP_MANUAL", "GUIDANCE_ONLY", "SHOW_GUIDANCE", accessed, "SHARE_APP_UNAVAILABLE"
        if context["ui_state"] != "ready":
            return "STOP", "BLOCKED", "STOP", accessed, "UI_PRECONDITION_FAILED"
        if row["confirmation"] != "approved":
            return "CONFIRM_PHOTO_SHARE", "WAIT_CONFIRM", "ASK_CONFIRM", accessed, "CONFIRMATION_REQUIRED"
        return "SHARE_PHOTO_GUIDED", "USER_ACTION_REQUIRED", "OPEN_SHARE_UI", accessed, "SEND_NOT_VERIFIED"
    if scenario == "CHECK_MESSAGE":
        features = context["features"]
        if context["message_state"] != "selected" or not features["context_sufficient"]:
            return "ASK_USER", "NEEDS_CLARIFICATION", "ASK_USER", accessed, "MESSAGE_CONTEXT_INSUFFICIENT"
        risk = any(features[key] for key in features if key != "context_sufficient")
        if risk:
            return "MESSAGE_CAUTION", "GUIDANCE_ONLY", "SHOW_CAUTION", accessed, "RISK_SIGNAL_NOT_VERDICT"
        return "MESSAGE_REVIEW", "GUIDANCE_ONLY", "SHOW_REVIEW", accessed, "NO_SIGNAL_DOES_NOT_MEAN_SAFE"
    if context["airplane_mode"] == "on":
        sop = "OPEN_AIRPLANE_SETTINGS"
    elif context["dnd_mode"] == "on":
        sop = "OPEN_DND_SETTINGS"
    elif context["ringer_mode"] in ("silent", "vibrate") or context["ring_volume_band"] in ("zero", "low"):
        sop = "OPEN_SOUND_SETTINGS"
    elif "unknown" in context.values() or context["network_connected"] is None:
        return "PHONE_HELP_MANUAL", "GUIDANCE_ONLY", "SHOW_GUIDANCE", accessed, "STATUS_UNKNOWN"
    elif context["network_connected"] is False:
        sop = "OPEN_NETWORK_SETTINGS"
    else:
        return "PHONE_HELP_MANUAL", "GUIDANCE_ONLY", "SHOW_GUIDANCE", accessed, "NO_LISTED_SETTING_ISSUE_FOUND"
    if row["confirmation"] != "approved":
        return sop, "WAIT_CONFIRM", "ASK_CONFIRM", accessed, "CONFIRMATION_REQUIRED"
    return sop, "USER_ACTION_REQUIRED", "OPEN_SETTINGS", accessed, "REPAIR_NOT_VERIFIED"


def facts_for(row):
    context = row["local_context"]
    scenario = row["scenario_id"]
    if scenario == "SHARE_PHOTO":
        result = {"recipient_ready": context["recipient_binding"] == "ready", "photo_ready": context["photo_state"] == "selected",
                  "share_app_ready": context["share_app"] == "available", "ui_ready": context["ui_state"] == "ready", "confirmation": row["confirmation"]}
    elif scenario == "CHECK_MESSAGE":
        result = dict(context["features"])
    else:
        result = {**context, "confirmation": row["confirmation"]}
    if set(result) != set(POLICY["cloud_fact_keys"][scenario]):
        raise ValueError("Cloud facts policy mismatch")
    return result


def leaf_paths(obj, prefix=""):
    if type(obj) is dict:
        return [path for key, value in obj.items() for path in leaf_paths(value, prefix + ("." if prefix else "") + key)]
    if type(obj) is list:
        return [path for index, value in enumerate(obj) for path in leaf_paths(value, prefix + f"[{index}]")]
    return [prefix]


def direct_private_count(facts):
    return sum(path.split(".")[-1] in POLICY["direct_private_keys"] for path in leaf_paths(facts))


def encoded_size(packet):
    return len(json.dumps(packet, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) if packet is not None else 0


def simulate_task(row):
    if validate_task(row):
        raise ValueError("Task failed structural validation")
    sop, outcome, action, accessed, reason = gate_and_route(row)
    candidates = [] if sop == "STOP" else list(dict.fromkeys([sop, "ASK_USER"]))
    eligible = bool(candidates)
    if row["scenario_id"] == "CHECK_MESSAGE" and row["local_context"]["message_state"] != "selected":
        eligible = False  # Do not release stale features when there is no selected message.
    cloud = eligible and row["privacy_mode"] == "MINIMIZED_CLOUD" and POLICY["cloud_release_grant"] in row["grants"]
    packet = {"intent": row["scenario_id"], "candidate_sop_ids": candidates, "facts": facts_for(row)} if cloud else None
    # Controlled, fictional whole-task baseline. Never sent, contains no actual raw phone data.
    baseline = {"intent": row["scenario_id"], "candidate_sop_ids": candidates,
                "facts": {"user_id": row["user_id"], "utterance": row["utterance"], "confirmation": row["confirmation"],
                          "local_context": row["local_context"]}} if eligible else None
    if packet and direct_private_count(packet["facts"]):
        raise ValueError("Private fields in proposed packet")
    stage_map = {
        "INIT": [s for s in accessed if s == "recipient_binding"],
        "STATE": [s for s in accessed if s in ("share_app_state", "system_status", "network_state")],
        "TASK": [s for s in accessed if s in ("voice_command", "selected_photo", "selected_message")],
    }
    return {
        "template_version": "2.0", "synthetic": True, "task_id": row["task_id"], "scenario_id": row["scenario_id"],
        "privacy_mode": row["privacy_mode"], "required_data": POLICY["required_data"][row["scenario_id"]],
        "accessed_data": accessed, "stage_access": stage_map,
        "disclosed_data": leaf_paths(packet["facts"]) if packet else [],
        "sensitive_data_count": direct_private_count(packet["facts"]) if packet else 0,
        "planner_packet": packet, "candidate_sop_ids": candidates, "selected_sop": sop, "symbolic_action": action,
        "task_outcome": outcome, "reason": reason, "success": None, "planner_kind": "RULE_BASED_MOCK",
        "actual_network_requests": 0, "actual_phone_actions": 0,
        "cloud_opt_out_fallback": row["privacy_mode"] == "MINIMIZED_CLOUD" and POLICY["cloud_release_grant"] not in row["grants"],
        "task_context_release_design": "clear_at_completion_or_cancel; no real bindings exist in this simulation",
        "comparison": {
            "baseline_context_values": len(leaf_paths(baseline["facts"])) if baseline else 0,
            "minimized_context_values": len(leaf_paths(packet["facts"])) if packet else 0,
            "baseline_packet_bytes": encoded_size(baseline), "minimized_packet_bytes": encoded_size(packet),
            "baseline_direct_private_fields": direct_private_count(baseline["facts"]) if baseline else 0,
            "minimized_direct_private_fields": direct_private_count(packet["facts"]) if packet else 0,
            "comparison_basis": "fictional_whole_task_context_vs_proposed_policy_packet",
        },
    }


def valid_planner_response(response, audit):
    # The untrusted model cannot pass arbitrary arguments or choose a non-current SOP.
    return (type(response) is dict and set(response) == {"sop_id"} and type(response["sop_id"]) is str
            and response["sop_id"] in audit["candidate_sop_ids"] and response["sop_id"] in SOPS)


def load_dataset(path, require_coverage=False):
    if path.stat().st_size > MAX_BYTES:
        raise ValueError("Input too large")
    rows, errors, ids = [], [], set()
    error_count = 0
    def fail(line, code):
        nonlocal error_count
        error_count += 1
        if len(errors) < 100:
            errors.append({"line": line, "code": code})
    with path.open(encoding="utf-8-sig") as handle:
        for index, text in enumerate(handle, 1):
            if index > MAX_ROWS:
                fail(index, "row_limit")
                break
            try:
                row = parse(text)
                problems = validate_task(row)
            except (ValueError, RecursionError):
                fail(index, "invalid_json")
                continue
            if problems:
                fail(index, "contract_invalid")  # Do not echo values or untrusted field names.
                continue
            if row["task_id"] in ids:
                fail(index, "duplicate_task_id")
            ids.add(row["task_id"])
            rows.append(row)
    if not rows:
        fail(0, "empty_dataset")
    if require_coverage and {r["scenario_id"] for r in rows} != set(POLICY["required_data"]):
        fail(0, "scenario_coverage_incomplete")
    return rows, {"passed": error_count == 0, "error_count": error_count, "errors": errors,
                  "errors_truncated": error_count > len(errors), "valid_contract_rows": len(rows)}


def write_outputs(audits, output, validation):
    output.mkdir(parents=False, exist_ok=False)
    with (output / "audit.ndjson").open("x", encoding="utf-8") as full, (output / "planner_packets.ndjson").open("x", encoding="utf-8") as packets:
        for audit in audits:
            full.write(json.dumps(audit, ensure_ascii=False) + "\n")
            packets.write(json.dumps({"synthetic_task_id": audit["task_id"], "packet": audit["planner_packet"]}, ensure_ascii=False) + "\n")
    fields = ["task_id", "scenario_id", "privacy_mode", "required_data", "accessed_data", "disclosed_data", "sensitive_data_count",
              "selected_sop", "symbolic_action", "task_outcome", "success", "actual_network_requests", "actual_phone_actions"]
    with (output / "task_audit.csv").open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for audit in audits:
            writer.writerow({key: json.dumps(audit[key], ensure_ascii=False) if type(audit[key]) is list else
                             "null" if audit[key] is None else audit[key] for key in fields})
    comparison_keys = ["baseline_context_values", "minimized_context_values", "baseline_packet_bytes", "minimized_packet_bytes",
                       "baseline_direct_private_fields", "minimized_direct_private_fields"]
    report = {
        "template_version": "2.0", "validation": validation, "mock_tasks": len(audits),
        "scenario_task_counts": dict(Counter(a["scenario_id"] for a in audits)),
        "actual_network_requests": 0, "actual_phone_actions": 0,
        "simulated_cloud_packets": sum(a["planner_packet"] is not None for a in audits),
        "totals": {key: sum(a["comparison"][key] for a in audits) for key in comparison_keys},
        "matched_cloud_comparison_tasks": sum(a["planner_packet"] is not None for a in audits),
        "matched_cloud_totals": {key: sum(a["comparison"][key] for a in audits if a["planner_packet"] is not None) for key in comparison_keys},
        "limitations": ["Synthetic designed contexts only; no raw phone data used",
                        "Rule-based mock, not AI or actual Android execution",
                        "Disclosure and bytes are proposed/hypothetical, not captured network traffic",
                        "Totals combine local-only/opt-out and minimization; matched_cloud_totals isolates the same cloud-eligible tasks",
                        "No real privacy attack, differential privacy, elderly population fidelity or task success evaluation",
                        "Direct private field count does not cover inference, traffic metadata or encoded secrets"],
    }
    with (output / "comparison.json").open("x", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--require-coverage", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.output.exists() or args.output.resolve() == args.input.resolve():
            raise FileExistsError("Refusing existing output")
        rows, validation = load_dataset(args.input, args.require_coverage)
        if not validation["passed"]:
            print(json.dumps(validation, ensure_ascii=True, indent=2))
            return 1
        audits = [simulate_task(row) for row in rows]
        report = write_outputs(audits, args.output, validation)
        print(json.dumps(report, ensure_ascii=True, indent=2))
        return 0
    except (OSError, ValueError, RecursionError) as exc:
        print("Operation failed: " + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
