from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from receiver.message_eval import run


class MessageEvaluationTests(unittest.TestCase):
    def test_small_fixture_produces_reproducible_evidence(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as temp:
            root = Path(temp)
            dataset_path = root / "dataset.json"
            catalog_path = root / "catalog.json"
            attacks_path = root / "attacks.json"
            mutation_path = root / "mutations.json"
            out = root / "out"
            messages = [
                {"message_id": "M_HIGH", "template_id": "high", "text":
                 "請立即轉帳到 SYN_ACCOUNT_PRIVATE_TEST，否則停權。"},
                {"message_id": "M_SAFE", "template_id": "safe", "text": "明天下午一起吃飯。"},
                {"message_id": "M_SHORT", "template_id": "short", "text":
                 "請處理這件事，細節還沒有說明。"},
            ]
            dataset_path.write_text(json.dumps({
                "phone_environments": [{
                    "environment_id": "E1", "continuous_state_data": {"messages": messages}
                }]
            }, ensure_ascii=False), encoding="utf-8")
            catalog_path.write_text(json.dumps({"message_templates": [
                {"template_id": "high", "semantic_tags": ["transfer", "urgency"]},
                {"template_id": "safe", "semantic_tags": []},
                {"template_id": "short", "semantic_tags": ["insufficient_context"]},
            ]}, ensure_ascii=False), encoding="utf-8")
            attacks_path.write_text(json.dumps({"version": "test", "cases": [{
                "id": "spaced", "text": "請立即匯 款給我", "expected_level": "high",
                "expected_facts": {"asks_transfer": True}, "redaction_contains": [],
                "redaction_excludes": [],
            }]}, ensure_ascii=False), encoding="utf-8")
            mutation_path.write_text(json.dumps({"version": "test", "seeds": [{
                "id": "transfer", "text": "請立即轉帳給我", "target": "轉帳",
                "expected_level": "high", "expected_facts": {"asks_transfer": True},
            }]}, ensure_ascii=False), encoding="utf-8")

            summary = run(dataset_path, catalog_path, attacks_path, out, mutation_path)

            self.assertEqual(summary["messages"], 3)
            self.assertTrue(all(metric["failures"] == 0 for metric in summary["metrics"].values()))
            self.assertEqual(summary["metrics"]["mutation_attack_failure"]["denominator"], 14)
            self.assertEqual(json.loads((out / "failures.json").read_text(encoding="utf-8")), {})
            for name in ("message_results.csv", "attack_results.csv", "mutation_results.csv", "summary.json", "manifest.json"):
                self.assertTrue((out / name).is_file(), name)


if __name__ == "__main__":
    unittest.main()
