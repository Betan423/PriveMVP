import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "prepare_cofacts_benchmark.py"
SPEC = importlib.util.spec_from_file_location("prepare_cofacts_benchmark", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PrepareCofactsBenchmarkTest(unittest.TestCase):
    def write_csv(self, directory, name, fields, rows):
        path = directory / name
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def test_filters_non_scam_rumors_conflicts_and_unsupported_links(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]) as temp:
            root = Path(temp)
            source = root / "source"
            output = root / "output"
            source.mkdir()
            self.write_csv(
                source,
                "replies.csv",
                ["id", "type", "text", "reference"],
                [
                    {"id": "r-scam", "type": "RUMOR", "text": "此為詐騙訊息，請勿點擊。", "reference": ""},
                    {"id": "r-safe", "type": "NOT_RUMOR", "text": "這是官方正常通知。", "reference": ""},
                    {"id": "r-health", "type": "RUMOR", "text": "此健康偏方沒有科學證據。", "reference": ""},
                    {"id": "r-negated", "type": "RUMOR", "text": "這並非詐騙，而是資料錯植。", "reference": ""},
                ],
            )
            self.write_csv(
                source,
                "article_replies.csv",
                ["articleId", "replyId", "status", "positiveFeedbackCount", "negativeFeedbackCount"],
                [
                    {"articleId": "a-scam", "replyId": "r-scam", "status": "NORMAL", "positiveFeedbackCount": "2", "negativeFeedbackCount": "0"},
                    {"articleId": "a-safe", "replyId": "r-safe", "status": "NORMAL", "positiveFeedbackCount": "1", "negativeFeedbackCount": "0"},
                    {"articleId": "a-health", "replyId": "r-health", "status": "NORMAL", "positiveFeedbackCount": "2", "negativeFeedbackCount": "0"},
                    {"articleId": "a-negated", "replyId": "r-negated", "status": "NORMAL", "positiveFeedbackCount": "2", "negativeFeedbackCount": "0"},
                    {"articleId": "a-conflict", "replyId": "r-scam", "status": "NORMAL", "positiveFeedbackCount": "2", "negativeFeedbackCount": "0"},
                    {"articleId": "a-conflict", "replyId": "r-safe", "status": "NORMAL", "positiveFeedbackCount": "2", "negativeFeedbackCount": "0"},
                    {"articleId": "a-tie", "replyId": "r-scam", "status": "NORMAL", "positiveFeedbackCount": "0", "negativeFeedbackCount": "0"},
                ],
            )
            self.write_csv(
                source,
                "articles.csv",
                ["id", "articleType", "status", "text"],
                [
                    {"id": "a-scam", "articleType": "TEXT", "status": "NORMAL", "text": "付款異常，請立即驗證。"},
                    {"id": "a-safe", "articleType": "TEXT", "status": "NORMAL", "text": "訂單已正常出貨。"},
                    {"id": "a-health", "articleType": "TEXT", "status": "NORMAL", "text": "網傳健康偏方。"},
                    {"id": "a-negated", "articleType": "TEXT", "status": "NORMAL", "text": "網傳資料錯植。"},
                    {"id": "a-conflict", "articleType": "TEXT", "status": "NORMAL", "text": "查核意見互相衝突。"},
                    {"id": "a-tie", "articleType": "TEXT", "status": "NORMAL", "text": "尚未獲得任何回饋。"},
                ],
            )

            manifest = MODULE.build(source, output, max_per_label=10)
            rows = [json.loads(line) for line in (output / "cofacts-benchmark.jsonl").read_text(encoding="utf-8").splitlines()]

            self.assertEqual({"scam": 1, "non_scam": 1}, manifest["selected_counts"])
            self.assertEqual({"scam", "non_scam"}, {row["label"] for row in rows})
            self.assertTrue((output / "ATTRIBUTION.txt").is_file())
            self.assertNotIn("a-scam", (output / "manifest.json").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
