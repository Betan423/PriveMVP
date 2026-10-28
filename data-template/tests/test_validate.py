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
import validate


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.rows = [json.loads(line) for line in (validate.BASE / "examples/sample.ndjson").read_text(encoding="utf-8").splitlines()]

    def run_rows(self, rows=None, **kwargs):
        return validate.validate_lines([json.dumps(row) for row in self.rows if row is not None] if rows is None else [json.dumps(row) for row in rows], **kwargs)[0]

    def assert_code(self, report, code):
        self.assertFalse(report["passed"])
        self.assertIn(code, [item["code"] for item in report["errors"]])

    def test_sample_passes(self):
        self.assertTrue(self.run_rows()["passed"])

    def test_extra_private_field_rejected_without_echo(self):
        self.rows[0]["payload"]["text"] = "PRIVATE_SENTINEL"
        report = self.run_rows()
        self.assert_code(report, "schema_additionalProperties")
        self.assertNotIn("PRIVATE_SENTINEL", json.dumps(report))

    def test_real_identifier_rejected(self):
        self.rows[0]["user_id"] = "REAL_IDENTIFIER"
        self.assert_code(self.run_rows(), "schema_pattern")

    def test_identifier_newline_rejected(self):
        self.rows[0]["user_id"] += "\n"
        self.assert_code(self.run_rows(), "schema_pattern")

    def test_duplicate_id(self):
        self.rows[1]["event_id"] = self.rows[0]["event_id"]
        self.assert_code(self.run_rows(), "duplicate_event_id")

    def test_boolean_not_integer(self):
        self.rows[0]["offset_sec"] = True
        self.assert_code(self.run_rows(), "schema_type")

    def test_numeric_true_not_boolean_true(self):
        self.rows[0]["synthetic"] = 1
        self.assert_code(self.run_rows(), "schema_const")

    def test_wrong_payload_type(self):
        self.rows[0]["payload"] = []
        self.assert_code(self.run_rows(), "schema_type")

    def test_overlap(self):
        self.rows[5]["offset_sec"] = 30200
        self.rows.sort(key=lambda row: row["offset_sec"])
        self.assert_code(self.run_rows(), "usage_intervals_overlap")

    def test_cross_day(self):
        self.rows[7]["offset_sec"] = 86350
        self.rows.sort(key=lambda row: row["offset_sec"])
        self.assert_code(self.run_rows(), "usage_crosses_day")

    def test_unsorted(self):
        self.rows[1], self.rows[2] = self.rows[2], self.rows[1]
        self.assert_code(self.run_rows(), "user_timeline_not_sorted")

    def test_redaction_semantics(self):
        self.rows[6]["payload"]["message_length_band"] = "short"
        self.assert_code(self.run_rows(), "redacted_length_not_unknown")

    def test_offline_semantics(self):
        self.rows[-1]["payload"]["internet_available"] = True
        self.assert_code(self.run_rows(), "offline_internet_inconsistent")

    def test_unknown_network(self):
        self.rows[-1]["payload"]["network_class"] = "unknown"
        self.assert_code(self.run_rows(), "unknown_network_requires_null")

    def test_missing_state(self):
        self.rows.pop(0)
        self.assert_code(self.run_rows(), "scenario_device_state_count")

    def test_wrong_state_time(self):
        self.rows[0]["offset_sec"] += 1
        self.assert_code(self.run_rows(), "device_state_offsets")

    def test_empty(self):
        self.assert_code(self.run_rows([]), "empty_dataset")

    def test_duplicate_json_keys(self):
        report, rows = validate.validate_lines(['{"synthetic":true,"synthetic":false}'])
        self.assert_code(report, "invalid_json_or_duplicate_key")
        self.assertEqual(rows, [])

    def test_nan(self):
        report, _ = validate.validate_lines(['{"offset_sec":NaN}'])
        self.assert_code(report, "invalid_json_or_duplicate_key")

    def test_blank_line(self):
        report, _ = validate.validate_lines([''])
        self.assert_code(report, "blank_line")

    def test_coverage_expected_users_and_days(self):
        self.assert_code(self.run_rows(require_coverage=True), "scenario_coverage_incomplete")
        self.assert_code(self.run_rows(expected_users=6), "expected_user_count")
        self.assert_code(self.run_rows(expected_days=2), "expected_days_or_day_indices")

    def test_changed_scenario(self):
        self.rows[1]["scenario_id"] = "low_activity"
        self.assert_code(self.run_rows(), "user_scenario_changed")

    def test_default_batch_missing_users(self):
        self.assert_code(self.run_rows(default_batch=True), "default_batch_user_assignment")

    def test_burst_and_low_activity(self):
        burst = copy.deepcopy(self.rows)
        for row in burst:
            row["scenario_id"] = "notification_burst"
            if row["event_type"] == "notification":
                row["offset_sec"] = 50000 + int(row["event_id"][-2:]) * 10
        reference = copy.deepcopy(burst[1])
        for index in range(8):
            notification = copy.deepcopy(reference)
            notification["event_id"] = f"SYN_E{100 + index:06d}"
            notification["offset_sec"] = 50120 + index * 10
            burst.append(notification)
        burst.sort(key=lambda row: row["offset_sec"])
        self.assertTrue(self.run_rows(burst)["passed"])
        notifications = [row for row in burst if row["event_type"] == "notification"]
        for index, row in enumerate(notifications):
            row["offset_sec"] = 10000 + index * 1000
        burst.sort(key=lambda row: row["offset_sec"])
        self.assert_code(self.run_rows(burst), "notification_burst_window_missing")
        low = [copy.deepcopy(row) for row in self.rows if row["event_type"] == "device_state" or row["event_id"] == "SYN_E000003"]
        for row in low:
            row["scenario_id"] = "low_activity"
        self.assertTrue(self.run_rows(low)["passed"])

    def test_full_six_user_batch(self):
        complete = []
        counter = 1
        for user in range(1, 7):
            scenario = "routine" if user <= 2 else "notification_burst" if user <= 4 else "low_activity"
            group = copy.deepcopy(self.rows)
            if scenario == "low_activity":
                group = [row for row in group if row["event_type"] == "device_state" or row["event_id"] == "SYN_E000003"]
            elif scenario == "notification_burst":
                notifications = [row for row in group if row["event_type"] == "notification"]
                for index in range(8):
                    group.append(copy.deepcopy(notifications[0]))
                for index, row in enumerate(row for row in group if row["event_type"] == "notification"):
                    row["offset_sec"] = 50000 + index * 20
            group.sort(key=lambda row: row["offset_sec"])
            for row in group:
                row["user_id"] = f"SYN_U{user:03d}"
                row["scenario_id"] = scenario
                row["event_id"] = f"SYN_E{counter:06d}"
                counter += 1
            complete.extend(group)
        self.assertTrue(self.run_rows(complete, require_coverage=True, expected_users=6, expected_days=1, default_batch=True)["passed"])
        for row in complete:
            if row["user_id"] == "SYN_U006":
                row["user_id"] = "SYN_U007"
        self.assert_code(self.run_rows(complete, default_batch=True), "default_batch_user_assignment")

    def test_csv_utf8_roundtrip(self):
        with tempfile.TemporaryDirectory(dir=validate.BASE) as tmp:
            path = Path(tmp) / "sample.csv"
            rows = copy.deepcopy(self.rows)
            rows[-1]["payload"].update(network_class="unknown", internet_available=None)
            validate.export_csv(rows, path)
            with path.open(encoding="utf-8", newline="") as handle:
                exported = list(csv.DictReader(handle))
            self.assertEqual(len(exported), 10)
            self.assertEqual(exported[0]["synthetic"], "true")
            self.assertEqual(exported[0]["duration_sec"], "")
            self.assertEqual(exported[-1]["internet_available"], "null")
            with self.assertRaises(FileExistsError):
                validate.export_csv(rows, path)

    def test_invalid_does_not_export(self):
        with tempfile.TemporaryDirectory(dir=validate.BASE) as tmp, redirect_stdout(io.StringIO()):
            folder = Path(tmp)
            source, target = folder / "bad.ndjson", folder / "out.csv"
            source.write_text('{"text":"PRIVATE_SENTINEL"}\n', encoding="utf-8")
            self.assertEqual(validate.main([str(source), "--csv", str(target)]), 1)
            self.assertFalse(target.exists())

    def test_output_collision_and_overwrite(self):
        with tempfile.TemporaryDirectory(dir=validate.BASE) as tmp, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            target = Path(tmp) / "existing.txt"
            target.write_text("preserve", encoding="utf-8")
            source = validate.BASE / "examples/sample.ndjson"
            self.assertEqual(validate.main([str(source), "--csv", str(target)]), 2)
            self.assertEqual(target.read_text(encoding="utf-8"), "preserve")
            self.assertEqual(validate.main([str(source), "--report", str(source)]), 2)
            new_path = Path(tmp) / "same.json"
            self.assertEqual(validate.main([str(source), "--report", str(new_path), "--csv", str(new_path)]), 2)
            self.assertFalse(new_path.exists())


if __name__ == "__main__":
    unittest.main()
