from __future__ import annotations

import http.client
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from receiver.assistant import (
    MessageAssistant,
    PlannerUnavailable,
    build_prompt,
    forbidden_hits,
    gemini_proxy_planner,
    parse_packet,
    parse_planner_answer,
    rule_assessment,
)
from receiver.contracts import ReceiverError
from receiver.phone_sim import build_packet, extract_facts, redact
from receiver.server import ASSIST_PATH, create_server

SOPS = ["MESSAGE_CAUTION", "MESSAGE_VERIFY", "MESSAGE_INSUFFICIENT", "ASK_USER"]


def encode(packet: dict[str, object]) -> bytes:
    return json.dumps(packet, ensure_ascii=False).encode("utf-8")


def answer(sop: str, level: str, codes: list[str]) -> str:
    return json.dumps({"sop_id": sop, "risk_level": level, "reason_codes": codes})


class PhoneSideTests(unittest.TestCase):
    def test_identifiers_are_redacted(self) -> None:
        text = ("我是客服，驗證碼 482913 請回傳，帳號 SYN_ACCOUNT_PRIVATE_0001_12，"
                "電話 0912-345-678，網址 https://bank.example.invalid/login，信箱 a.b@example.com，身分證 A123456789")
        redacted = redact(text)
        for leaked in ("482913", "SYN_", "0912", "https", "example", "@", "A123456789"):
            self.assertNotIn(leaked, redacted)
        for placeholder in ("[OTP]", "[ACCOUNT]", "[PHONE]", "[LINK]", "[EMAIL]", "[ID]"):
            self.assertIn(placeholder, redacted)

    def test_negated_requests_are_not_risk_signals(self) -> None:
        facts = extract_facts("一次性驗證碼 123456，請勿告訴任何人。", "pasted")
        self.assertFalse(facts["requests_secret"])
        facts = extract_facts("包裹已送達管理室，不需要付款或提供資料。", "pasted")
        self.assertFalse(facts["asks_transfer"])
        self.assertFalse(facts["requests_secret"])

    def test_negation_does_not_swallow_later_requests(self) -> None:
        for text in ("不要猶豫請轉帳給我", "不要猶豫，請轉帳給我", "請勿掛斷請提供驗證碼給我",
                     "請勿提供資料，請轉帳給我。", "不用付款，提供驗證碼給我"):
            self.assertEqual(rule_assessment(extract_facts(text, "pasted"))[0], "high", text)

    def test_spacing_and_format_controls_cannot_hide_scam_signals(self) -> None:
        attacks = (
            "請立即匯 款給我",
            "請立即匯\u200b款給我",
            "請提 供驗 證 碼給我",
            "我是客\u2060服，請提供驗證碼給我",
        )
        for text in attacks:
            with self.subTest(text=text):
                facts = extract_facts(text, "pasted")
                self.assertEqual(rule_assessment(facts)[0], "high")
        self.assertFalse(extract_facts("請 勿 提 供 驗 證 碼", "pasted")["requests_secret"])

    def test_format_controls_cannot_hide_identifiers(self) -> None:
        for text in ("電話 0912\u200b345678", "網址 https://exa\u2060mple.invalid/login"):
            with self.subTest(text=text):
                redacted = redact(text)
                self.assertEqual(forbidden_hits({"excerpt": redacted}), [])
        self.assertIn("digits", forbidden_hits({"excerpt": "0912\u200b345678"}))
        self.assertIn("url", forbidden_hits({"excerpt": "https://exa\u2060mple.invalid"}))

    def test_scam_features(self) -> None:
        facts = extract_facts("我是家人，臨時換號碼，請立即轉帳到 SYN_ACCOUNT_PRIVATE_1。", "pasted")
        self.assertTrue(facts["asks_transfer"])
        self.assertTrue(facts["urgency"])
        self.assertEqual(facts["impersonation"], "family")
        self.assertEqual(rule_assessment(facts)[0], "high")

    def test_link_kind_is_classified_on_device(self) -> None:
        cases = {
            "請上 https://www.post.gov.tw/track 查詢進度": "known_official",
            "請上 https://esunbank.com.tw/login 查詢": "known_official",
            "請點 https://taiwanpost-verify.xyz/login 補填資料": "suspicious",
            "請點 https://post.gov.tw.verify.cc/login": "suspicious",
            "請點 https://192.168.0.99/login": "suspicious",
            "請點 https://xn--fiq228c.com/x": "suspicious",
            "詳情 https://reurl.cc/abc 查看": "shortener",
            "詳情 https://news.example.invalid/article": "unknown",
            "明天下午一起吃飯。": "none",
        }
        for text, expected in cases.items():
            facts = extract_facts(text, "pasted")
            self.assertEqual(expected, facts["link_kind"], text)
            self.assertEqual(expected != "none", facts["has_link"], text)

    def test_most_severe_link_kind_wins_and_literal_placeholder_is_not_a_link(self) -> None:
        mixed = extract_facts("官方 https://www.gov.tw 公告，另見 https://promo.bad.xyz/go", "pasted")
        self.assertEqual("suspicious", mixed["link_kind"])
        literal = extract_facts("對方傳來的內容是 [LINK] 這五個字，我沒有看到網址。", "pasted")
        self.assertFalse(literal["has_link"])
        self.assertEqual("none", literal["link_kind"])

    def test_official_link_no_longer_escalates_but_suspicious_link_is_decisive(self) -> None:
        official = extract_facts("您的包裹已到，請上 https://www.post.gov.tw/track 查詢，今天內未處理將退回", "pasted")
        level, codes = rule_assessment(official)
        self.assertEqual("verify", level)
        self.assertIn("HAS_LINK", codes)
        phishing = extract_facts("您的包裹已到，請點 https://taiwanpost-verify.xyz/login 補填收件資料", "pasted")
        level, codes = rule_assessment(phishing)
        self.assertEqual("high", level)
        self.assertIn("LINK_SUSPICIOUS", codes)
        shortener = extract_facts("您的包裹已到，請點 https://reurl.cc/abc 補填，今天內未處理將退回", "pasted")
        level, codes = rule_assessment(shortener)
        self.assertEqual("high", level)
        self.assertIn("LINK_SHORTENER", codes)

    def test_link_kind_must_be_consistent_with_has_link(self) -> None:
        packet = build_packet("詳情 https://news.example.invalid/article 查看")
        packet["facts"]["link_kind"] = "none"
        with self.assertRaises(ReceiverError) as caught:
            parse_packet(encode(packet))
        self.assertEqual(caught.exception.code, "PACKET_CONTRACT_VIOLATION")
        packet = build_packet("明天下午一起吃飯。")
        packet["facts"]["link_kind"] = "unknown"
        with self.assertRaises(ReceiverError):
            parse_packet(encode(packet))

    def test_excerpt_is_opt_in_and_only_when_local_rules_are_not_decisive(self) -> None:
        self.assertIsNone(build_packet("明天下午一起吃飯。")["redacted_excerpt"])
        scam = "請立即轉帳到 SYN_ACCOUNT_PRIVATE_1，否則停權"
        self.assertIsNone(build_packet(scam, allow_excerpt=True)["redacted_excerpt"])
        self.assertEqual(build_packet("明天下午一起吃飯。", allow_excerpt=True)["redacted_excerpt"], "明天下午一起吃飯。")

    def test_identifier_format_variants_are_redacted(self) -> None:
        cases = {
            "聯絡電話 09.12.34.56.78": "09",
            "聯絡電話 +886 912 345 678": "912",
            "電話（02）234-567-89": "234",
            "電話 ０９１２－３４５－６７８": "345",
            "帳號 012/345/678/901": "678",
            "本次驗證碼 A7B9C2": "A7B9C2",
            "請見 t.ly/AbCd": "t.ly",
            "網站 https://bank.example.tw/login": "bank",
        }
        for text, fragment in cases.items():
            redacted = redact(text)
            self.assertNotIn(fragment, redacted, text)
            self.assertEqual(forbidden_hits({"x": redacted}), [], text)

    def test_names_from_salutation_and_known_contacts(self) -> None:
        self.assertNotIn("王小明", redact("王小明您好，明天下午一起吃飯。"))
        self.assertNotIn("美玲", redact("美玲說明天要來", known_names=("美玲",)))

    def test_alias_is_task_scoped(self) -> None:
        packets = [build_packet("明天下午一起吃飯。") for _ in range(50)]
        aliases = {packet["task_id_alias"] for packet in packets}
        nonces = {packet["request_nonce"] for packet in packets}
        self.assertEqual(len(aliases), 50)
        self.assertEqual(len(nonces), 50)


