"""Synthetic environment -> minimized task demonstration. No phone or network access."""
import argparse
import csv
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path
from core import task_engine as v2

BASE = Path(__file__).resolve().parent
TEXTS = (
    "我是虛構客服，請立即轉帳並提供驗證碼，點選 https://verify.example.invalid/claim",
    "虛構家人提醒：明天一起吃飯。",
    "請提供驗證碼，我是虛構警察，馬上處理。",
    "虛構商店通知：訂單已出貨。",
)


def require(ok, code="contract_invalid"):
    if not ok:
        raise ValueError(code)  # Never echo submitted values or field names.


def enum(*values):
    return lambda x: any(type(x) is type(y) and x == y for y in values)


def number(low, high, integer=True, nullable=False):
    return lambda x: (nullable and x is None) or (
        type(x) in ((int,) if integer else (int, float)) and
        math.isfinite(x) and low <= x <= high)


def pattern(regex, nullable=False):
    return lambda x: (nullable and x is None) or (type(x) is str and re.fullmatch(regex, x) is not None)


ID = pattern(r"SYN_[A-Z0-9_]{1,64}")
REF = pattern(r"SYN_[A-Z0-9_]{1,64}", True)
BOOL = enum(True, False)
TIME = number(0, 86400)
ON = enum("on", "off", "unknown")


def check(value, spec):
    if callable(spec):
        require(spec(value))
    elif type(spec) is dict:
        require(type(value) is dict and set(value) == set(spec))
        for key, rule in spec.items():
            check(value[key], rule)
    else:
        require(type(value) is list and len(value) <= 1000)
        for item in value:
            check(item, spec[0])


ENV_SPEC = {
    "environment_id": ID, "captured_offset_sec": TIME,
    "initialization_data": {
        "user_profile": {"user_id": pattern(r"SYN_U[0-9]{3,6}"), "age_band": enum("65-74", "75-84", "85+", "unknown"), "locale": enum("zh-TW"), "font_scale": number(1, 2, False)},
        "device": {"device_id": ID, "manufacturer": ID, "model": ID, "sdk_int": enum(34, 35, 36), "android_release": enum("14", "15", "16"), "screen_width_px": number(320, 5000), "screen_height_px": number(320, 5000), "ram_gb": number(1, 64), "storage_gb": number(8, 2048)},
        "contacts": [{"contact_id": ID, "display_name": ID, "relationship": enum("daughter", "family", "friend", "other"), "phone_ref": REF, "line_ref": REF}],
        "installed_apps": [{"app_id": ID, "name": enum("LINE", "GALLERY", "SETTINGS", "OTHER"), "package": pattern(r"jp\.naver\.line\.android|com\.example\.synthetic\.[a-z]+"), "installed": BOOL}],
    },
    "continuous_state_data": {
        "permissions": {k: enum("granted", "denied", "unknown") for k in ("contacts", "media_images", "notifications", "accessibility", "microphone", "location", "calendar")},
        "battery": {"capacity_percent": number(0, 100, nullable=True), "charging": enum(True, False, None)},
        "network": {"connected": enum(True, False, None), "transport": enum("WIFI", "CELLULAR", "NONE", "UNKNOWN"), "internet_validated": enum(True, False, None), "wifi_ssid_ref": REF},
        "system": {"airplane_mode": ON, "dnd_mode": ON, "ringer_mode": enum("normal", "silent", "vibrate", "unknown"), "ring_volume_current": number(0, 100, nullable=True), "ring_volume_max": number(1, 100, nullable=True)},
        "screen": {"current_app_id": REF, "guard_ready": BOOL, "node_count": number(0, 10000), "text_ref": REF},
        "photos": [{"photo_id": ID, "display_name": pattern(r"SYN_[A-Z0-9_]{1,64}\.jpg"), "media_ref": REF, "taken_offset_sec": TIME, "mime_type": enum("image/jpeg"), "size_bytes": number(1, 100000000), "width": number(1, 20000), "height": number(1, 20000), "exif_location_ref": REF, "pixels_available": enum(False)}],
        "messages": [{"message_id": ID, "sender_ref": REF, "text": enum(*TEXTS), "received_offset_sec": TIME, "context_sufficient": BOOL}],
        "notifications": [{"notification_id": ID, "app_id": ID, "message_id": ID, "post_offset_sec": TIME}],
        "calendar": [{"event_id": ID, "title_ref": REF, "start_offset_sec": TIME}],
        "location": {"location_ref": REF, "latitude": enum(0), "longitude": enum(0), "accuracy_m": number(0, 10000)},
        "clipboard": {"observability": enum("unavailable", "foreground"), "text_ref": REF},
        "call": {"state": enum("idle", "ringing", "unknown"), "caller_ref": REF},
    },
}
TASK_SPEC = {
    "task_id": pattern(r"SYN_T[0-9]{4,8}"), "environment_id": ID,
    "user_id": pattern(r"SYN_U[0-9]{3,6}"), "scenario_id": enum(*v2.POLICY["required_data"]),
    "utterance": lambda x: type(x) is str, "privacy_mode": enum("STRICT_LOCAL", "MINIMIZED_CLOUD"),
    "grants": [enum(*v2.SCHEMA["properties"]["grants"]["items"]["enum"])],
    "confirmation": enum("approved", "pending", "denied", "not_required"), "cancelled": BOOL,
    "requested_offset_sec": TIME, "parameters": lambda x: type(x) is dict,
}


