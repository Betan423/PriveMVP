from __future__ import annotations

import copy
import http.client
import json
import random
import tempfile
import threading
import unittest
from pathlib import Path

from receiver.contracts import ReceiverError
from receiver.core_tasks import (
    build_rescue_packet,
    build_share_packet,
    decide_task,
    interpret,
    merge_plan,
    parse_plan_answer,
    rescue_rule,
    validate_plan_packet,
    volume_band,
)
from receiver.server import PLAN_PATH, create_server

READY = {"recipient_ready": True, "photo_ready": True, "share_app_ready": True,
         "location_removed": True, "private_background_review": False}
CLEAN = {"airplane_mode": "off", "network": "connected", "internet_validated": "yes", "transport": "WIFI",
         "dnd_mode": "off", "ringer_mode": "normal", "ring_volume_band": "normal"}


def environment(**overrides: object) -> dict[str, object]:
    env = {
        "initialization_data": {
            "contacts": [{"contact_id": "SYN_C_1", "relationship": "daughter", "display_name": "SYN_NAME_PRIVATE_1"}],
            "installed_apps": [{"name": "LINE", "installed": True}],
        },
        "continuous_state_data": {
            "photos": [{"photo_id": "SYN_P_1", "exif_location_ref": "SYN_LOC", "background_privacy_hint": "unknown"}],
            "messages": [{"message_id": "SYN_M_1", "text": "請立即轉帳到 SYN_ACCOUNT_PRIVATE_1"}],
            "network": {"connected": True, "transport": "WIFI", "internet_validated": True},
            "system": {"airplane_mode": "on", "dnd_mode": "off", "ringer_mode": "normal",
                       "ring_volume_current": 5, "ring_volume_max": 7},
        },
    }
    env.update(overrides)
    return env


def task(scenario: str, **overrides: object) -> dict[str, object]:
    base = {
        "task_id": "T1", "environment_id": "E1", "scenario_id": scenario, "subscenario": "x",
        "utterance": {"SHARE_PHOTO": "幫我把這張照片傳給女兒", "CHECK_MESSAGE": "這則訊息可信嗎",
                      "RESCUE_PHONE": "手機沒有網路，幫我看看"}[scenario],
        "parameters": {"SHARE_PHOTO": {"recipient_relation": "daughter", "photo_id": "SYN_P_1",
                                       "selection_method": "selected", "app_name": "LINE"},
                       "CHECK_MESSAGE": {"message_id": "SYN_M_1"},
                       "RESCUE_PHONE": {"symptom_category": "connectivity", "requested_scope": "guided_help"}}[scenario],
        "grants": ["voice_command", "recipient_binding", "selected_photo", "share_app_state", "selected_message",
                   "system_status", "network_state", "cloud_features"],
        "privacy_mode": "MINIMIZED_CLOUD", "confirmation": "approved", "cancelled": False,
        "fixture_labels": {"cloud_features_consent": True, "snapshot_age_sec": 10, "selected_resource_missing": False,
                           "recipient_binding_case": "unique"},
    }
    base.update(overrides)
    return base


class InterpreterTests(unittest.TestCase):
    def test_core_intents_and_parameters(self) -> None:
        share = interpret("把最新那張照片分享給孫子")
        self.assertEqual((share.intent, share.relation, share.selection), ("SHARE_PHOTO", "grandson", "latest"))
        self.assertEqual(interpret("把家庭相簿選的照片給孫女").relation, "granddaughter")
        self.assertEqual(interpret("這則要驗證碼的訊息正常嗎").intent, "CHECK_MESSAGE")
        rescue = interpret("我家的 Wi-Fi 好像不能用了")
        self.assertEqual((rescue.intent, rescue.symptom_detail, rescue.requested_scope), ("RESCUE_PHONE", "wifi", "guided_help"))
        self.assertEqual(interpret("鈴聲小到聽不到，怎麼辦").symptom_detail, "low_volume")
        self.assertEqual(interpret("請檢查手機，但暫時不要動設定").requested_scope, "inspect_only")
        self.assertEqual(interpret("不用了，先取消。").intent, "CANCEL")
        self.assertEqual(interpret("今天天氣如何").intent, "UNKNOWN")


