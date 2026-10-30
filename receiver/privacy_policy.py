"""Load and validate the executable CHECK_MESSAGE privacy budget."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

POLICY_PATH = Path(__file__).resolve().parent / "policies" / "check_message_privacy_policy.json"


def _load_policy() -> dict[str, Any]:
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    expected = {
        "policy_version", "packet_schema", "client_policy_versions", "allowed_top_level_fields", "facts",
        "redacted_excerpt", "freshness", "retention", "forbidden_cloud_values",
    }
    if type(policy) is not dict or set(policy) != expected:
        raise RuntimeError("CHECK_MESSAGE privacy policy has an invalid top-level schema")
    if not policy["policy_version"] or not policy["packet_schema"]:
        raise RuntimeError("CHECK_MESSAGE privacy policy must be versioned")
    if type(policy["allowed_top_level_fields"]) is not list or len(policy["allowed_top_level_fields"]) != len(
        set(policy["allowed_top_level_fields"])
    ):
        raise RuntimeError("CHECK_MESSAGE privacy policy fields must be unique")
    required_fields = {"schema", "task_id_alias", "request_nonce", "issued_at_ms", "intent", "locale",
                       "cloud_consent", "facts", "redacted_excerpt", "allowed_sops", "client_policy_version"}
    if set(policy["allowed_top_level_fields"]) != required_fields:
        raise RuntimeError("CHECK_MESSAGE privacy policy field budget is incomplete")
    for name, spec in policy["facts"].items():
        if type(name) is not str or type(spec) is not dict or spec.get("type") not in {"boolean", "enum"}:
            raise RuntimeError("CHECK_MESSAGE privacy policy fact schema is invalid")
        if spec["type"] == "enum" and (type(spec.get("values")) is not list or not spec["values"]):
            raise RuntimeError("CHECK_MESSAGE privacy policy enum is empty")
    freshness = policy["freshness"]
    if set(freshness) != {"max_age_ms", "max_future_skew_ms", "nonce_cache_entries"} or any(
        type(value) is not int or value <= 0 for value in freshness.values()
    ):
        raise RuntimeError("CHECK_MESSAGE freshness policy is invalid")
    return policy


POLICY = _load_policy()
POLICY_SHA256 = hashlib.sha256(POLICY_PATH.read_bytes()).hexdigest()