class PacketContractTests(unittest.TestCase):
    def test_valid_packet_passes(self) -> None:
        packet = build_packet("明天下午一起吃飯。")
        self.assertEqual(parse_packet(encode(packet)), packet)

    def test_unknown_or_missing_fields_are_rejected(self) -> None:
        packet = build_packet("明天下午一起吃飯。")
        packet["user_name"] = "x"
        with self.assertRaises(ReceiverError) as caught:
            parse_packet(encode(packet))
        self.assertEqual(caught.exception.code, "PACKET_CONTRACT_VIOLATION")
        packet = build_packet("明天下午一起吃飯。")
        packet["facts"]["sender"] = "x"
        with self.assertRaises(ReceiverError):
            parse_packet(encode(packet))

    def test_missing_cloud_consent_is_forbidden(self) -> None:
        packet = build_packet("明天下午一起吃飯。")
        packet["cloud_consent"] = False
        with self.assertRaises(ReceiverError) as caught:
            parse_packet(encode(packet))
        self.assertEqual(caught.exception.status, 403)

    def test_raw_identifiers_in_excerpt_are_rejected(self) -> None:
        for leaked in ("請打 0912345678", "SYN_NAME_PRIVATE_1 您好", "到 https://x.example.invalid", "驗證碼 4821"):
            packet = build_packet("明天下午一起吃飯。")
            packet["redacted_excerpt"] = leaked
            with self.assertRaises(ReceiverError) as caught:
                parse_packet(encode(packet))
            self.assertEqual(caught.exception.code, "FORBIDDEN_VALUE_IN_PACKET")
            self.assertNotIn(leaked, caught.exception.message)

    def test_wrong_types_and_unknown_versions_are_contract_errors(self) -> None:
        mutations = (
            ("allowed_sops", [{}]),
            ("client_policy_version", "wang.xiaoming"),
            ("redacted_excerpt", "\ud800"),
            ("issued_at_ms", True),
            ("request_nonce", "REQ_TOO_SHORT"),
        )
        for key, value in mutations:
            packet = build_packet("明天下午一起吃飯。")
            packet[key] = value
            body = json.dumps(packet).encode("ascii")  # ensure_ascii keeps the lone surrogate escaped
            with self.assertRaises(ReceiverError, msg=key) as caught:
                parse_packet(body)
            self.assertEqual(caught.exception.code, "PACKET_CONTRACT_VIOLATION", key)

    def test_duplicate_keys_are_rejected(self) -> None:
        with self.assertRaises(ReceiverError):
            parse_packet(b'{"schema":"a","schema":"b"}')

    def test_prompt_contains_no_alias_or_consent_metadata(self) -> None:
        prompt = build_prompt(build_packet("明天下午一起吃飯。"))
        self.assertNotIn("TASK_", prompt)
        self.assertNotIn("REQ_", prompt)
        self.assertNotIn("issued_at_ms", prompt)
        self.assertEqual(forbidden_hits(prompt), [])

    def test_high_risk_excerpt_exceeds_privacy_budget(self) -> None:
        packet = build_packet("請立即轉帳給我")
        packet["redacted_excerpt"] = "請立即轉帳給我"
        with self.assertRaises(ReceiverError) as caught:
            parse_packet(encode(packet))
        self.assertEqual(caught.exception.code, "PRIVACY_BUDGET_EXCEEDED")


