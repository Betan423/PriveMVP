"""Scenario 1 Raw / Masked / Task-Minimal comparison: structure and privacy invariants."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from receiver import share_photo_compare as spc

DATASET = Path(__file__).resolve().parents[2] / "dataset/AI金孫_合成原始資料集_v5_廣度擴充/dataset/source_raw_v5.json"


class SharePhotoCompareTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not DATASET.exists():
            raise unittest.SkipTest("v5 dataset not checked out")
        data = json.loads(DATASET.read_text(encoding="utf-8"))
        # Two environments and their SHARE_PHOTO tasks keep the test fast but real.
        envs = data["phone_environments"][:2]
        keep = {e["environment_id"] for e in envs}
        tasks = [t for t in data["task_requests"] if t["scenario_id"] == "SHARE_PHOTO" and t["environment_id"] in keep]
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dataset = Path(cls.tmp.name) / "mini.json"
        cls.dataset.write_text(json.dumps({"phone_environments": envs, "task_requests": tasks}, ensure_ascii=False), encoding="utf-8")
        cls.out = Path(cls.tmp.name) / "out"
        cls.summary = spc.run(cls.dataset, cls.out, seed=1)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.tmp.cleanup()

    def test_every_task_has_one_row_and_three_versions(self) -> None:
        rows = [json.loads(line) for line in (self.out / "pairs.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(rows), self.summary["tasks"])
        for row in rows:
            for version in spc.VERSIONS:
                self.assertIn(version, row)

    def test_task_minimal_never_carries_identifiers_or_non_task_sections(self) -> None:
        minimal = self.summary["versions"]["task_minimal"]
        self.assertEqual(minimal["identifier_values_total"], 0)
        self.assertEqual(minimal["non_task_sections_per_output"], 0.0)
        self.assertEqual(minimal["cross_task_linkable_outputs"], 0)

    def test_masking_hides_values_but_not_structure(self) -> None:
        raw, masked = self.summary["versions"]["raw"], self.summary["versions"]["masked"]
        self.assertGreater(raw["identifier_values_total"], 0)
        self.assertEqual(masked["identifier_values_total"], 0)
        # Same leaf count: masking replaces values, it drops nothing.
        self.assertEqual(raw["mean_leaves_per_task"], masked["mean_leaves_per_task"])
        self.assertEqual(masked["non_task_sections_per_output"], raw["non_task_sections_per_output"])
        self.assertEqual(masked["cross_task_linkable_outputs"], masked["tasks_with_output"])

    def test_allowed_packets_keep_every_necessary_condition(self) -> None:
        allowed = self.summary["versions"]["task_minimal"]["tasks_with_output"]
        for key, value in self.summary["task_minimal_fidelity_over_allowed"].items():
            self.assertEqual(value, f"{allowed}/{allowed}", key)

    def test_task_minimal_is_much_smaller_than_masked(self) -> None:
        masked = self.summary["versions"]["masked"]["mean_leaves_per_output"]
        minimal = self.summary["versions"]["task_minimal"]["mean_leaves_per_output"]
        self.assertLess(minimal * 10, masked)

    def test_manifest_hashes_outputs(self) -> None:
        manifest = json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(set(manifest["outputs"]), {"comparison.json", "pairs.jsonl"})


if __name__ == "__main__":
    unittest.main()
