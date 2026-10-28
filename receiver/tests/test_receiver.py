from __future__ import annotations

import hashlib
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

from receiver.contracts import ReceiverError, parse_and_validate_record
from receiver.server import create_server
from receiver.storage import ReceiverStorage


def record(index: int = 1, *, session: str = "session-1", source: str = "device") -> dict[str, object]:
    return {
        "schemaVersion": 2,
        "ts": 1_790_000_000_000 + index,
        "sessionId": session,
        "source": source,
        "sensitivity": "P2",
        "payload": {"status": "ok", "message": f"中文-{index}"},
    }


def ndjson(*values: dict[str, object]) -> bytes:
    return b"".join(
        json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
        for value in values
    )


class ContractTests(unittest.TestCase):
    def test_valid_record(self) -> None:
        value = record()
        self.assertEqual(parse_and_validate_record(ndjson(value).rstrip(), 1), value)

    def test_duplicate_key_and_missing_fields_are_rejected(self) -> None:
        with self.assertRaisesRegex(ReceiverError, "valid JSON object"):
            parse_and_validate_record(b'{"schemaVersion":2,"schemaVersion":3}', 1)
        with self.assertRaises(ReceiverError) as caught:
            parse_and_validate_record(b'{"schemaVersion":2}', 3)
        self.assertEqual(caught.exception.code, "MISSING_RECORD_FIELDS")
        self.assertEqual(caught.exception.details["line"], 3)


class StorageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.storage = ReceiverStorage(self.temp.name, max_body_bytes=2048, max_line_bytes=1024)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_commit_list_read_and_download(self) -> None:
        body = ndjson(record(1), record(2, source="battery"))
        metadata, duplicate = self.storage.receive(
            "demo-001",
            [body[:17], body[17:]],
            expected_record_count=2,
            expected_sha256=hashlib.sha256(body).hexdigest(),
        )
        self.assertFalse(duplicate)
        self.assertEqual(metadata["recordCount"], 2)
        self.assertEqual(metadata["receivedRecords"], 2)
        self.assertEqual(metadata["sources"], {"battery": 1, "device": 1})
        self.assertEqual(self.storage.list_metadata()[0]["transferId"], "demo-001")
        records, total = self.storage.read_records("demo-001", offset=1, limit=1)
        self.assertEqual(total, 2)
        self.assertEqual(records[0]["payload"]["message"], "中文-2")
        self.assertEqual(self.storage.download_path("demo-001").read_bytes(), body)

    def test_duplicate_retry_and_hash_conflict(self) -> None:
        body = ndjson(record())
        first, duplicate = self.storage.receive("retry-id", [body])
        self.assertFalse(duplicate)
        second, duplicate = self.storage.receive("retry-id", [body])
        self.assertTrue(duplicate)
        self.assertEqual(first, second)
        with self.assertRaises(ReceiverError) as caught:
            self.storage.receive("retry-id", [ndjson(record(2))])
        self.assertEqual(caught.exception.status, 409)

    def test_duplicate_id_cannot_change_live_mode_or_session(self) -> None:
        body = ndjson(record(session="live-session"))
        self.storage.receive(
            "semantic-retry",
            [body],
            transmission_mode="baseline",
            collection_session_id="live-session",
        )
        with self.assertRaises(ReceiverError) as caught:
            self.storage.receive(
                "semantic-retry",
                [body],
                transmission_mode="periodic",
                collection_session_id="live-session",
            )
        self.assertEqual(caught.exception.code, "TRANSFER_ID_CONFLICT")
        self.assertEqual(caught.exception.status, 409)

    def test_invalid_or_mismatched_body_leaves_no_file(self) -> None:
        with self.assertRaises(ReceiverError):
            self.storage.receive("invalid", [b"not json\n"])
        body = ndjson(record())
        with self.assertRaises(ReceiverError) as caught:
            self.storage.receive("bad-count", [body], expected_record_count=2)
        self.assertEqual(caught.exception.code, "RECORD_COUNT_MISMATCH")
        with self.assertRaises(ReceiverError) as caught:
            self.storage.receive("bad-hash", [body], expected_sha256="0" * 64)
        self.assertEqual(caught.exception.code, "PAYLOAD_SHA256_MISMATCH")
        self.assertEqual(list(Path(self.temp.name).glob("*.ndjson")), [])
        self.assertEqual(list((Path(self.temp.name) / ".incoming").iterdir()), [])

    def test_size_and_line_limits(self) -> None:
        body_limited = ReceiverStorage(self.temp.name, max_body_bytes=10, max_line_bytes=10)
        with self.assertRaises(ReceiverError) as caught:
            body_limited.receive("too-big", [b"12345678901"])
        self.assertEqual(caught.exception.code, "BODY_TOO_LARGE")
        line_limited = ReceiverStorage(self.temp.name, max_body_bytes=2048, max_line_bytes=20)
        with self.assertRaises(ReceiverError) as caught:
            line_limited.receive("long-line", [ndjson(record())])
        self.assertEqual(caught.exception.code, "LINE_TOO_LARGE")


class ServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.server = create_server(
            "127.0.0.1",
            0,
            data_dir=self.temp.name,
            max_body_bytes=4096,
            max_line_bytes=2048,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.host, self.port = self.server.server_address[:2]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        connection = http.client.HTTPConnection(self.host, self.port, timeout=3)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        payload = response.read()
        result = response.status, dict(response.getheaders()), payload
        connection.close()
        return result

    def test_health_and_dashboard(self) -> None:
        status, _, body = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["status"], "ok")
        status, headers, body = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers["Content-Type"])
        self.assertIn("AI 金孫資料接收端", body.decode("utf-8"))

    def test_full_http_flow_headers_and_duplicate(self) -> None:
        body = ndjson(record(1), record(2, source="network"))
        headers = {
            "Content-Type": "application/x-ndjson",
            "X-Transfer-Id": "http-flow",
            "X-Record-Count": "2",
            "X-Payload-Sha256": hashlib.sha256(body).hexdigest().upper(),
        }
        status, response_headers, payload = self.request("POST", "/api/v1/transmissions", body, headers)
        self.assertEqual(status, 201)
        created = json.loads(payload)
        self.assertFalse(created["duplicate"])
        self.assertEqual(created["receivedRecords"], 2)
        self.assertEqual(created["sha256"], hashlib.sha256(body).hexdigest())
        self.assertEqual(response_headers["Location"], "/api/v1/transmissions/http-flow")

        status, _, payload = self.request("POST", "/api/v1/transmissions", body, headers)
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(payload)["duplicate"])

        status, _, payload = self.request("GET", "/api/v1/transmissions")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(payload)["count"], 1)
        status, _, payload = self.request("GET", "/api/v1/transmissions/http-flow")
        self.assertEqual(json.loads(payload)["sha256"], created["sha256"])
        status, _, payload = self.request("GET", "/api/v1/transmissions/http-flow/records?offset=1&limit=1")
        records = json.loads(payload)
        self.assertEqual(records["returned"], 1)
        self.assertEqual(records["records"][0]["source"], "network")
        status, response_headers, payload = self.request("GET", "/api/v1/transmissions/http-flow/download")
        self.assertEqual(status, 200)
        self.assertEqual(payload, body)
        self.assertIn("attachment", response_headers["Content-Disposition"])

        status, _, payload = self.request("POST", "/api/v1/transmissions", ndjson(record(9)), {
            "Content-Type": "application/x-ndjson", "X-Transfer-Id": "http-flow"})
        self.assertEqual(status, 409)
        self.assertEqual(json.loads(payload)["error"]["code"], "TRANSFER_ID_CONFLICT")

    def test_chunked_upload_and_generated_id(self) -> None:
        body = ndjson(record())
        connection = http.client.HTTPConnection(self.host, self.port, timeout=3)
        connection.request(
            "POST",
            "/api/v1/transmissions",
            body=iter((body[:10], body[10:])),
            headers={"Content-Type": "application/x-ndjson"},
            encode_chunked=True,
        )
        response = connection.getresponse()
        payload = json.loads(response.read())
        status = response.status
        connection.close()
        self.assertEqual(status, 201)
        self.assertTrue(payload["transferId"].startswith("tr_"))

    def test_live_modes_and_latest_context_preserve_source_chunks(self) -> None:
        session = "live-session-1"
        baseline_body = ndjson(
            record(1, session=session, source="apps"),
            record(2, session=session, source="apps"),
            record(3, session=session, source="battery"),
        )
        baseline_headers = {
            "Content-Type": "application/x-ndjson",
            "X-Transfer-Id": "live-baseline",
            "X-Record-Count": "3",
            "X-Payload-Sha256": hashlib.sha256(baseline_body).hexdigest(),
            "X-Transmission-Mode": "baseline",
            "X-Client-Version": "0.4",
            "X-Client-Sent-At": "1790000000100",
            "X-Collection-Session-Id": session,
        }
        status, _, payload = self.request(
            "POST", "/api/v1/transmissions", baseline_body, baseline_headers
        )
        self.assertEqual(status, 201)
        baseline = json.loads(payload)
        self.assertEqual(baseline["transmissionMode"], "baseline")
        self.assertEqual(baseline["collectionSessionId"], session)

        periodic_body = ndjson(
            record(4, session=session, source="battery"),
            record(5, session=session, source="network"),
        )
        periodic_headers = {
            "Content-Type": "application/x-ndjson",
            "X-Transfer-Id": "live-periodic",
            "X-Transmission-Mode": "periodic",
            "X-Client-Version": "0.4",
            "X-Client-Sent-At": "1790000000200",
            "X-Collection-Session-Id": session,
        }
        status, _, _ = self.request(
            "POST", "/api/v1/transmissions", periodic_body, periodic_headers
        )
        self.assertEqual(status, 201)

        manual_body = ndjson(record(9, session="manual-session", source="device"))
        status, _, _ = self.request(
            "POST",
            "/api/v1/transmissions",
            manual_body,
            {"Content-Type": "application/x-ndjson", "X-Transfer-Id": "manual-history"},
        )
        self.assertEqual(status, 201)

        status, _, payload = self.request("GET", "/api/v1/context/latest?limitPerSource=10")
        self.assertEqual(status, 200)
        context = json.loads(payload)
        self.assertEqual(context["collectionSessionId"], session)
        self.assertEqual(set(context["sources"]), {"apps", "battery", "network"})
        self.assertEqual(context["sources"]["apps"]["recordCount"], 2)
        self.assertEqual(context["sources"]["apps"]["returned"], 2)
        self.assertEqual(context["sources"]["battery"]["transferId"], "live-periodic")
        self.assertNotIn("device", context["sources"])

        status, _, payload = self.request(
            "GET", f"/api/v1/context/latest?sessionId={session}&limitPerSource=1"
        )
        limited = json.loads(payload)
        self.assertEqual(status, 200)
        self.assertEqual(limited["sources"]["apps"]["returned"], 1)
        self.assertTrue(limited["sources"]["apps"]["truncated"])
        self.assertEqual(
            limited["sources"]["apps"]["records"][0]["payload"]["message"],
            "中文-2",
        )

        status, _, payload = self.request("GET", "/api/v1/context/latest?sessionId=")
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(payload)["error"]["code"], "INVALID_COLLECTION_SESSION_ID")

    def test_live_headers_and_session_mismatch_are_rejected(self) -> None:
        body = ndjson(record(session="actual-session"))
        status, _, payload = self.request(
            "POST",
            "/api/v1/transmissions",
            body,
            {
                "Content-Type": "application/x-ndjson",
                "X-Transmission-Mode": "periodic",
            },
        )
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(payload)["error"]["code"], "COLLECTION_SESSION_REQUIRED")

        status, _, payload = self.request(
            "POST",
            "/api/v1/transmissions",
            body,
            {
                "Content-Type": "application/x-ndjson",
                "X-Transmission-Mode": "periodic",
                "X-Collection-Session-Id": "different-session",
            },
        )
        self.assertEqual(status, 422)
        self.assertEqual(json.loads(payload)["error"]["code"], "COLLECTION_SESSION_MISMATCH")

    def test_invalid_requests_return_stable_errors(self) -> None:
        status, _, payload = self.request(
            "POST",
            "/api/v1/transmissions",
            b"{}\n",
            {"Content-Type": "application/x-ndjson", "X-Transfer-Id": "bad"},
        )
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(payload)["error"]["code"], "MISSING_RECORD_FIELDS")
        status, _, payload = self.request("GET", "/api/v1/transmissions/no-such-id")
        self.assertEqual(status, 404)
        self.assertEqual(json.loads(payload)["error"]["code"], "TRANSMISSION_NOT_FOUND")
        status, _, payload = self.request(
            "POST", "/api/v1/transmissions", ndjson(record()), {"Content-Type": "text/plain"}
        )
        self.assertEqual(status, 415)
        self.assertEqual(json.loads(payload)["error"]["code"], "UNSUPPORTED_MEDIA_TYPE")
        status, _, payload = self.request(
            "POST",
            "/api/v1/transmissions",
            ndjson(record()),
            {"Content-Type": "application/x-ndjson", "X-Record-Count": "2"},
        )
        self.assertEqual(status, 422)
        self.assertEqual(json.loads(payload)["error"]["code"], "RECORD_COUNT_MISMATCH")
        status, _, payload = self.request(
            "POST",
            "/api/v1/transmissions",
            ndjson(record()),
            {"Content-Type": "application/x-ndjson", "X-Transfer-Id": ""},
        )
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(payload)["error"]["code"], "INVALID_TRANSFER_ID")


if __name__ == "__main__":
    unittest.main()