class PlannerAnswerTests(unittest.TestCase):
    def test_plain_and_fenced_answers(self) -> None:
        self.assertEqual(parse_planner_answer(answer("MESSAGE_CAUTION", "high", ["URGENCY"]), SOPS),
                         ("MESSAGE_CAUTION", "high", ["URGENCY"]))
        fenced = "```json\n" + answer("MESSAGE_VERIFY", "verify", ["NO_STRONG_SIGNAL"]) + "\n```"
        self.assertEqual(parse_planner_answer(fenced, SOPS)[1], "verify")

    def test_out_of_whitelist_answers_are_rejected(self) -> None:
        bad = [
            "這則訊息很安全",
            answer("SEND_MONEY", "high", ["URGENCY"]),
            answer("MESSAGE_CAUTION", "safe", ["URGENCY"]),
            answer("MESSAGE_VERIFY", "high", ["URGENCY"]),
            answer("MESSAGE_CAUTION", "high", ["CALL_THIS_NUMBER"]),
            "CALL 0912345678 " + answer("MESSAGE_VERIFY", "verify", ["NO_STRONG_SIGNAL"]),
            json.dumps({"sop_id": "MESSAGE_CAUTION", "risk_level": "high", "reason_codes": ["URGENCY"], "say": "x"}),
        ]
        for text in bad:
            with self.assertRaises(ValueError, msg=text):
                parse_planner_answer(text, SOPS)


class AssistantTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.audit = Path(self.temp.name) / "audit.ndjson"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_model_cannot_lower_rule_risk(self) -> None:
        assistant = MessageAssistant(self.audit, lambda _p: answer("MESSAGE_VERIFY", "verify", ["NO_STRONG_SIGNAL"]))
        result = assistant.assess(build_packet("我是客服，請提供一次性驗證碼 SYN_OTP_PRIVATE_1。"))
        self.assertEqual(result["risk_level"], "high")
        self.assertEqual(result["sop_id"], "MESSAGE_CAUTION")
        self.assertNotIn("NO_STRONG_SIGNAL", result["reason_codes"])

    def test_model_can_raise_caution(self) -> None:
        assistant = MessageAssistant(self.audit, lambda _p: answer("MESSAGE_CAUTION", "high", ["ASKS_TRANSFER"]))
        result = assistant.assess(build_packet("明天下午一起吃飯。"))
        self.assertEqual(result["risk_level"], "high")
        self.assertEqual(result["planner"]["kind"], "GEMINI_PROXY")

    def test_planner_failures_fall_back_to_rules(self) -> None:
        def down(_prompt: str) -> str:
            raise PlannerUnavailable("PLANNER_UNREACHABLE")

        for planner, reason in ((down, "PLANNER_UNREACHABLE"), (lambda _p: "not json", "PLANNER_INVALID_RESPONSE"),
                                (None, "PLANNER_DISABLED")):
            result = MessageAssistant(self.audit, planner).assess(build_packet("這是虛構訊息。請處理這件事，細節還沒有說明。"))
            self.assertEqual(result["risk_level"], "insufficient")
            self.assertEqual(result["planner"], {"kind": "RULES", "fallback_reason": reason})

    def test_unexpected_planner_exception_falls_back(self) -> None:
        def broken(_prompt: str) -> str:
            raise http.client.IncompleteRead(b"")

        result = MessageAssistant(self.audit, broken).assess(build_packet("明天下午一起吃飯。"))
        self.assertEqual(result["planner"]["kind"], "RULES")

    def test_audit_never_stores_excerpt(self) -> None:
        packet = build_packet("明天下午一起吃飯，記得帶傘。", allow_excerpt=True)
        MessageAssistant(self.audit, None).assess(packet)
        text = self.audit.read_text(encoding="utf-8")
        self.assertNotIn("帶傘", text)
        self.assertIn(packet["task_id_alias"], text)


class ProxyAdapterTests(unittest.TestCase):
    """Exercises the real HTTP adapter against a fake proxy, including broken responses."""

    def serve(self, raw_response: bytes) -> str:
        import socket

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        self.addCleanup(listener.close)

        def respond() -> None:
            connection, _ = listener.accept()
            with connection:
                connection.recv(65536)
                connection.sendall(raw_response)
                # Half-close and drain so the client reads the response instead of a reset.
                connection.shutdown(socket.SHUT_WR)
                connection.settimeout(3)
                try:
                    while connection.recv(4096):
                        pass
                except OSError:
                    pass

        threading.Thread(target=respond, daemon=True).start()
        return f"http://127.0.0.1:{listener.getsockname()[1]}"

    def test_valid_envelope(self) -> None:
        body = json.dumps({"answer": answer("MESSAGE_VERIFY", "verify", ["NO_STRONG_SIGNAL"])}).encode()
        url = self.serve(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                         str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
        self.assertIn("MESSAGE_VERIFY", gemini_proxy_planner(url, 3)("prompt"))

    def test_broken_responses_become_planner_unavailable(self) -> None:
        responses = {
            "PLANNER_TRANSPORT_ERROR": b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nab",
            "PLANNER_HTTP_502": b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "PLANNER_BAD_ENVELOPE": b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]",
        }
        for reason, raw in responses.items():
            with self.assertRaises(PlannerUnavailable, msg=reason) as caught:
                gemini_proxy_planner(self.serve(raw), 3)("prompt")
            self.assertEqual(caught.exception.reason, reason)


