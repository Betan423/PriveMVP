import json
import unittest

from scripts.privacy_abc_evaluation import (
    Sample,
    _counts,
    _merge_floor,
    build_batch_prompt,
    parse_ai_levels,
)
from receiver.assistant import rule_assessment
from receiver.phone_sim import extract_facts


class PrivacyAbcEvaluationTest(unittest.TestCase):
    def sample(self, text: str, label: str = "scam") -> Sample:
        facts = extract_facts(text, "pasted")
        level, _ = rule_assessment(facts)
        return Sample("0123456789abcdef", label, text, facts, level)

    def test_b_has_no_message_text_and_c_redacts_identifiers(self) -> None:
        text = "王小明請馬上匯款到123-4567890123，網址https://bad.xyz/login"
        sample = self.sample(text)
        prompt_b = build_batch_prompt([sample], "B")
        prompt_c = build_batch_prompt([sample], "C")
        self.assertNotIn("王小明", prompt_b)
        self.assertNotIn("123-4567890123", prompt_b)
        self.assertNotIn("bad.xyz", prompt_b)
        # Local HIGH means the production policy omits the excerpt entirely in C too.
        self.assertNotIn("王小明", prompt_c)
        self.assertNotIn("123-4567890123", prompt_c)
        self.assertNotIn("bad.xyz", prompt_c)

    def test_c_includes_only_redacted_excerpt_for_non_high(self) -> None:
        sample = self.sample("王小明您好，請查看 https://www.gov.tw 公告", "non_scam")
        self.assertNotEqual(sample.local_level, "high")
        prompt = build_batch_prompt([sample], "C")
        self.assertNotIn("王小明", prompt)
        self.assertNotIn("https://www.gov.tw", prompt)
        self.assertIn("[NAME]", prompt)
        self.assertIn("[LINK]", prompt)

    def test_strict_ai_array_and_risk_floor(self) -> None:
        self.assertEqual(parse_ai_levels(json.dumps({"r": ["h", "v", "i"]}), 3),
                         ["high", "verify", "insufficient"])
        with self.assertRaises(ValueError):
            parse_ai_levels(json.dumps({"r": ["h"], "extra": True}), 1)
        self.assertEqual(_merge_floor("high", "insufficient"), "high")
        self.assertEqual(_merge_floor("verify", "high"), "high")

    def test_confusion_counts_high_only(self) -> None:
        samples = [self.sample("請匯款", "scam"), self.sample("今晚一起吃飯", "non_scam")]
        counts = _counts(samples, ["high", "verify"])
        self.assertEqual((counts.tp, counts.fn, counts.fp, counts.tn), (1, 0, 0, 1))


if __name__ == "__main__":
    unittest.main()
