"""Atomic on-disk storage for validated NDJSON transmissions."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
import threading
import uuid
from collections import Counter, deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from .contracts import (
    API_VERSION,
    DEFAULT_MAX_BODY_BYTES,
    DEFAULT_MAX_LINE_BYTES,
    ReceiverError,
    parse_and_validate_record,
    validate_collection_session_id,
    validate_client_sent_at,
    validate_client_version,
    validate_transmission_mode,
    validate_transfer_id,
)

DEFAULT_CONTEXT_LIMIT_PER_SOURCE = 100
MAX_CONTEXT_LIMIT_PER_SOURCE = 1000


class ReceiverStorage:
    def __init__(
        self,
        data_dir: str | Path,
        *,
        max_body_bytes: int = DEFAULT_MAX_BODY_BYTES,
        max_line_bytes: int = DEFAULT_MAX_LINE_BYTES,
    ) -> None:
        if max_body_bytes <= 0 or max_line_bytes <= 0:
            raise ValueError("Storage limits must be positive")
        self.data_dir = Path(data_dir).resolve()
        self.incoming_dir = self.data_dir / ".incoming"
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.incoming_dir.mkdir(parents=True, exist_ok=True)
        self.max_body_bytes = max_body_bytes
        self.max_line_bytes = max_line_bytes
        self._commit_lock = threading.Lock()

    @staticmethod
    def new_transfer_id() -> str:
        return "tr_" + uuid.uuid4().hex

    def _data_path(self, transfer_id: str) -> Path:
        return self.data_dir / f"{transfer_id}.ndjson"

    def _metadata_path(self, transfer_id: str) -> Path:
        return self.data_dir / f"{transfer_id}.metadata.json"

    def _load_metadata_path(self, path: Path) -> dict[str, Any]:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ReceiverError(
                "STORAGE_CORRUPT",
                "Stored transmission metadata cannot be read.",
                status=500,
            ) from exc
        if not isinstance(value, dict):
            raise ReceiverError("STORAGE_CORRUPT", "Stored metadata is invalid.", status=500)
        return value

    def get_metadata(self, transfer_id: str) -> dict[str, Any]:
        validate_transfer_id(transfer_id)
        path = self._metadata_path(transfer_id)
        if not path.is_file():
            raise ReceiverError("TRANSMISSION_NOT_FOUND", "Transmission was not found.", status=404)
        return self._load_metadata_path(path)

    def list_metadata(self) -> list[dict[str, Any]]:
        items = [self._load_metadata_path(path) for path in self.data_dir.glob("*.metadata.json")]
        return sorted(items, key=lambda item: str(item.get("receivedAt", "")), reverse=True)

    def receive(
        self,
        transfer_id: str | None,
        chunks: Iterable[bytes],
        *,
        expected_record_count: int | None = None,
        expected_sha256: str | None = None,
        transmission_mode: str = "manual",
        client_version: str | None = None,
        client_sent_at: int | None = None,
        collection_session_id: str | None = None,
    ) -> tuple[dict[str, Any], bool]:
        """Validate, stage and commit a stream. Returns metadata and duplicate flag."""

        identifier = validate_transfer_id(transfer_id) if transfer_id is not None else self.new_transfer_id()
        normalized_mode = validate_transmission_mode(transmission_mode)
        normalized_client_version = validate_client_version(client_version)
        normalized_client_sent_at = validate_client_sent_at(
            str(client_sent_at) if client_sent_at is not None else None
        )
        normalized_collection_session_id = validate_collection_session_id(
            collection_session_id,
            required=normalized_mode != "manual",
        )
        temp_path: Path | None = None
        try:
            fd, name = tempfile.mkstemp(prefix=f"{identifier}.", suffix=".part", dir=self.incoming_dir)
            os.close(fd)
            temp_path = Path(name)
            digest = hashlib.sha256()
            total_bytes = 0
            record_count = 0
            line_number = 0
            pending = bytearray()
            sessions: set[str] = set()
            sources: Counter[str] = Counter()
            sensitivities: Counter[str] = Counter()

            def accept_line(raw_line: bytes) -> None:
                nonlocal line_number, record_count
                line_number += 1
                if raw_line.endswith(b"\r"):
                    raw_line = raw_line[:-1]
                if not raw_line:
                    raise ReceiverError(
                        "EMPTY_NDJSON_LINE",
                        f"Line {line_number} is empty.",
                        details={"line": line_number},
                    )
                if len(raw_line) > self.max_line_bytes:
                    raise ReceiverError(
                        "LINE_TOO_LARGE",
                        f"Line {line_number} exceeds the {self.max_line_bytes}-byte limit.",
                        status=413,
                        details={"line": line_number, "maxBytes": self.max_line_bytes},
                    )
                record = parse_and_validate_record(raw_line, line_number)
                record_count += 1
                sessions.add(record["sessionId"])
                sources[record["source"]] += 1
                sensitivities[record["sensitivity"]] += 1

            with temp_path.open("wb") as output:
                for chunk in chunks:
                    if not isinstance(chunk, (bytes, bytearray)):
                        raise ReceiverError("INVALID_BODY", "Request body reader produced non-byte data.")
                    if not chunk:
                        continue
                    total_bytes += len(chunk)
                    if total_bytes > self.max_body_bytes:
                        raise ReceiverError(
                            "BODY_TOO_LARGE",
                            f"Transmission exceeds the {self.max_body_bytes}-byte limit.",
                            status=413,
                            details={"maxBytes": self.max_body_bytes},
                        )
                    digest.update(chunk)
                    output.write(chunk)
                    pending.extend(chunk)
                    while True:
                        newline = pending.find(b"\n")
                        if newline < 0:
                            if len(pending) > self.max_line_bytes:
                                raise ReceiverError(
                                    "LINE_TOO_LARGE",
                                    f"Line {line_number + 1} exceeds the {self.max_line_bytes}-byte limit.",
                                    status=413,
                                    details={"line": line_number + 1, "maxBytes": self.max_line_bytes},
                                )
                            break
                        raw_line = bytes(pending[:newline])
                        del pending[: newline + 1]
                        accept_line(raw_line)
                if pending:
                    accept_line(bytes(pending))
                output.flush()
                os.fsync(output.fileno())

            if record_count == 0:
                raise ReceiverError("EMPTY_TRANSMISSION", "Transmission contains no NDJSON records.")
            actual_sha256 = digest.hexdigest()
            if expected_record_count is not None and record_count != expected_record_count:
                raise ReceiverError(
                    "RECORD_COUNT_MISMATCH",
                    "X-Record-Count does not match the received NDJSON record count.",
                    status=422,
                    details={"expected": expected_record_count, "actual": record_count},
                )
            if expected_sha256 is not None and actual_sha256 != expected_sha256:
                raise ReceiverError(
                    "PAYLOAD_SHA256_MISMATCH",
                    "X-Payload-Sha256 does not match the received request bytes.",
                    status=422,
                    details={"expected": expected_sha256, "actual": actual_sha256},
                )
            if normalized_collection_session_id is not None and sessions != {normalized_collection_session_id}:
                raise ReceiverError(
                    "COLLECTION_SESSION_MISMATCH",
                    "X-Collection-Session-Id does not match every record sessionId.",
                    status=422,
                    details={
                        "header": normalized_collection_session_id,
                        "recordSessionIds": sorted(sessions),
                    },
                )

            metadata: dict[str, Any] = {
                "apiVersion": API_VERSION,
                "transferId": identifier,
                "recordCount": record_count,
                "receivedRecords": record_count,
                "bytes": total_bytes,
                "sha256": actual_sha256,
                "receivedAt": datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"),
                "transmissionMode": normalized_mode,
                "clientVersion": normalized_client_version,
                "clientSentAt": normalized_client_sent_at,
                "collectionSessionId": normalized_collection_session_id,
                "sessionCount": len(sessions),
                "sessionIds": sorted(sessions),
                "sources": dict(sorted(sources.items())),
                "sensitivities": dict(sorted(sensitivities.items())),
            }

            with self._commit_lock:
                metadata_path = self._metadata_path(identifier)
                data_path = self._data_path(identifier)
                if metadata_path.exists() or data_path.exists():
                    if not metadata_path.is_file() or not data_path.is_file():
                        raise ReceiverError(
                            "TRANSFER_ID_CONFLICT",
                            "X-Transfer-Id exists in an incomplete or conflicting state.",
                            status=409,
                        )
                    existing = self._load_metadata_path(metadata_path)
                    existing_mode = existing.get("transmissionMode", "manual")
                    existing_session = existing.get("collectionSessionId")
                    if (
                        existing.get("sha256") == metadata["sha256"]
                        and existing_mode == metadata["transmissionMode"]
                        and existing_session == metadata["collectionSessionId"]
                    ):
                        temp_path.unlink(missing_ok=True)
                        temp_path = None
                        return existing, True
                    raise ReceiverError(
                        "TRANSFER_ID_CONFLICT",
                        "X-Transfer-Id already belongs to different payload bytes or live semantics.",
                        status=409,
                        details={
                            "transferId": identifier,
                            "existingTransmissionMode": existing_mode,
                            "requestedTransmissionMode": metadata["transmissionMode"],
                            "existingCollectionSessionId": existing_session,
                            "requestedCollectionSessionId": metadata["collectionSessionId"],
                        },
                    )

                metadata_temp = self.incoming_dir / f"{identifier}.{uuid.uuid4().hex}.metadata.part"
                metadata_temp.write_text(
                    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )
                try:
                    os.replace(temp_path, data_path)
                    temp_path = None
                    os.replace(metadata_temp, metadata_path)
                except Exception:
                    metadata_temp.unlink(missing_ok=True)
                    data_path.unlink(missing_ok=True)
                    raise
                return metadata, False
        finally:
            if temp_path is not None:
                temp_path.unlink(missing_ok=True)

    def read_records(self, transfer_id: str, *, offset: int, limit: int) -> tuple[list[dict[str, Any]], int]:
        metadata = self.get_metadata(transfer_id)
        path = self._data_path(transfer_id)
        if not path.is_file():
            raise ReceiverError("STORAGE_CORRUPT", "Stored transmission data is missing.", status=500)
        records: list[dict[str, Any]] = []
        try:
            with path.open("rb") as handle:
                for index, raw_line in enumerate(handle):
                    if index < offset:
                        continue
                    if len(records) >= limit:
                        break
                    records.append(parse_and_validate_record(raw_line.rstrip(b"\r\n"), index + 1))
        except OSError as exc:
            raise ReceiverError("STORAGE_CORRUPT", "Stored transmission data cannot be read.", status=500) from exc
        return records, int(metadata["recordCount"])

    def download_path(self, transfer_id: str) -> Path:
        self.get_metadata(transfer_id)
        path = self._data_path(transfer_id)
        if not path.is_file():
            raise ReceiverError("STORAGE_CORRUPT", "Stored transmission data is missing.", status=500)
        return path

    def latest_context(
        self,
        *,
        collection_session_id: str | None = None,
        limit_per_source: int = DEFAULT_CONTEXT_LIMIT_PER_SOURCE,
    ) -> dict[str, Any]:
        """Return each source's newest live transmission without mixing sessions."""

        if not 1 <= limit_per_source <= MAX_CONTEXT_LIMIT_PER_SOURCE:
            raise ValueError("limit_per_source is outside the supported range")
        if collection_session_id is not None:
            collection_session_id = validate_collection_session_id(collection_session_id, required=True)

        live_modes = {"baseline", "periodic", "immediate"}
        live_items = [
            item for item in self.list_metadata()
            if item.get("transmissionMode") in live_modes and item.get("collectionSessionId")
        ]
        selector = "requested-session" if collection_session_id is not None else "latest-live-session"
        if collection_session_id is None and live_items:
            collection_session_id = str(live_items[0]["collectionSessionId"])
        if collection_session_id is None:
            return {
                "apiVersion": API_VERSION,
                "generatedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "selector": selector,
                "collectionSessionId": None,
                "transmissionCount": 0,
                "sourceCount": 0,
                "sources": {},
            }

        selected = [
            item for item in live_items
            if item.get("collectionSessionId") == collection_session_id
        ]
        contexts: dict[str, Any] = {}
        for metadata in selected:
            available = [
                str(source) for source in metadata.get("sources", {})
                if str(source) not in contexts
            ]
            if not available:
                continue
            wanted = set(available)
            grouped: dict[str, deque[dict[str, Any]]] = {
                source: deque(maxlen=limit_per_source) for source in available
            }
            latest_ts: dict[str, int] = {}
            transfer_id = str(metadata["transferId"])
            path = self.download_path(transfer_id)
            try:
                with path.open("rb") as handle:
                    for line_number, raw_line in enumerate(handle, start=1):
                        record = parse_and_validate_record(raw_line.rstrip(b"\r\n"), line_number)
                        source = str(record["source"])
                        if source not in wanted:
                            continue
                        latest_ts[source] = max(latest_ts.get(source, 0), int(record["ts"]))
                        grouped[source].append(record)
            except OSError as exc:
                raise ReceiverError(
                    "STORAGE_CORRUPT",
                    "Stored transmission data cannot be read.",
                    status=500,
                ) from exc

            source_counts = metadata.get("sources", {})
            for source in available:
                records = list(grouped[source])
                total = int(source_counts.get(source, len(records)))
                contexts[source] = {
                    "transferId": transfer_id,
                    "transmissionMode": metadata["transmissionMode"],
                    "receivedAt": metadata["receivedAt"],
                    "latestEventTs": latest_ts.get(source),
                    "recordCount": total,
                    "returned": len(records),
                    "truncated": total > len(records),
                    "records": records,
                }

        return {
            "apiVersion": API_VERSION,
            "generatedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "selector": selector,
            "collectionSessionId": collection_session_id,
            "transmissionCount": len(selected),
            "sourceCount": len(contexts),
            "sources": dict(sorted(contexts.items())),
        }
