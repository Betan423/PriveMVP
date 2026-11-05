"""Scenario 2 inference attack: the attacker view, the folds and the invariants the packet design promises."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from receiver import inference_attack_eval as iae

ROOT = Path(__file__).resolve().parents[2] / "dataset/AI金孫_合成原始資料集_v5_廣度擴充"
DATASET = ROOT / "dataset/source_raw_v5.json"
CATALOG = ROOT / "template_catalog.json"


class InferenceAttackEvalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not DATASET.exists():
            raise unittest.SkipTest("v5 dataset not checked out")
        data = json.loads(DATASET.read_text(encoding="utf-8"))
        # Eight environments: two per fold, 256 messages, every family present.
        envs = data["phone_environments"][:8]
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dataset = Path(cls.tmp.name) / "mini.json"
        cls.dataset.write_text(json.dumps({"phone_environments": envs, "task_requests": []}, ensure_ascii=False),
                               encoding="utf-8")
        cls.out = Path(cls.tmp.name) / "out"
        cls.result = iae.run(cls.dataset, CATALOG, cls.out)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.tmp.cleanup()

    def _attack(self, view: str, target: str) -> dict:
        return next(a for a in self.result["inference_attacks"] if a["view"] == view and a["target"] == target)

    def test_attacker_sees_only_packet_facts(self) -> None:
        facts = json.loads(iae._signature(iae.extract_facts("虛構測試內容：明天下午一起吃飯。", "notification")))
        self.assertEqual(len(facts), self.result["fact_fields"])
        self.assertNotIn("text", facts)
        self.assertNotIn("redacted_excerpt", facts)

    def test_packet_carries_no_stable_identifier(self) -> None:
        fields = self.result["packet_fields"]
        self.assertEqual(fields["stable_identifier_fields"], [])
        self.assertEqual(fields["fields_varying_with_message"], ["facts"])
        self.assertIn("task_id_alias", fields["fields_random_per_send"])
        self.assertIn("request_nonce", fields["fields_random_per_send"])

    def test_facts_recover_risk_level_but_not_every_text(self) -> None:
        level = self._attack("facts_only", "expected_level")
        self.assertEqual(level["accuracy"], 1.0)
        text = self._attack("facts_only", "text")
        self.assertLess(text["accuracy"], 0.5)
        family = self._attack("facts_only", "template_id")
        self.assertGreater(family["accuracy"], family["majority_class_baseline"])
        self.assertLess(family["accuracy"], 1.0)
        self.assertEqual(family["unseen_signatures_in_test"], 0)

    def test_excerpt_is_the_real_exposure(self) -> None:
        facts_only = self._attack("facts_only", "template_id")["accuracy"]
        with_excerpt = self._attack("facts_and_excerpt", "template_id")["accuracy"]
        self.assertGreater(with_excerpt, facts_only)
        # High-risk packets never carry an excerpt, so the excerpt view gains nothing there.
        high_facts = self._attack("facts_only", "text")["accuracy_by_expected_level"]["high"]
        high_excerpt = self._attack("facts_and_excerpt", "text")["accuracy_by_expected_level"]["high"]
        self.assertEqual(high_facts, high_excerpt)

    def test_no_phone_is_uniquely_identified(self) -> None:
        for view in iae.VIEWS:
            acc = self.result["cross_message_accumulation"][view]
            self.assertEqual(acc["environments_uniquely_identified"], 0)
            self.assertGreaterEqual(acc["min_environment_k"], 2)

    def test_equivalence_classes_are_consistent(self) -> None:
        classes = self.result["equivalence_classes"]["facts_only"]
        # 8 environments give small classes; the full run asserts k >= 64 in its doc, not here.
        self.assertGreaterEqual(classes["min_k"], 1)
        self.assertLessEqual(classes["family_entropy_given_signature_bits"], classes["family_entropy_bits"])
        rows = (self.out / "classes.csv").read_text(encoding="utf-8").splitlines()[1:]
        facts_rows = [r for r in rows if r.startswith("facts_only,")]
        self.assertEqual(len(facts_rows), classes["classes"])

    def test_manifest_hashes_outputs(self) -> None:
        manifest = json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(set(manifest["outputs"]), {"inference_attack.json", "classes.csv"})
        self.assertEqual(len(manifest["inputs"]), 2)


if __name__ == "__main__":
    unittest.main()
