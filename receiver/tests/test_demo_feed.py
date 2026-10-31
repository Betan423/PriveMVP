from __future__ import annotations

import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

from receiver.demo_feed import DemoFeed, phone_steps
from receiver.phone_sim import build_packet
from receiver.server import ASSIST_PATH, create_server

SCAM = "王小明您好，我是銀行客服，請立即提供驗證碼 482913，電話 0912-345-678"


def encode(packet: dict[str, object]) -> bytes:
    return json.dumps(packet, ensure_ascii=False).encode("utf-8")


class DemoFeedUnitTests(unittest.TestCase):
    def test_feed_is_bounded_and_ordered(self) -> None:
        feed = DemoFeed(max_events=3)
        for i in range(5):
            feed.add({"i": i})
        events = feed.since(0)
        self.assertEqual([e["i"] for e in events], [2, 3, 4])
        self.assertEqual([e["i"] for e in feed.since(events[1]["seq"])], [4])

    def test_phone_steps_never_put_identifiers_in_the_packet(self) -> None:
        steps = phone_steps(SCAM)
        self.assertIn("[OTP]", steps["redacted"])
        self.assertIn("[PHONE]", steps["redacted"])
        self.assertIn("[NAME]", steps["redacted"])
        self.assertEqual(steps["local"]["risk_level"], "high")
        self.assertIn("requests_secret", steps["triggers"])
        wire = encode(steps["packet"]).decode("utf-8")
        for value in ("王小明", "482913", "0912"):
            self.assertNotIn(value, wire)
            self.assertNotIn(value, json.dumps(steps["triggers"], ensure_ascii=False))
        self.assertIsNone(steps["packet"]["redacted_excerpt"])

    def test_public_mode_refuses_demo_feed(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as temp, self.assertRaises(ValueError):
            create_server("127.0.0.1", 0, data_dir=temp, api_token="t", assist_only=True, demo_feed=True)


class DemoFeedHttpTests(unittest.TestCase):
    def start(self, demo_feed: bool, planner: object = None, api_token: str | None = None) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.server = create_server("127.0.0.1", 0, data_dir=self.temp.name, planner=planner,
                                    api_token=api_token, demo_feed=demo_feed)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.host, self.port = self.server.server_address[:2]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict[str, str] | None = None) -> tuple[int, bytes]:
        connection = http.client.HTTPConnection(self.host, self.port, timeout=5)
        connection.request(method, path, body=body,
                           headers={**({"Content-Type": "application/json"} if body else {}), **(headers or {})})
        response = connection.getresponse()
        result = response.status, response.read()
        connection.close()
        return result

    def events(self) -> list[dict[str, object]]:
        status, body = self.request("GET", "/api/v1/demo/events?after=0")
        self.assertEqual(status, 200)
        return json.loads(body)["events"]

    def test_demo_routes_do_not_exist_without_flag(self) -> None:
        self.start(demo_feed=False)
        self.assertEqual(self.request("GET", "/demo")[0], 404)
        self.assertEqual(self.request("GET", "/api/v1/demo/events")[0], 404)
        self.assertEqual(self.request("POST", "/api/v1/demo/sandbox", b'{"text":"hi"}')[0], 404)

    def test_phone_packet_and_replay_show_up_in_feed(self) -> None:
        self.start(demo_feed=True)
        status, page = self.request("GET", "/demo")
        self.assertEqual(status, 200)
        self.assertIn("金孫接收端".encode("utf-8"), page)
        packet = build_packet(SCAM)
        self.assertEqual(self.request("POST", ASSIST_PATH, encode(packet))[0], 200)
        self.assertEqual(self.request("POST", ASSIST_PATH, encode(packet))[0], 409)
        assessed, rejected = self.events()
        self.assertEqual(assessed["kind"], "assessed")
        self.assertEqual(assessed["final"]["risk_level"], "high")
        self.assertEqual(assessed["planner"]["fallback_reason"], "PLANNER_DISABLED")
        self.assertEqual(rejected["kind"], "rejected")
        self.assertEqual(rejected["code"], "PACKET_REPLAYED")
        self.assertNotIn("packet", rejected)

    def test_wire_view_is_the_request_as_received_with_token_masked(self) -> None:
        self.start(demo_feed=True, api_token="stage-secret")
        body = encode(build_packet(SCAM))
        status, _ = self.request("POST", ASSIST_PATH, body, {"X-Api-Token": "stage-secret"})
        self.assertEqual(status, 200)
        (event,) = self.events()
        head, _, wire_body = event["wire"].partition("\r\n\r\n")
        self.assertTrue(head.startswith(f"POST {ASSIST_PATH} HTTP/1.1"))
        self.assertIn("X-Api-Token: ••••••••", head)
        self.assertNotIn("stage-secret", event["wire"])
        self.assertEqual(wire_body.encode("utf-8"), body)

    def test_sandbox_runs_the_same_gates_and_feeds_the_live_view(self) -> None:
        prompts: list[str] = []

        def planner(prompt: str) -> str:
            prompts.append(prompt)
            return '{"sop_id":"MESSAGE_VERIFY","risk_level":"verify","reason_codes":["NO_STRONG_SIGNAL"]}'

        self.start(demo_feed=True, planner=planner)
        status, body = self.request("POST", "/api/v1/demo/sandbox", json.dumps({"text": SCAM}, ensure_ascii=False).encode("utf-8"))
        self.assertEqual(status, 200)
        result = json.loads(body)
        # Gemini tried to lower the risk; the phone rule floor wins.
        self.assertEqual(result["result"]["risk_level"], "high")
        self.assertEqual(len(prompts), 1)
        self.assertNotIn("482913", prompts[0])
        (event,) = self.events()
        self.assertEqual(event["origin"], "sandbox")
        self.assertEqual(event["planner"]["risk_level"], "verify")
        self.assertEqual(event["final"]["risk_level"], "high")


if __name__ == "__main__":
    unittest.main()