def index_rows(rows, key):
    require(len({r[key] for r in rows}) == len(rows), "duplicate_id")
    return {r[key]: r for r in rows}


def message_features(message):
    text = message["text"] if message else ""
    return {"asks_transfer": "轉帳" in text, "requests_otp": "驗證碼" in text,
            "urgency": any(s in text for s in ("立即", "馬上")), "has_link": "https://" in text,
            "impersonation": any(s in text for s in ("客服", "警察")),
            "context_sufficient": bool(message and message["context_sufficient"])}


def adapt(task, environment):
    init, state = environment["initialization_data"], environment["continuous_state_data"]
    scenario, params = task["scenario_id"], task["parameters"]
    if scenario == "SHARE_PHOTO":
        candidates = [c for c in init["contacts"] if c["relationship"] == params["recipient_relation"] or
                      (params["recipient_relation"] == "family" and c["relationship"] == "daughter")]
        # An unusable binding is not treated as ready merely because a name exists.
        ready = len(candidates) == 1 and candidates[0]["line_ref"] is not None
        photo = next((p for p in state["photos"] if p["photo_id"] == params["photo_id"]), None)
        context = {"recipient_binding": "ambiguous" if len(candidates) > 1 else "ready" if ready else "missing",
                   "photo_state": "selected" if photo and photo["media_ref"] else "missing",
                   "share_app": "available" if any(a["name"] == "LINE" and a["installed"] for a in init["installed_apps"]) else "unavailable",
                   "ui_state": "ready" if state["screen"]["guard_ready"] else "unknown"}
    elif scenario == "CHECK_MESSAGE":
        message = next((m for m in state["messages"] if m["message_id"] == params["message_id"]), None)
        context = {"message_state": "selected" if message else "missing", "features": message_features(message)}
    else:
        s = state["system"]
        current, maximum = s["ring_volume_current"], s["ring_volume_max"]
        band = "unknown" if current is None or maximum is None else "zero" if current == 0 else "low" if current <= maximum * .2 else "normal"
        context = {k: s[k] for k in ("airplane_mode", "dnd_mode", "ringer_mode")}
        context.update(ring_volume_band=band, network_connected=state["network"]["connected"])
    row = {k: task[k] for k in ("task_id", "user_id", "scenario_id", "utterance", "privacy_mode", "grants", "confirmation", "cancelled")}
    row.update(template_version="2.0", synthetic=True, local_context=context)
    return row


