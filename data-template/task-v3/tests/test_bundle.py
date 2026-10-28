import copy
import csv
import json
import tempfile
import unittest
from pathlib import Path
import bundle_engine as engine


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.source = json.loads((engine.BASE / "examples/source.json").read_text(encoding="utf-8"))
        self.env = self.source["phone_environments"][0]
        self.state = self.env["continuous_state_data"]
        self.tasks = self.source["task_requests"]

    def audit(self, index=0):
        engine.validate(self.source)
        return engine.derive(self.tasks[index], self.env)["part_2_derived"]

    def invalid(self):
        with self.assertRaises(ValueError):
            engine.validate(self.source)

    def test_full_example(self):
        self.assertEqual(len(engine.validate(self.source)), 1)
        self.assertEqual(self.audit()["selected_sop"], "SHARE_PHOTO_GUIDED")

    def test_message_text_derivation(self):
        facts = self.audit(1)["planner_packet"]["facts"]
        self.assertTrue(all(facts.values()))
        self.assertEqual(self.audit(1)["selected_sop"], "MESSAGE_CAUTION")

    def test_no_signal_not_safe(self):
        self.tasks[1]["parameters"]["message_id"] = "SYN_M002"
        self.assertEqual(self.audit(1)["selected_sop"], "MESSAGE_REVIEW")
        self.assertIsNone(self.audit(1)["success"])

    def test_dnd_before_sound(self):
        self.assertEqual(self.audit(2)["selected_sop"], "OPEN_DND_SETTINGS")
        self.assertEqual(self.audit(2)["task_outcome"], "WAIT_CONFIRM")

    def test_airplane_priority(self):
        self.state["system"]["airplane_mode"] = "on"
        self.assertEqual(self.audit(2)["selected_sop"], "OPEN_AIRPLANE_SETTINGS")

    def test_sound(self):
        self.state["system"]["dnd_mode"] = "off"
        self.assertEqual(self.audit(2)["selected_sop"], "OPEN_SOUND_SETTINGS")

    def test_unknown_volume(self):
        self.state["system"].update(dnd_mode="off", ring_volume_current=None)
        self.assertEqual(self.audit(2)["selected_sop"], "PHONE_HELP_MANUAL")

    def test_missing_photo(self):
        self.tasks[0]["parameters"]["photo_id"] = None
        self.assertEqual(self.audit()["selected_sop"], "ASK_USER")

    def test_ambiguous_recipient(self):
        extra = copy.deepcopy(self.env["initialization_data"]["contacts"][0])
        extra["contact_id"] = "SYN_C003"
        self.env["initialization_data"]["contacts"].append(extra)
        self.assertEqual(self.audit()["selected_sop"], "ASK_USER")

    def test_missing_binding(self):
        self.env["initialization_data"]["contacts"][0]["line_ref"] = None
        self.assertEqual(self.audit()["selected_sop"], "ASK_USER")

    def test_missing_message(self):
        self.tasks[1]["parameters"]["message_id"] = None
        self.assertIsNone(self.audit(1)["planner_packet"])

    def test_missing_purpose_grant(self):
        self.tasks[0]["grants"].remove("selected_photo")
        self.assertEqual(self.audit()["task_outcome"], "BLOCKED")
        self.assertIsNone(self.audit()["planner_packet"])

    def test_platform_permission_missing(self):
        self.state["permissions"]["media_images"] = "unknown"
        audit = self.audit()
        self.assertEqual(audit["reason"], "SIMULATED_PLATFORM_PERMISSION_UNAVAILABLE")
        self.assertIsNone(audit["planner_packet"])
        self.assertEqual(audit["accessed_data"], [])

    def test_cancelled(self):
        self.tasks[0]["cancelled"] = True
        self.assertEqual(self.audit()["task_outcome"], "CANCELLED")
        self.assertIsNone(self.audit()["planner_packet"])

    def test_denied(self):
        self.tasks[0]["confirmation"] = "denied"
        self.assertIsNone(self.audit()["planner_packet"])

    def test_local_only(self):
        self.tasks[0]["privacy_mode"] = "STRICT_LOCAL"
        self.assertIsNone(self.audit()["planner_packet"])

    def test_cloud_opt_out(self):
        self.tasks[0]["grants"].remove("cloud_features")
        self.assertIsNone(self.audit()["planner_packet"])
        self.assertTrue(self.audit()["cloud_opt_out_fallback"])

    def test_no_private_values_in_derived(self):
        for i in range(3):
            serialized = json.dumps(self.audit(i), ensure_ascii=False)
            for secret in ("SYN_PHONE_PRIVATE001", "SYN_LINE_PRIVATE001", "SYN_MEDIA_PRIVATE001", "SYN_WIFI_PRIVATE001", "SYN_CALENDAR_PRIVATE001", "SYN_LOCATION_PRIVATE001", "SYN_SCREEN_PRIVATE001", "SYN_U001", engine.TEXTS[0], self.tasks[i]["utterance"]):
                self.assertNotIn(secret, serialized)

    def test_bad_source_tag(self):
        self.source["synthetic"] = False
        self.invalid()

    def test_real_name_rejected(self):
        self.env["initialization_data"]["contacts"][0]["display_name"] = "真人名稱"
        self.invalid()

    def test_real_text_rejected(self):
        self.state["messages"][0]["text"] = "使用者真實私訊"
        self.invalid()

    def test_raw_injection_rejected(self):
        self.state["raw_events"] = "secret"
        self.invalid()

    def test_no_pixels(self):
        self.state["photos"][0]["pixels_available"] = True
        self.invalid()

    def test_no_real_location(self):
        self.state["location"]["latitude"] = 25
        self.invalid()

    def test_user_reference(self):
        self.tasks[0]["user_id"] = "SYN_U002"
        self.invalid()

    def test_cross_environment_resource(self):
        second = copy.deepcopy(self.env)
        second["environment_id"] = "SYN_ENV0002"
        second["continuous_state_data"]["photos"][0]["photo_id"] = "SYN_P999"
        self.source["phone_environments"].append(second)
        self.tasks[0]["parameters"]["photo_id"] = "SYN_P999"
        self.invalid()

    def test_duplicate_task(self):
        self.tasks.append(copy.deepcopy(self.tasks[0]))
        self.invalid()

    def test_duplicate_resource(self):
        self.state["photos"].append(copy.deepcopy(self.state["photos"][0]))
        self.invalid()

    def test_task_before_snapshot(self):
        self.tasks[0]["requested_offset_sec"] = 599
        self.invalid()

    def test_future_photo(self):
        self.state["photos"][0]["taken_offset_sec"] = 601
        self.invalid()

    def test_notification_reference(self):
        self.state["notifications"][0]["message_id"] = "SYN_M999"
        self.invalid()

    def test_network_conflict(self):
        self.state["network"]["connected"] = False
        self.invalid()

    def test_volume_conflict(self):
        self.state["system"]["ring_volume_current"] = 16
        self.invalid()

    def test_boolean_not_integer(self):
        self.env["captured_offset_sec"] = True
        self.invalid()

    def test_feature_injection(self):
        self.tasks[1]["features"] = {"safe": True}
        self.invalid()

    def test_json_duplicate_keys(self):
        with self.assertRaises(ValueError):
            engine.v2.parse('{"a":1,"a":2}')

    def test_json_nonfinite(self):
        with self.assertRaises(ValueError):
            engine.v2.parse('{"a":NaN}')

    def test_csv_and_pairs(self):
        with tempfile.TemporaryDirectory(dir=engine.BASE) as temp:
            output = Path(temp) / "result"
            report = engine.generate(self.source, output)
            self.assertEqual(report["task_count"], 3)
            self.assertEqual(report["matched_cloud_tasks"], 3)
            with (output / "phone_environments.csv").open(encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle))
            contacts = json.loads(rows[0]["initialization_data.contacts"])
            self.assertEqual(contacts[0]["contact_id"], "SYN_C001")
            pairs = json.loads((output / "pairs.json").read_text(encoding="utf-8"))
            self.assertEqual(pairs[0]["part_1_original"]["phone_environment"], self.env)
            with self.assertRaises(ValueError):
                engine.generate(self.source, output)

    def test_validation_failure_no_output(self):
        with tempfile.TemporaryDirectory(dir=engine.BASE) as temp:
            output = Path(temp) / "result"
            self.tasks[0]["parameters"]["photo_id"] = "SYN_P999"
            with self.assertRaises(ValueError):
                engine.generate(self.source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