class PolicyTests(unittest.TestCase):
    def decide(self, t: dict[str, object], env: dict[str, object] | None = None):
        return decide_task(t, env or environment(), random.Random(1))

    def test_priority_cancel_then_grants_then_ask(self) -> None:
        self.assertEqual(self.decide(task("SHARE_PHOTO", cancelled=True)).reason_codes, ["TASK_CANCELLED"])
        self.assertEqual(self.decide(task("SHARE_PHOTO", grants=["cloud_features"])).reason_codes, ["PURPOSE_GRANT_MISSING"])
        record = self.decide(task("SHARE_PHOTO", grants=["voice_command", "cloud_features"]))
        self.assertEqual(record.decision, "ASK")
        self.assertIsNone(record.packet)
        self.assertEqual(self.decide(task("SHARE_PHOTO", confirmation="rejected")).decision, "STOP")
        self.assertIn("CONFIRMATION_REQUIRED", self.decide(task("SHARE_PHOTO", confirmation="pending")).reason_codes)

    def test_share_ambiguous_or_missing_recipient_asks_without_packet(self) -> None:
        env = environment()
        env["initialization_data"]["contacts"].append({"contact_id": "SYN_C_2", "relationship": "daughter"})
        record = self.decide(task("SHARE_PHOTO"), env)
        self.assertEqual((record.decision, record.packet), ("ASK", None))
        self.assertIn("RECIPIENT_AMBIGUOUS", record.reason_codes)
        params = {"recipient_relation": "doctor", "photo_id": "SYN_P_1", "selection_method": "selected", "app_name": "LINE"}
        self.assertIn("RECIPIENT_NOT_BOUND", self.decide(task("SHARE_PHOTO", parameters=params)).reason_codes)

    def test_share_allow_packet_has_tokens_only(self) -> None:
        record = self.decide(task("SHARE_PHOTO"))
        self.assertEqual(record.decision, "ALLOW")
        text = json.dumps(record.packet)
        self.assertNotIn("SYN_", text)
        self.assertTrue(record.packet["facts"]["location_removed"])
        validate_plan_packet(record.packet)

    def test_strict_local_never_builds_packet(self) -> None:
        for scenario in ("SHARE_PHOTO", "CHECK_MESSAGE", "RESCUE_PHONE"):
            record = self.decide(task(scenario, privacy_mode="STRICT_LOCAL"))
            self.assertIsNone(record.packet, scenario)
            self.assertIn(record.decision, ("LOCAL", "ASK"), scenario)

    def test_rescue_is_local_first_and_rereads_stale_state(self) -> None:
        record = self.decide(task("RESCUE_PHONE"))
        self.assertEqual((record.decision, record.sop_id), ("LOCAL", "RESCUE_AIRPLANE_SETTINGS"))
        labels = {"cloud_features_consent": True, "snapshot_age_sec": 900, "selected_resource_missing": False}
        self.assertEqual(self.decide(task("RESCUE_PHONE", fixture_labels=labels)).reason_codes, ["SNAPSHOT_STALE"])


class RescueRuleTests(unittest.TestCase):
    def test_causes_in_symptom_order(self) -> None:
        self.assertEqual(rescue_rule(dict(CLEAN, airplane_mode="on"), "connectivity", "offline", "guided_help")[0],
                         "RESCUE_AIRPLANE_SETTINGS")
        self.assertEqual(rescue_rule(dict(CLEAN, transport="CELLULAR"), "connectivity", "wifi", "guided_help")[0],
                         "RESCUE_WIFI_SETTINGS")
        both = dict(CLEAN, dnd_mode="on", ring_volume_band="low")
        self.assertEqual(rescue_rule(both, "sound", "no_sound", "guided_help")[0], "RESCUE_DND_SETTINGS")
        self.assertEqual(rescue_rule(both, "sound", "low_volume", "guided_help")[0], "RESCUE_RAISE_VOLUME")

    def test_never_guesses(self) -> None:
        sop, codes = rescue_rule(dict(CLEAN, dnd_mode="unknown"), "sound", "no_sound", "guided_help")
        self.assertEqual((sop, codes), ("RESCUE_ASK_DESCRIBE", ["STATE_UNKNOWN"]))
        self.assertEqual(rescue_rule(CLEAN, "general", "general", "guided_help"), ("RESCUE_NO_FAULT_FOUND", ["NO_FAULT_FOUND"]))
        sop, codes = rescue_rule(dict(CLEAN, dnd_mode="on"), "general", "general", "inspect_only")
        self.assertEqual((sop, codes), ("RESCUE_INSPECT_ONLY", ["DND_ON"]))

    def test_volume_band(self) -> None:
        self.assertEqual([volume_band(0, 7), volume_band(1, 7), volume_band(5, 7), volume_band(25, 15), volume_band(None, 5)],
                         ["zero", "low", "normal", "unknown", "unknown"])