def validate(source):
    require(type(source) is dict and set(source) == {"bundle_version", "synthetic", "provenance", "phone_environments", "task_requests"})
    require(source["bundle_version"] == "3.0" and source["synthetic"] is True and source["provenance"] == "DESIGNED_SYNTHETIC_NO_REAL_DEVICE_VALUES")
    for key in ("phone_environments", "task_requests"):
        require(type(source[key]) is list and 0 < len(source[key]) <= 10000)
    environments = source["phone_environments"]
    for env in environments:
        check(env, ENV_SPEC)
        init, state, captured = env["initialization_data"], env["continuous_state_data"], env["captured_offset_sec"]
        require(int(init["device"]["android_release"]) + 20 == init["device"]["sdk_int"], "android_version_conflict")
        index_rows(init["contacts"], "contact_id")
        apps = index_rows(init["installed_apps"], "app_id")
        require(len({a["package"] for a in apps.values()}) == len(apps), "duplicate_app_package")
        for app in apps.values():
            require((app["name"] == "LINE") == (app["package"] == "jp.naver.line.android"), "app_name_package_conflict")
        photos = index_rows(state["photos"], "photo_id")
        messages = index_rows(state["messages"], "message_id")
        index_rows(state["notifications"], "notification_id")
        index_rows(state["calendar"], "event_id")
        for photo in photos.values():
            require(photo["taken_offset_sec"] <= captured, "future_photo")
        for message in messages.values():
            require(message["received_offset_sec"] <= captured, "future_message")
        for n in state["notifications"]:
            require(n["app_id"] in apps and n["message_id"] in messages, "notification_reference_invalid")
            require(messages[n["message_id"]]["received_offset_sec"] <= n["post_offset_sec"] <= captured, "notification_time_invalid")
        require(state["screen"]["current_app_id"] is None or state["screen"]["current_app_id"] in apps, "screen_reference_invalid")
        s = state["system"]
        require(s["ring_volume_current"] is None or s["ring_volume_max"] is None or s["ring_volume_current"] <= s["ring_volume_max"], "volume_invalid")
        n = state["network"]
        require(n["connected"] is not False or (n["transport"] in ("NONE", "UNKNOWN") and n["internet_validated"] is not True), "network_conflict")
        require(n["transport"] != "NONE" or (n["connected"] is not True and n["internet_validated"] is not True), "network_conflict")
        require(state["clipboard"]["observability"] != "unavailable" or state["clipboard"]["text_ref"] is None, "clipboard_unavailable")
        require(state["call"]["state"] != "idle" or state["call"]["caller_ref"] is None, "idle_caller")
    by_id = index_rows(environments, "environment_id")
    for task in source["task_requests"]:
        check(task, TASK_SPEC)
        require(task["environment_id"] in by_id, "environment_reference_invalid")
        env = by_id[task["environment_id"]]
        require(task["user_id"] == env["initialization_data"]["user_profile"]["user_id"], "user_reference_invalid")
        require(task["requested_offset_sec"] >= env["captured_offset_sec"], "task_before_snapshot")
        params, scenario = task["parameters"], task["scenario_id"]
        if scenario == "SHARE_PHOTO":
            check(params, {"recipient_relation": enum("daughter", "family"), "photo_id": REF})
            require(params["photo_id"] is None or params["photo_id"] in {p["photo_id"] for p in env["continuous_state_data"]["photos"]}, "photo_reference_invalid")
            require("女兒" not in task["utterance"] or params["recipient_relation"] == "daughter", "recipient_command_conflict")
        elif scenario == "CHECK_MESSAGE":
            check(params, {"message_id": REF})
            require(params["message_id"] is None or params["message_id"] in {m["message_id"] for m in env["continuous_state_data"]["messages"]}, "message_reference_invalid")
        else:
            check(params, {})
        require(not v2.validate_task(adapt(task, env)), "task_contract_invalid")
    index_rows(source["task_requests"], "task_id")
    return by_id