class AssistHttpTests(unittest.TestCase):
    def start(self, planner: object = None, api_token: str | None = None) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.server = create_server("127.0.0.1", 0, data_dir=self.temp.name, planner=planner, api_token=api_token)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.host, self.port = self.server.server_address[:2]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def post(self, body: bytes, headers: dict[str, str] | None = None) -> tuple[int, dict[str, object]]:
        connection = http.client.HTTPConnection(self.host, self.port, timeout=3)
        connection.request("POST", ASSIST_PATH, body=body,
                           headers={"Content-Type": "application/json", **(headers or {})})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_end_to_end_with_fake_planner(self) -> None:
        prompts: list[str] = []

        def planner(prompt: str) -> str:
            prompts.append(prompt)
            return answer("MESSAGE_CAUTION", "high", ["ASKS_TRANSFER", "IMPERSONATION_FAMILY"])

        self.start(planner)
        status, result = self.post(encode(build_packet("我是家人，換號碼了，請匯款到 SYN_ACCOUNT_PRIVATE_9", allow_excerpt=True)))
        self.assertEqual(status, 200)
        self.assertEqual(result["risk_level"], "high")
        self.assertEqual(result["planner"]["kind"], "GEMINI_PROXY")
        self.assertEqual(len(prompts), 1)
        self.assertNotIn("SYN_", prompts[0])

    def test_leaking_packet_is_rejected_before_planner(self) -> None:
        calls: list[str] = []
        self.start(lambda p: calls.append(p) or answer("MESSAGE_VERIFY", "verify", ["NO_STRONG_SIGNAL"]))
        packet = build_packet("明天下午一起吃飯。")
        packet["redacted_excerpt"] = "請打 0912345678"
        status, result = self.post(encode(packet))
        self.assertEqual(status, 422)
        self.assertEqual(result["error"]["code"], "FORBIDDEN_VALUE_IN_PACKET")
        self.assertEqual(calls, [])

    def test_token_is_enforced_when_configured(self) -> None:
        self.start(None, api_token="s3cret")
        body = encode(build_packet("明天下午一起吃飯。"))
        self.assertEqual(self.post(body)[0], 401)
        self.assertEqual(self.post(body, {"X-Api-Token": "wrong"})[0], 401)
        self.assertEqual(self.post(body, {"X-Api-Token": "s3cret"})[0], 200)

    def test_wrong_media_type_and_oversize(self) -> None:
        self.start()
        self.assertEqual(self.post(b"{}", {"Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.post(b"x" * 20000)[0], 413)

    def test_replay_and_stale_packets_are_rejected_before_planner(self) -> None:
        calls: list[str] = []
        self.start(lambda prompt: calls.append(prompt) or answer("MESSAGE_VERIFY", "verify", ["NO_STRONG_SIGNAL"]))
        packet = build_packet("明天下午一起吃飯。")
        body = encode(packet)
        self.assertEqual(self.post(body)[0], 200)
        status, result = self.post(body)
        self.assertEqual(status, 409)
        self.assertEqual(result["error"]["code"], "PACKET_REPLAYED")
        stale = build_packet("明天下午一起吃飯。", issued_at_ms=int(time.time() * 1000) - 301_000)
        status, result = self.post(encode(stale))
        self.assertEqual(status, 422)
        self.assertEqual(result["error"]["code"], "PACKET_EXPIRED")
        future = build_packet("明天下午一起吃飯。", issued_at_ms=int(time.time() * 1000) + 61_000)
        status, result = self.post(encode(future))
        self.assertEqual(status, 422)
        self.assertEqual(result["error"]["code"], "PACKET_FROM_FUTURE")
        self.assertEqual(len(calls), 1)


if __name__ == "__main__":
    unittest.main()
