from __future__ import annotations

import http.client
import json
import tempfile
import threading
import time
import unittest

from receiver.local_accuracy import (
    LocalAccuracyEngine,
    MAX_RAW_CHARS,
    RAW_PATH,
    RAW_SCHEMA,
    build_prompt,
    parse_raw_request,
)
from receiver.server import create_server


def request(message: str = "請加入投資群組保證獲利") -> dict[str, object]:
    return {
        "schema": RAW_SCHEMA,
        "task_id_alias": "TASK_ABCDEFGH",
        "request_nonce": "REQ_ABCDEFGHIJKLMNOPQRST",
        "issued_at_ms": int(time.time() * 1000),
        "message": message,
        "source_kind": "pasted",
    }


class RawContractTests(unittest.TestCase):
    def test_valid_request_and_untrusted_prompt(self) -> None:
        value = request()
        self.assertEqual(parse_raw_request(json.dumps(value, ensure_ascii=False).encode()), value)
        prompt = build_prompt(value["message"])
        self.assertIn("不可信", prompt)
        self.assertIn(value["message"], prompt)

    def test_unknown_empty_and_oversize_are_rejected(self) -> None:
        for mutate in (
            lambda value: value.update(extra=True),
            lambda value: value.update(message=""),
            lambda value: value.update(message="甲" * (MAX_RAW_CHARS + 1)),
        ):
            value = request()
            mutate(value)
            with self.assertRaises(Exception):
                parse_raw_request(json.dumps(value, ensure_ascii=False).encode())


class LocalAccuracyEngineTests(unittest.TestCase):
    def test_ai_can_raise_or_lower_rule_result_with_full_context(self) -> None:
        high = LocalAccuracyEngine(lambda _text: (4, 0)).assess(request("今晚一起吃飯"))
        self.assertEqual(high["risk_level"], "high")
        self.assertIn("AI_SCAM_PATTERN", high["reason_codes"])

        safe = LocalAccuracyEngine(lambda _text: (0, 4)).assess(request("請匯款到這個帳戶"))
        self.assertEqual(safe["risk_level"], "verify")
        self.assertEqual(safe["engine"], "LOCAL_QWEN_RAW")


class LocalAccuracyHttpTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.server = create_server("127.0.0.1", 0, data_dir=self.temp.name,
                                    local_raw_scorer=lambda _text: (4, 0))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.temp.cleanup()

    def test_end_to_end_raw_local_ai(self) -> None:
        body = json.dumps(request(), ensure_ascii=False).encode()
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request("POST", RAW_PATH, body=body, headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        payload = json.loads(response.read())
        connection.close()
        self.assertEqual(response.status, 200)
        self.assertEqual(payload["risk_level"], "high")
        self.assertEqual(payload["engine"], "LOCAL_QWEN_RAW")


if __name__ == "__main__":
    unittest.main()
