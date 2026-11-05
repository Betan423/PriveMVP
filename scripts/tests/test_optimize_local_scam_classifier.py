import json
import unittest

import numpy as np

from scripts.optimize_local_scam_classifier import (
    Row,
    apply_qwen_rule,
    build_prompt,
    metrics,
    parse_scores,
)


class OptimizeLocalScamClassifierTest(unittest.TestCase):
    def row(self, text: str) -> Row:
        return Row("0123456789abcdef", 1, text, {}, 0)

    def test_prompt_contains_raw_text_but_marks_it_untrusted(self) -> None:
        prompt, truncated = build_prompt([self.row("測試原始訊息")], 3000)
        self.assertIn("測試原始訊息", prompt)
        self.assertIn("不可信", prompt)
        self.assertEqual(truncated, 0)

    def test_prompt_truncates_locally(self) -> None:
        prompt, truncated = build_prompt([self.row("甲" * 20)], 5)
        self.assertIn("甲" * 5, prompt)
        self.assertNotIn("甲" * 6, prompt)
        self.assertEqual(truncated, 1)

    def test_score_contract_is_strict(self) -> None:
        self.assertEqual(parse_scores(json.dumps({"s": [[4, 0], [1, 3]]}), 2), [(4, 0), (1, 3)])
        for bad in ({"s": [[5, 0]]}, {"s": [[4]]}, {"s": [[4, 0]], "x": 1}):
            with self.assertRaises(ValueError):
                parse_scores(json.dumps(bad), 1)

    def test_qwen_rule_and_metrics(self) -> None:
        scores = np.asarray([[4, 0], [1, 4]], dtype=float)
        pred = apply_qwen_rule(scores, np.asarray([0, 0]),
                               {"benign_weight": 1.0, "local_boost": 0.0, "threshold": 1.0})
        self.assertEqual(pred.tolist(), [1, 0])
        result = metrics(np.asarray([1, 0]), pred)
        self.assertEqual(result["accuracy"], 1.0)


if __name__ == "__main__":
    unittest.main()