class PacketTests(unittest.TestCase):
    def test_share_contract(self) -> None:
        packet = build_share_packet(READY, "selected", random.Random())
        validate_plan_packet(packet)
        for mutate in (lambda p: p["facts"].update(recipient_ready=False), lambda p: p.update(confirmation="pending"),
                       lambda p: p.update(recipient_token="王小美"), lambda p: p.update(extra=1),
                       lambda p: p["allowed_sops"].append("SEND_NOW"), lambda p: p.update(cloud_consent=False)):
            broken = copy.deepcopy(packet)
            mutate(broken)
            with self.assertRaises(ReceiverError):
                validate_plan_packet(broken)

    def test_rescue_discloses_only_the_symptom_state(self) -> None:
        packet = build_rescue_packet(dict(CLEAN, network="unknown"), "connectivity", "offline", random.Random())
        self.assertNotIn("dnd_mode", packet["facts"])
        validate_plan_packet(packet)
        decisive = build_rescue_packet(dict(CLEAN, network="unknown", airplane_mode="on"), "connectivity", "offline",
                                       random.Random())
        with self.assertRaises(ReceiverError):
            validate_plan_packet(decisive)  # The phone already knows the cause; nothing to ask the cloud.

    def test_aliases_and_tokens_are_fresh(self) -> None:
        rng = random.Random()
        tokens = [build_share_packet(READY, "selected", rng)["recipient_token"] for _ in range(200)]
        self.assertEqual(len(set(tokens)), 200)


class PlanAnswerTests(unittest.TestCase):
    def test_parse_and_merge(self) -> None:
        share = build_share_packet(READY, "latest", random.Random())
        self.assertEqual(parse_plan_answer('{"sop_id":"STOP","reason_codes":[]}', share), ("STOP", []))
        for bad in ('{"sop_id":"SEND_NOW","reason_codes":[]}', 'ok {"sop_id":"STOP","reason_codes":[]}',
                    '{"sop_id":"STOP","reason_codes":["DND_ON"]}', '{"sop_id":"STOP"}'):
            with self.assertRaises(ValueError):
                parse_plan_answer(bad, share)
        rule = ("SHARE_PHOTO_GUIDED", ["CONFIRM_RECIPIENT"])
        self.assertEqual(merge_plan(share, rule, ("ASK_USER", []))[0], "ASK_USER")  # more cautious: accepted
        rescue = build_rescue_packet(dict(CLEAN, dnd_mode="unknown"), "sound", "no_sound", random.Random())
        self.assertEqual(merge_plan(rescue, ("RESCUE_ASK_DESCRIBE", ["STATE_UNKNOWN"]), ("ASK_USER", []))[0],
                         "RESCUE_ASK_DESCRIBE")


class PlanHttpTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.calls: list[str] = []
        self.server = create_server("127.0.0.1", 0, data_dir=self.temp.name,
                                    planner=lambda p: self.calls.append(p) or '{"sop_id":"ASK_USER","reason_codes":[]}')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def post(self, packet: dict[str, object]) -> tuple[int, dict[str, object]]:
        host, port = self.server.server_address[:2]
        connection = http.client.HTTPConnection(host, port, timeout=3)
        connection.request("POST", PLAN_PATH, body=json.dumps(packet).encode(), headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_share_plan_is_made_more_cautious_by_model(self) -> None:
        status, result = self.post(build_share_packet(READY, "selected", random.Random()))
        self.assertEqual((status, result["sop_id"], result["planner"]["kind"]), (200, "ASK_USER", "GEMINI_PROXY"))
        self.assertIn("NEEDS_USER_CHECK", result["reason_codes"])
        audit = (Path(self.temp.name) / "assist_audit.ndjson").read_text()
        self.assertNotIn("CONTACT_", audit)

    def test_leaking_packet_never_reaches_planner(self) -> None:
        packet = build_rescue_packet(dict(CLEAN, network="unknown"), "connectivity", "offline", random.Random())
        packet["facts"]["transport"] = "0912345678"
        status, _ = self.post(packet)
        self.assertEqual(status, 422)
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main()
