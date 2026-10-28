"""Validate fictional events against the supplied contract. No raw-data/API access."""

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

BASE = Path(__file__).resolve().parent
MAX_BYTES = 20 * 1024 * 1024
MAX_ROWS = 100000
MAX_ERRORS = 100
CSV_FIELDS = [
    "template_version", "synthetic", "user_id", "event_id", "scenario_id",
    "day_index", "offset_sec", "event_type", "app_category", "duration_sec",
    "notification_kind", "message_length_band", "content_redacted", "battery_band",
    "charging", "network_class", "internet_available",
]


def strict_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def no_constant(_value):
    raise ValueError("non_finite_json_number")


def loads(text):
    return json.loads(text, object_pairs_hook=strict_pairs, parse_constant=no_constant)


def same_json_value(a, b):
    # In Python, True == 1; JSON booleans must not match numeric const/enum values.
    return type(a) is type(b) and a == b


def has_type(value, kind):
    return {
        "object": lambda: type(value) is dict,
        "string": lambda: type(value) is str,
        "integer": lambda: type(value) is int,
        "boolean": lambda: type(value) is bool,
        "null": lambda: value is None,
    }[kind]()


def schema_errors(value, rule, root, path="$", depth=0):
    """Supports only the keywords used by the supplied version 1.0 schema."""
    if depth > 30:
        return [(path, "schema_depth")]
    if "$ref" in rule:
        ref = rule["$ref"]
        if not ref.startswith("#/"):
            raise ValueError("Only local schema references are allowed")
        target = root
        for part in ref[2:].split("/"):
            target = target[part.replace("~1", "/").replace("~0", "~")]
        return schema_errors(value, target, root, path, depth + 1)
    errors = []
    if "const" in rule and not same_json_value(value, rule["const"]):
        errors.append((path, "const"))
    if "enum" in rule and not any(same_json_value(value, v) for v in rule["enum"]):
        errors.append((path, "enum"))
    kinds = rule.get("type")
    if kinds is not None:
        kinds = [kinds] if isinstance(kinds, str) else kinds
        if not any(has_type(value, kind) for kind in kinds):
            return errors + [(path, "type")]
    if type(value) is int:
        if "minimum" in rule and value < rule["minimum"]:
            errors.append((path, "minimum"))
        if "maximum" in rule and value > rule["maximum"]:
            errors.append((path, "maximum"))
    if type(value) is str and "pattern" in rule:
        if not re.fullmatch(rule["pattern"], value):
            errors.append((path, "pattern"))
    if type(value) is dict:
        props = rule.get("properties", {})
        if any(key not in value for key in rule.get("required", [])):
            errors.append((path, "required"))
        if rule.get("additionalProperties") is False and set(value) - set(props):
            # Never echo unexpected field names or supplied values into reports.
            errors.append((path, "additionalProperties"))
        for key, child in props.items():
            if key in value:
                errors.extend(schema_errors(value[key], child, root, path + "." + key, depth + 1))
    for child in rule.get("allOf", []):
        errors.extend(schema_errors(value, child, root, path, depth + 1))
    if "if" in rule and not schema_errors(value, rule["if"], root, path, depth + 1):
        errors.extend(schema_errors(value, rule.get("then", {}), root, path, depth + 1))
    return errors


