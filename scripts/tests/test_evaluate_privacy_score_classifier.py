import json
import unittest

from receiver.assistant import rule_assessment
from receiver.phone_sim import extract_facts
from scripts.evaluate_privacy_score_classifier import (
    Row,
    build_messages,
    parse_scores,
    predictions,
    select_config,
)


class PrivacyScoreEvaluationTest(unittest.TestCase):
    def row(self, text: str, label: int, row_id: str) -> Row:
        facts = extract_facts(text, "pasted")
        level, _ = rule_assessment(facts)
        return Row(row_id, label, text, facts, int(level == "high"))

    def test_prompt_has_redacted_excerpt_and_role_separation(self) -> None:
        row = self.row("王小明您好，請查看 https://www.gov.tw 公告", 0, "safe")
        messages = build_messages([row], include_excerpt=True)
        self.assertEqual([message["role"] for message in messages], ["system", "user"])
        self.assertNotIn("王小明", messages[1]["content"])
        self.assertNotIn("https://www.gov.tw", messages[1]["content"])
        self.assertIn("[NAME]", messages[1]["content"])
        self.assertIn("[OFFICIAL_URL]", messages[1]["content"])

    def test_score_contract_and_gate(self) -> None:
        answer = json.dumps({"s": [
            {"r": 4, "b": 0, "i": "INVESTMENT_PITCH"},
            {"r": 0, "b": 4, "i": "GENERAL_CHAT"},
        ]})
        scores = parse_scores(answer, 2)
        rows = [self.row("老師帶單保證獲利", 1, "scam"), self.row("今晚一起吃飯", 0, "safe")]
        config, prediction, result = select_config(rows, scores, 1.0)
        self.assertEqual(prediction.tolist(), [1, 0])
        self.assertEqual(result["specificity"], 1.0)
        self.assertEqual(predictions(rows, scores, config).tolist(), [1, 0])

    def test_intent_gate_can_reject_benign_high_score(self) -> None:
        row = self.row("限時活動請查看官方公告", 0, "notice")
        config = {
            "risk_min": 3,
            "benign_max": 1,
            "semantic_risk_min": 4,
            "semantic_benign_max": 0,
            "min_margin": 0,
            "intent_for_evidence": True,
        }
        self.assertEqual(
            predictions([row], [(3, 0, "LEGITIMATE_NOTICE")], config).tolist(),
            [0],
        )

    def test_rejects_unknown_intent(self) -> None:
        with self.assertRaises(ValueError):
            parse_scores(json.dumps({"s": [{"r": 1, "b": 1, "i": "MADE_UP"}]}), 1)


if __name__ == "__main__":
    unittest.main()