def derive(task, env):
    row = adapt(task, env)
    audit = v2.simulate_task(row)
    needed = {"SHARE_PHOTO": ["contacts", "media_images", "accessibility"], "CHECK_MESSAGE": ["notifications"], "RESCUE_PHONE": []}[task["scenario_id"]]
    permissions_ok = all(env["continuous_state_data"]["permissions"][k] == "granted" for k in needed)
    if not permissions_ok and audit["task_outcome"] not in ("BLOCKED", "CANCELLED"):
        audit.update(planner_packet=None, candidate_sop_ids=[], selected_sop="STOP", symbolic_action="STOP", task_outcome="BLOCKED",
                     reason="SIMULATED_PLATFORM_PERMISSION_UNAVAILABLE", accessed_data=[], stage_access={"INIT": [], "STATE": [], "TASK": []}, disclosed_data=[], sensitive_data_count=0)
    audit["template_version"] = "3.0"
    audit["platform_permissions_checked"] = needed
    audit["platform_permissions_sufficient"] = permissions_ok
    audit["snapshot_age_sec"] = task["requested_offset_sec"] - env["captured_offset_sec"]
    baseline = {"phone_environment": env, "task_request": task}
    packet = audit["planner_packet"]
    audit["comparison"] = {"basis": "fictional_full_phone_and_task_vs_hypothetical_minimized_packet",
                           "original_leaf_values": len(v2.leaf_paths(baseline)), "original_bytes": v2.encoded_size(baseline),
                           "proposed_packet_leaf_values": len(v2.leaf_paths(packet)) if packet else 0, "proposed_packet_bytes": v2.encoded_size(packet),
                           "matched_cloud_task": packet is not None}
    return {"record_id": task["task_id"], "part_1_original": baseline, "part_2_derived": audit}


def flatten(obj, prefix=""):
    out = {}
    for key, value in obj.items():
        name = prefix + key
        if type(value) is dict:
            out.update(flatten(value, name + "."))
        else:
            out[name] = value
    return out


def write_csv(path, rows):
    fields = list(dict.fromkeys(k for row in rows for k in row))
    with path.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            # JSON encoding preserves booleans, null and nested arrays; no spreadsheet formulas.
            writer.writerow({k: v if type(v) is str else json.dumps(v, ensure_ascii=False, separators=(",", ":")) for k, v in row.items()})


def generate(source, output):
    environments = validate(source)  # All validation precedes all output writes.
    require(not output.exists(), "output_already_exists")
    pairs = [derive(task, environments[task["environment_id"]]) for task in source["task_requests"]]
    report = {"bundle_version": "3.0", "synthetic": True, "validation_passed": True,
              "phone_environment_count": len(environments), "task_count": len(pairs),
              "scenario_counts": dict(Counter(p["part_2_derived"]["scenario_id"] for p in pairs)),
              "actual_network_requests": 0, "actual_phone_actions": 0,
              "matched_cloud_tasks": sum(p["part_2_derived"]["planner_packet"] is not None for p in pairs),
              "matched_cloud_totals": {key: sum(p["part_2_derived"]["comparison"][key] for p in pairs if p["part_2_derived"]["planner_packet"] is not None)
                                       for key in ("original_leaf_values", "original_bytes", "proposed_packet_leaf_values", "proposed_packet_bytes")},
              "limitations": ["Designed synthetic source, not collected real-device data", "Rule-based mock, not AI or phone execution",
                              "Matched hypothetical packet sizes, not network traffic or privacy attack results", "Source repeated per task for matched comparisons; not physical dataset size",
                              "No differential privacy guarantee, real population fidelity or verified task success", "Schema whitelist cannot prove absence of all encoded secrets",
                              "Snapshot age is reported; no live state refresh or automatic stale-state safety guarantee"]}
    output.mkdir(parents=False, exist_ok=False)
    write_csv(output / "phone_environments.csv", [flatten(e) for e in source["phone_environments"]])
    write_csv(output / "task_requests.csv", [flatten(t) for t in source["task_requests"]])
    write_csv(output / "derived_tasks.csv", [flatten(p["part_2_derived"]) for p in pairs])
    for name, value in (("pairs.json", pairs), ("comparison.json", report)):
        with (output / name).open("x", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        require(args.input.stat().st_size <= v2.MAX_BYTES, "input_too_large")
        source = v2.parse(args.input.read_text(encoding="utf-8-sig"))
        report = generate(source, args.output)
        print(json.dumps(report, ensure_ascii=True, indent=2))
        return 0
    except (OSError, ValueError, RecursionError, OverflowError):
        print("Operation failed: input/output validation failed; no submitted values echoed", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