def validate_lines(lines, require_coverage=False, expected_users=None, expected_days=None, default_batch=False):
    schema = loads((BASE / "schema.json").read_text(encoding="utf-8"))
    config = loads((BASE / "scenarios.json").read_text(encoding="utf-8"))
    rules = config["definitions"]
    errors = []
    error_count = 0
    valid_rows = []
    row_count = 0
    parsed_count = 0
    schema_pass = 0

    def fail(code, line=None, field=None):
        nonlocal error_count
        error_count += 1
        if len(errors) < MAX_ERRORS:
            item = {"code": code}
            if line is not None:
                item["line"] = line
            if field is not None:
                item["field"] = field
            errors.append(item)

    for line_number, text in enumerate(lines, 1):
        row_count += 1
        if row_count > MAX_ROWS:
            fail("row_limit", line_number)
            break
        if not text.strip():
            fail("blank_line", line_number)
            continue
        try:
            row = loads(text)
        except (ValueError, RecursionError):
            fail("invalid_json_or_duplicate_key", line_number)
            continue
        parsed_count += 1
        row_errors = schema_errors(row, schema, schema)
        if row_errors:
            for path, code in row_errors:
                fail("schema_" + code, line_number, path)
            continue
        schema_pass += 1
        valid_rows.append((line_number, row))
    if not row_count:
        fail("empty_dataset")

    event_ids = set()
    identities = {}
    previous_time = {}
    groups = defaultdict(list)
    for line_number, row in valid_rows:
        uid = row["user_id"]
        day = row["day_index"]
        scenario = row["scenario_id"]
        timestamp = (day, row["offset_sec"])
        if row["event_id"] in event_ids:
            fail("duplicate_event_id", line_number)
        event_ids.add(row["event_id"])
        if uid in identities and identities[uid] != scenario:
            fail("user_scenario_changed", line_number)
        identities.setdefault(uid, scenario)
        if uid in previous_time and timestamp < previous_time[uid]:
            fail("user_timeline_not_sorted", line_number)
        previous_time[uid] = timestamp
        groups[(uid, day)].append((line_number, row))
        kind = row["event_type"]
        payload = row["payload"]
        if kind == "app_usage" and row["offset_sec"] + payload["duration_sec"] > 86400:
            fail("usage_crosses_day", line_number)
        if kind == "notification" and payload["content_redacted"] and payload["message_length_band"] != "unknown":
            fail("redacted_length_not_unknown", line_number)
        if kind == "device_state":
            network, internet = payload["network_class"], payload["internet_available"]
            if network == "offline" and internet is not False:
                fail("offline_internet_inconsistent", line_number)
            if network == "unknown" and internet is not None:
                fail("unknown_network_requires_null", line_number)

    for (uid, _day), group in groups.items():
        scenario = identities[uid]
        spec = rules[scenario]
        counts = Counter(row["event_type"] for _, row in group)
        first_line = group[0][0]
        for kind in ("app_usage", "notification", "device_state"):
            low, high = spec[kind + "_count"]
            if not low <= counts[kind] <= high:
                fail("scenario_" + kind + "_count", first_line)
        usages = sorted(
            [(r["offset_sec"], r["offset_sec"] + r["payload"]["duration_sec"], n, r["payload"]["duration_sec"])
             for n, r in group if r["event_type"] == "app_usage"]
        )
        last_end = -1
        duration_sum = 0
        low, high = spec["usage_duration_sec"]
        for start, end, line_number, duration in usages:
            if start < last_end:
                fail("usage_intervals_overlap", line_number)
            last_end = max(last_end, end)
            duration_sum += duration
            if not low <= duration <= high:
                fail("scenario_usage_duration", line_number)
        if duration_sum > spec["max_daily_usage_sec"]:
            fail("scenario_total_usage", first_line)
        states = sorted(r["offset_sec"] for _, r in group if r["event_type"] == "device_state")
        if states != config["conventions"]["device_state_offsets_sec"]:
            fail("device_state_offsets", first_line)
        window = spec["notification_window_sec"]
        if window is not None:
            times = sorted(r["offset_sec"] for _, r in group if r["event_type"] == "notification")
            size = window["min_events"]
            if not any(times[i + size - 1] - times[i] <= window["span_sec"] for i in range(len(times) - size + 1)):
                fail("notification_burst_window_missing", first_line)

    if require_coverage and set(identities.values()) != set(rules):
        fail("scenario_coverage_incomplete")
    if expected_users is not None and len(identities) != expected_users:
        fail("expected_user_count")
    if expected_days is not None:
        for uid in identities:
            days = {day for user, day in groups if user == uid}
            if days != set(range(expected_days)):
                fail("expected_days_or_day_indices")

    if default_batch:
        batch = config["default_batch"]
        if identities != batch["assignment"]:
            fail("default_batch_user_assignment")
        for uid in identities:
            days = {day for user, day in groups if user == uid}
            if days != set(range(batch["days_per_user"])):
                fail("default_batch_days")

    report = {
        "template_version": "1.0",
        "passed": error_count == 0,
        "scope": "structure_and_designed_scenarios_only",
        "privacy_evaluation_performed": False,
        "population_fidelity_evaluation_performed": False,
        "records_seen": row_count,
        "json_parsed_records": parsed_count,
        "schema_pass_records": schema_pass,
        "schema_pass_rate": schema_pass / row_count if row_count else 0,
        "synthetic_users": len(identities),
        "synthetic_user_days": len(groups),
        "scenario_user_counts": dict(sorted(Counter(identities.values()).items())),
        "error_count": error_count,
        "errors": errors,
        "errors_truncated": error_count > len(errors),
        "limitations": [
            "Not a general-purpose JSON Schema engine; implements supplied schema subset",
            "Whitelist cannot detect private information encoded into valid numeric values",
            "No reidentification/membership inference attack or differential privacy proof performed",
            "Designed scenarios are not estimates of a real elderly population",
        ],
    }
    return report, [row for _, row in valid_rows] if report["passed"] else []


def export_csv(rows, target):
    with Path(target).open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for row in rows:
            flattened = {key: value for key, value in row.items() if key != "payload"}
            flattened.update(row["payload"])
            # Unused columns are empty; explicit null is retained as 'null'.
            converted = {key: "null" if value is None else str(value).lower() if type(value) is bool else value
                         for key, value in flattened.items()}
            writer.writerow(converted)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--csv", type=Path)
    parser.add_argument("--require-coverage", action="store_true")
    parser.add_argument("--expected-users", type=int)
    parser.add_argument("--expected-days", type=int)
    parser.add_argument("--default-batch", action="store_true", help="Require exact user/scenario/day assignment in scenarios.json")
    args = parser.parse_args(argv)
    try:
        if args.expected_users is not None and args.expected_users <= 0:
            raise ValueError("expected-users must be positive")
        if args.expected_days is not None and not 1 <= args.expected_days <= 365:
            raise ValueError("expected-days must be 1..365")
        outputs = [p.resolve() for p in (args.report, args.csv) if p is not None]
        if len(outputs) != len(set(outputs)) or args.input.resolve() in outputs:
            raise ValueError("Input and output paths must be distinct")
        if any(p.exists() for p in outputs):
            raise FileExistsError("Refusing to overwrite an existing output")
        if any(not p.parent.is_dir() for p in outputs):
            raise FileNotFoundError("Output parent directory must already exist")
        if args.input.stat().st_size > MAX_BYTES:
            raise ValueError("Input exceeds 20 MiB safety limit")
        with args.input.open(encoding="utf-8-sig") as handle:
            report, rows = validate_lines(handle, args.require_coverage, args.expected_users, args.expected_days, args.default_batch)
        if args.report:
            with args.report.open("x", encoding="utf-8") as handle:
                json.dump(report, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
        if report["passed"] and args.csv:
            export_csv(rows, args.csv)
        print(json.dumps(report, ensure_ascii=True, indent=2))
        return 0 if report["passed"] else 1
    except (OSError, ValueError, RecursionError) as exc:
        # Exception text can contain private paths/data; report only the category.
        print("Operation failed: " + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
