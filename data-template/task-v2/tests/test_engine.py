import copy
import csv
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import task_engine as engine


class TaskTests(unittest.TestCase):
    def setUp(self):
        self.rows = [engine.parse(line) for line in (engine.BASE / "examples/tasks.ndjson").read_text(encoding="utf-8").splitlines()]

    def row(self, index):
        return copy.deepcopy(self.rows[index])

    def run_row(self, index):
        return engine.simulate_task(self.row(index))

    def test_all_examples_valid(self):
        for row in self.rows:
            self.assertEqual(engine.validate_task(row), [])

    def test_share_is_not_send_success(self):
        audit = self.run_row(0)
        self.assertEqual(audit["selected_sop"], "SHARE_PHOTO_GUIDED")
        self.assertEqual(audit["task_outcome"], "USER_ACTION_REQUIRED")
        self.assertIsNone(audit["success"])
        self.assertEqual(audit["actual_phone_actions"], 0)

    def test_photo_ambiguity(self):
        self.assertEqual(self.run_row(1)["selected_sop"], "ASK_USER")

    def test_photo_missing_grant_stops_cloud(self):
        audit = self.run_row(2)
        self.assertEqual(audit["task_outcome"], "BLOCKED")
        self.assertIsNone(audit["planner_packet"])
        self.assertNotIn("selected_photo", audit["accessed_data"])

    def test_denial_before_resource_access(self):
        audit = self.run_row(3)
        self.assertEqual(audit["accessed_data"], [])
        self.assertIsNone(audit["planner_packet"])

    def test_cancel_before_resource_access(self):
        row = self.row(0)
        row["cancelled"] = True
        audit = engine.simulate_task(row)
        self.assertEqual(audit["task_outcome"], "CANCELLED")
        self.assertEqual(audit["accessed_data"], [])
        self.assertEqual(audit["candidate_sop_ids"], [])
        self.assertIsNone(audit["planner_packet"])

    def test_photo_pending_no_open_share(self):
        row = self.row(0)
        row["confirmation"] = "pending"
        audit = engine.simulate_task(row)
        self.assertEqual(audit["symbolic_action"], "ASK_CONFIRM")
        self.assertNotIn("SHARE_PHOTO_GUIDED", audit["candidate_sop_ids"])

    def test_ui_mismatch_stops(self):
        row = self.row(0)
        row["local_context"]["ui_state"] = "unknown"
        audit = engine.simulate_task(row)
        self.assertEqual(audit["task_outcome"], "BLOCKED")
        self.assertIsNone(audit["planner_packet"])

    def test_app_missing_manual(self):
        row = self.row(0)
        row["local_context"]["share_app"] = "unavailable"
        self.assertEqual(engine.simulate_task(row)["selected_sop"], "PHOTO_HELP_MANUAL")

    def test_photo_missing_or_ambiguous(self):
        for state in ("missing", "ambiguous"):
            row = self.row(0)
            row["local_context"]["photo_state"] = state
            self.assertEqual(engine.simulate_task(row)["selected_sop"], "ASK_USER")

    def test_message_risk_is_caution_not_verdict(self):
        audit = self.run_row(4)
        self.assertEqual(audit["selected_sop"], "MESSAGE_CAUTION")
        self.assertEqual(audit["reason"], "RISK_SIGNAL_NOT_VERDICT")
        self.assertIsNone(audit["success"])

    def test_message_no_signal_not_safe(self):
        audit = self.run_row(5)
        self.assertEqual(audit["selected_sop"], "MESSAGE_REVIEW")
        self.assertEqual(audit["reason"], "NO_SIGNAL_DOES_NOT_MEAN_SAFE")

    def test_message_insufficient(self):
        self.assertEqual(self.run_row(6)["selected_sop"], "ASK_USER")

    def test_message_missing_never_releases_stale_features(self):
        row = self.row(4)
        row["local_context"]["message_state"] = "missing"
        audit = engine.simulate_task(row)
        self.assertEqual(audit["selected_sop"], "ASK_USER")
        self.assertIsNone(audit["planner_packet"])

    def test_cloud_opt_out_keeps_local_function(self):
        audit = self.run_row(7)
        self.assertTrue(audit["cloud_opt_out_fallback"])
        self.assertEqual(audit["selected_sop"], "MESSAGE_CAUTION")
        self.assertIsNone(audit["planner_packet"])

    def test_airplane_route(self):
        self.assertEqual(self.run_row(8)["selected_sop"], "OPEN_AIRPLANE_SETTINGS")

    def test_dnd_pending_not_changed(self):
        audit = self.run_row(9)
        self.assertEqual(audit["selected_sop"], "OPEN_DND_SETTINGS")
        self.assertEqual(audit["symbolic_action"], "ASK_CONFIRM")
        self.assertIsNone(audit["success"])

    def test_volume_zero_route(self):
        self.assertEqual(self.run_row(10)["selected_sop"], "OPEN_SOUND_SETTINGS")

    def test_status_unknown_manual(self):
        audit = self.run_row(11)
        self.assertEqual(audit["selected_sop"], "PHONE_HELP_MANUAL")
        self.assertEqual(audit["reason"], "STATUS_UNKNOWN")

    def test_offline_not_inferred_as_airplane(self):
        row = self.row(8)
        row["local_context"]["airplane_mode"] = "off"
        self.assertEqual(engine.simulate_task(row)["selected_sop"], "OPEN_NETWORK_SETTINGS")

    def test_normal_status_not_hardware_verdict(self):
        row = self.row(8)
        row["local_context"].update(airplane_mode="off", network_connected=True)
        self.assertEqual(engine.simulate_task(row)["reason"], "NO_LISTED_SETTING_ISSUE_FOUND")

    def test_multiple_issues_only_first_guidance_not_success(self):
        row = self.row(8)
        row["local_context"].update(dnd_mode="on", ring_volume_band="zero")
        audit = engine.simulate_task(row)
        self.assertEqual(audit["selected_sop"], "OPEN_AIRPLANE_SETTINGS")
        self.assertIsNone(audit["success"])

    def test_strict_local_even_with_cloud_grant(self):
        row = self.row(0)
        row["privacy_mode"] = "STRICT_LOCAL"
        self.assertIsNone(engine.simulate_task(row)["planner_packet"])

    def test_task_scopes_not_all_granted_scopes(self):
        row = self.row(0)
        row["grants"].append("selected_message")
        self.assertNotIn("selected_message", engine.simulate_task(row)["accessed_data"])

    def test_every_scenario_denied_source_and_voice_grants(self):
        for index in (0, 4, 8):
            row = self.row(index)
            for required in engine.POLICY["required_data"][row["scenario_id"]]:
                denied = copy.deepcopy(row)
                denied["grants"].remove(required)
                audit = engine.simulate_task(denied)
                self.assertEqual(audit["task_outcome"], "BLOCKED")
                self.assertIsNone(audit["planner_packet"])
                self.assertNotIn(required, audit["accessed_data"])

    def test_cloud_packet_no_stable_id_or_raw_command(self):
        for row in self.rows:
            packet = engine.simulate_task(row)["planner_packet"]
            if packet:
                self.assertEqual(set(packet), {"intent", "candidate_sop_ids", "facts"})
                self.assertEqual(engine.direct_private_count(packet["facts"]), 0)
                serialized = json.dumps(packet, ensure_ascii=False)
                self.assertNotIn(row["user_id"], serialized)
                self.assertNotIn(row["task_id"], serialized)
                self.assertNotIn(row["utterance"], serialized)

    def test_stage_access_photo(self):
        stages = self.run_row(0)["stage_access"]
        self.assertEqual(stages["INIT"], ["recipient_binding"])
        self.assertIn("selected_photo", stages["TASK"])

    def test_model_response_closed_contract(self):
        audit = self.run_row(0)
        self.assertTrue(engine.valid_planner_response({"sop_id": "SHARE_PHOTO_GUIDED"}, audit))
        self.assertFalse(engine.valid_planner_response({"sop_id": "OPEN_SOUND_SETTINGS"}, audit))
        self.assertFalse(engine.valid_planner_response({"sop_id": "SHARE_PHOTO_GUIDED", "code": "BAD"}, audit))
        self.assertFalse(engine.valid_planner_response({"sop_id": []}, audit))
        self.assertFalse(engine.valid_planner_response({"sop_id": "SHARE_PHOTO_GUIDED"}, self.run_row(2)))

    def test_private_fields_rejected(self):
        row = self.row(0)
        row["local_context"]["recipient_phone"] = "PRIVATE_SENTINEL"
        self.assertTrue(engine.validate_task(row))
        with self.assertRaises(ValueError):
            engine.simulate_task(row)

    def test_free_utterance_rejected(self):
        row = self.row(0)
        row["utterance"] = "PRIVATE_SENTINEL"
        self.assertTrue(engine.validate_task(row))

    def test_utterance_feature_conflict(self):
        row = self.row(4)
        row["local_context"]["features"]["asks_transfer"] = False
        self.assertTrue(engine.validate_task(row))

    def test_identifier_newline_and_true_number(self):
        row = self.row(0)
        row["task_id"] += "\n"
        self.assertTrue(engine.validate_task(row))
        row = self.row(0)
        row["synthetic"] = 1
        self.assertTrue(engine.validate_task(row))

    def test_duplicate_grants_and_unknown_grant(self):
        row = self.row(0)
        row["grants"].append("voice_command")
        self.assertTrue(engine.validate_task(row))
        row["grants"] = ["all_phone_data"]
        self.assertTrue(engine.validate_task(row))

    def test_non_finite_and_duplicate_json_keys(self):
        for text in ('{"synthetic":true,"synthetic":false}', '{"x":NaN}'):
            with self.assertRaises(ValueError):
                engine.parse(text)

    def test_invalid_input_no_outputs_and_no_echo(self):
        with tempfile.TemporaryDirectory(dir=engine.BASE) as tmp, redirect_stdout(io.StringIO()) as stdout:
            source = Path(tmp) / "invalid.ndjson"
            target = Path(tmp) / "out"
            source.write_text('{"message_text":"PRIVATE_SENTINEL"}\n', encoding="utf-8")
            self.assertEqual(engine.main([str(source), "--output", str(target)]), 1)
            self.assertFalse(target.exists())
            self.assertNotIn("PRIVATE_SENTINEL", stdout.getvalue())

    def test_duplicate_tasks_and_coverage(self):
        with tempfile.TemporaryDirectory(dir=engine.BASE) as tmp:
            source = Path(tmp) / "input.ndjson"
            source.write_text('\n'.join(json.dumps(row) for row in [self.rows[0], self.rows[0]]) + '\n', encoding="utf-8")
            _, report = engine.load_dataset(source, True)
            self.assertFalse(report["passed"])
            self.assertEqual(report["error_count"], 2)

    def test_export_roundtrip_and_no_overwrite(self):
        with tempfile.TemporaryDirectory(dir=engine.BASE) as tmp:
            target = Path(tmp) / "out"
            audits = [engine.simulate_task(row) for row in self.rows]
            report = engine.write_outputs(audits, target, {"passed": True})
            self.assertEqual(report["mock_tasks"], 12)
            self.assertEqual(report["actual_network_requests"], 0)
            self.assertEqual(report["matched_cloud_comparison_tasks"], 6)
            self.assertEqual(report["matched_cloud_totals"]["baseline_context_values"], 51)
            self.assertEqual(report["matched_cloud_totals"]["minimized_context_values"], 35)
            with (target / "task_audit.csv").open(encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(len(rows), 12)
            self.assertTrue(all(row["success"] == "null" for row in rows))
            self.assertTrue(all(row["sensitive_data_count"] == "0" for row in rows))
            with self.assertRaises(FileExistsError):
                engine.write_outputs(audits, target, {})

    def test_cli_existing_output_preserved(self):
        with tempfile.TemporaryDirectory(dir=engine.BASE) as tmp, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(engine.main([str(engine.BASE / "examples/tasks.ndjson"), "--output", tmp]), 2)


if __name__ == "__main__":
    unittest.main()
