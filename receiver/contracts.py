"""Wire-contract validation shared by the HTTP and storage layers."""

from __future__ import annotations

import json
import re
from typing import Any

API_VERSION = "1"
DEFAULT_MAX_BODY_BYTES = 25 * 1024 * 1024
DEFAULT_MAX_LINE_BYTES = 1024 * 1024
TRANSFER_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SESSION_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SHA256_PATTERN = re.compile(r"^[A-Fa-f0-9]{64}$")
TRANSMISSION_MODES = frozenset({"manual", "baseline", "periodic", "immediate"})
REQUIRED_RECORD_FIELDS = (
    "schemaVersion",
    "ts",
    "sessionId",
    "source",
    "sensitivity",
    "payload",
)


class ReceiverError(Exception):
    """An expected error returned as a stable JSON API response."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        status: int = 400,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.details = details or {}

    def as_dict(self) -> dict[str, Any]:
        error: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.details:
            error["details"] = self.details
        return {"error": error}


def validate_transfer_id(value: str) -> str:
    if not TRANSFER_ID_PATTERN.fullmatch(value):
        raise ReceiverError(
            "INVALID_TRANSFER_ID",
            "X-Transfer-Id must be 1-128 ASCII letters, digits, dots, underscores or hyphens.",
        )
    return value


def validate_sha256(value: str) -> str:
    if not SHA256_PATTERN.fullmatch(value):
        raise ReceiverError(
            "INVALID_PAYLOAD_SHA256",
            "X-Payload-Sha256 must contain exactly 64 hexadecimal characters.",
        )
    return value.lower()


def validate_record_count(value: str) -> int:
    try:
        result = int(value)
    except ValueError as exc:
        raise ReceiverError(
            "INVALID_RECORD_COUNT",
            "X-Record-Count must be a positive integer.",
        ) from exc
    if result <= 0 or str(result) != value.strip():
        raise ReceiverError("INVALID_RECORD_COUNT", "X-Record-Count must be a positive integer.")
    return result


def validate_transmission_mode(value: str | None) -> str:
    mode = (value or "manual").strip().lower()
    if mode not in TRANSMISSION_MODES:
        raise ReceiverError(
            "INVALID_TRANSMISSION_MODE",
            "X-Transmission-Mode must be manual, baseline, periodic or immediate.",
        )
    return mode


def validate_client_version(value: str | None) -> str | None:
    if value is None:
        return None
    version = value.strip()
    if not version or len(version) > 64 or any(ord(char) < 32 or ord(char) > 126 for char in version):
        raise ReceiverError(
            "INVALID_CLIENT_VERSION",
            "X-Client-Version must contain 1-64 printable ASCII characters.",
        )
    return version


def validate_client_sent_at(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        timestamp = int(value)
    except ValueError as exc:
        raise ReceiverError(
            "INVALID_CLIENT_SENT_AT",
            "X-Client-Sent-At must be a positive Unix epoch millisecond value.",
        ) from exc
    if timestamp <= 0 or str(timestamp) != value.strip():
        raise ReceiverError(
            "INVALID_CLIENT_SENT_AT",
            "X-Client-Sent-At must be a positive Unix epoch millisecond value.",
        )
    return timestamp


def validate_collection_session_id(value: str | None, *, required: bool = False) -> str | None:
    if value is None or not value.strip():
        if required:
            raise ReceiverError(
                "COLLECTION_SESSION_REQUIRED",
                "X-Collection-Session-Id is required for live transmissions.",
            )
        return None
    session_id = value.strip()
    if not SESSION_ID_PATTERN.fullmatch(session_id):
        raise ReceiverError(
            "INVALID_COLLECTION_SESSION_ID",
            "X-Collection-Session-Id contains unsupported characters or is too long.",
        )
    return session_id


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate key: {key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise ValueError(f"non-finite number: {value}")


def parse_and_validate_record(raw_line: bytes, line_number: int) -> dict[str, Any]:
    """Decode one NDJSON line and validate the Collector record envelope."""

    try:
        text = raw_line.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ReceiverError(
            "INVALID_UTF8",
            f"Line {line_number} is not valid UTF-8.",
            details={"line": line_number, "column": exc.start + 1},
        ) from exc
    try:
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except (json.JSONDecodeError, ValueError) as exc:
        details: dict[str, Any] = {"line": line_number}
        if getattr(exc, "colno", None) is not None:
            details["column"] = exc.colno
        raise ReceiverError(
            "INVALID_NDJSON",
            f"Line {line_number} is not a valid JSON object.",
            details=details,
        ) from exc
    if not isinstance(value, dict):
        raise ReceiverError(
            "INVALID_RECORD",
            f"Line {line_number} must contain a JSON object.",
            details={"line": line_number},
        )
    missing = [field for field in REQUIRED_RECORD_FIELDS if field not in value]
    if missing:
        raise ReceiverError(
            "MISSING_RECORD_FIELDS",
            f"Line {line_number} is missing required record fields.",
            details={"line": line_number, "fields": missing},
        )

    invalid_fields: list[str] = []
    schema_version = value["schemaVersion"]
    timestamp = value["ts"]
    if isinstance(schema_version, bool) or not isinstance(schema_version, int) or schema_version < 1:
        invalid_fields.append("schemaVersion")
    if isinstance(timestamp, bool) or not isinstance(timestamp, int) or timestamp < 0:
        invalid_fields.append("ts")
    if not isinstance(value["sessionId"], str) or not value["sessionId"] or len(value["sessionId"]) > 128:
        invalid_fields.append("sessionId")
    if not isinstance(value["source"], str) or not value["source"] or len(value["source"]) > 128:
        invalid_fields.append("source")
    if value["sensitivity"] not in {"P0", "P1", "P2", "P3"}:
        invalid_fields.append("sensitivity")
    if not isinstance(value["payload"], dict):
        invalid_fields.append("payload")
    if invalid_fields:
        raise ReceiverError(
            "INVALID_RECORD_FIELDS",
            f"Line {line_number} has invalid record field types or values.",
            details={"line": line_number, "fields": invalid_fields},
        )
    return value
