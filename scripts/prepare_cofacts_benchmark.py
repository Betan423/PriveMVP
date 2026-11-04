#!/usr/bin/env python3
"""Build a conservative local scam/non-scam subset from authorized Cofacts CSV exports.

The output keeps message text only in an external local JSONL file used by JVM evaluation. Reports
must use id_hash rather than text. This script does not download data or accept Cofacts terms.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


ATTRIBUTION_ZH = (
    "本編輯資料取自「Cofacts 真的假的」訊息回報機器人與查證協作社群，採 CC BY-SA 4.0 "
    "授權提供。若欲補充資訊請訪問 Cofacts LINE bot https://line.me/ti/p/@cofacts"
)

# Require an affirmative scam/phishing statement in the fact-check reply. The generic word RUMOR
# is not enough: medical or political misinformation is outside MessageGuard's fraud task.
SCAM_ASSERTION = re.compile(
    r"(?:"
    r"(?:是|為|屬於|確認為|判定為|常見的|典型的)[^。；\n]{0,16}(?:詐騙|詐欺|釣魚)|"
    r"(?:詐騙|詐欺|釣魚)(?:訊息|簡訊|網站|網址|連結|手法|行為|集團|郵件|電話)|"
    r"(?:意圖|用意|目的)[^。；\n]{0,10}(?:騙取|騙走|竊取)[^。；\n]{0,20}(?:個資|資料|帳號|密碼|金錢|款項)|"
    r"(?:假冒|冒充)[^。；\n]{0,18}(?:客服|銀行|政府|警察|平台|商家|官方)|"
    r"(?:一頁式詐騙|假投資|假中獎|盜刷|騙取個資|誘騙匯款|惡意釣魚)"
    r")"
)
SCAM_NEGATION = re.compile(r"(?:不是|並非|非屬|不屬於|沒有|無關)[^。；\n]{0,8}(?:詐騙|詐欺|釣魚)")


def set_csv_field_limit() -> None:
    limit = sys.maxsize
    while True:
        try:
            csv.field_size_limit(limit)
            return
        except OverflowError:
            limit //= 10


def as_int(value: str | None) -> int:
    try:
        return int(value or 0)
    except ValueError:
        return 0


def csv_rows(path: Path):
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        yield from csv.DictReader(handle)


def supported(row: dict[str, str]) -> bool:
    return as_int(row.get("positiveFeedbackCount")) > as_int(row.get("negativeFeedbackCount"))


def scam_assertion(text: str) -> bool:
    return bool(SCAM_ASSERTION.search(text)) and not SCAM_NEGATION.search(text)


def stable_id(article_id: str, text: str) -> str:
    return hashlib.sha256(f"cofacts\0{article_id}\0{text}".encode("utf-8")).hexdigest()[:16]


def normalized_text_key(text: str) -> str:
    return hashlib.sha256(re.sub(r"\s+", "", text).encode("utf-8")).hexdigest()


def build(input_directory: Path, output_directory: Path, max_per_label: int) -> dict:
    required = {name: input_directory / name for name in ("articles.csv", "article_replies.csv", "replies.csv")}
    missing = [str(path) for path in required.values() if not path.is_file()]
    if missing:
        raise FileNotFoundError("Missing Cofacts CSV files: " + ", ".join(missing))

    scam_reply_ids: set[str] = set()
    safe_reply_ids: set[str] = set()
    reply_counts = defaultdict(int)
    for row in csv_rows(required["replies.csv"]):
        reply_id = row.get("id", "")
        reply_type = row.get("type", "")
        if not reply_id:
            continue
        reply_counts[reply_type] += 1
        combined = f"{row.get('text', '')}\n{row.get('reference', '')}"
        if reply_type == "RUMOR" and scam_assertion(combined):
            scam_reply_ids.add(reply_id)
        elif reply_type == "NOT_RUMOR":
            safe_reply_ids.add(reply_id)

    article_votes: dict[str, set[str]] = defaultdict(set)
    link_counts = defaultdict(int)
    for row in csv_rows(required["article_replies.csv"]):
        if row.get("status") != "NORMAL" or not supported(row):
            continue
        article_id = row.get("articleId", "")
        reply_id = row.get("replyId", "")
        if not article_id or not reply_id:
            continue
        if reply_id in scam_reply_ids:
            article_votes[article_id].add("scam")
            link_counts["supported_scam_links"] += 1
        if reply_id in safe_reply_ids:
            article_votes[article_id].add("non_scam")
            link_counts["supported_safe_links"] += 1

    candidates = {"scam": [], "non_scam": []}
    excluded = defaultdict(int)
    seen_text: dict[str, str] = {}
    for row in csv_rows(required["articles.csv"]):
        if row.get("status") != "NORMAL" or row.get("articleType") != "TEXT":
            excluded["not_normal_text"] += 1
            continue
        article_id = row.get("id", "")
        text = (row.get("text") or "").strip()
        if not article_id or not text:
            excluded["missing_id_or_text"] += 1
            continue
        votes = article_votes.get(article_id, set())
        if len(votes) != 1:
            excluded["conflicting_or_unlabeled"] += 1
            continue
        label = next(iter(votes))
        text_key = normalized_text_key(text)
        if text_key in seen_text:
            excluded["duplicate_text"] += 1
            continue
        seen_text[text_key] = article_id
        candidates[label].append({
            "id_hash": stable_id(article_id, text),
            "label": label,
            "text": text,
            "source": "cofacts_line_report",
        })

    selected = []
    selected_counts = {}
    for label in ("scam", "non_scam"):
        rows = sorted(candidates[label], key=lambda row: row["id_hash"])
        if max_per_label > 0:
            rows = rows[:max_per_label]
        selected.extend(rows)
        selected_counts[label] = len(rows)
    selected.sort(key=lambda row: row["id_hash"])
    if not selected_counts["scam"] or not selected_counts["non_scam"]:
        raise RuntimeError(f"Prepared corpus is missing a class: {selected_counts}")

    output_directory.mkdir(parents=True, exist_ok=True)
    output_jsonl = output_directory / "cofacts-benchmark.jsonl"
    with output_jsonl.open("w", encoding="utf-8", newline="\n") as handle:
        for row in selected:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    (output_directory / "ATTRIBUTION.txt").write_text(
        ATTRIBUTION_ZH + "\n\nSource: https://huggingface.co/datasets/Cofacts/line-msg-fact-check-tw\n",
        encoding="utf-8",
    )
    manifest = {
        "schema": "privagent.cofacts_benchmark.v1",
        "source": "https://huggingface.co/datasets/Cofacts/line-msg-fact-check-tw",
        "license": "CC-BY-SA-4.0 plus Cofacts Data User Agreement",
        "attribution_zh": ATTRIBUTION_ZH,
        "selection": {
            "article": "NORMAL TEXT only; exact normalized-text duplicates removed",
            "scam": "supported RUMOR reply with explicit affirmative scam/phishing assertion",
            "non_scam": "supported NOT_RUMOR reply and no supported scam conflict",
            "supported": "positiveFeedbackCount > negativeFeedbackCount",
            "conflicts": "excluded",
            "sampling": "sort by SHA-256 id_hash then take max_per_label",
            "max_per_label": max_per_label,
        },
        "reply_type_counts": dict(sorted(reply_counts.items())),
        "link_counts": dict(sorted(link_counts.items())),
        "candidate_counts": {key: len(value) for key, value in candidates.items()},
        "selected_counts": selected_counts,
        "excluded_counts": dict(sorted(excluded.items())),
        "output_sha256": hashlib.sha256(output_jsonl.read_bytes()).hexdigest(),
        "privacy": "Raw text remains in this external local JSONL. Evaluation reports use only aggregate counts and id_hash.",
    }
    (output_directory / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        default=Path(__file__).resolve().parents[2]
        / "privacy-sms-datasets"
        / "Cofacts-line-msg-fact-check-tw"
        / "extracted",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "privacy-sms-datasets" / "cofacts-prepared",
    )
    parser.add_argument("--max-per-label", type=int, default=5000)
    args = parser.parse_args(argv)
    if args.max_per_label < 0:
        parser.error("--max-per-label must be zero (all rows) or positive")
    set_csv_field_limit()
    manifest = build(args.input.resolve(), args.output.resolve(), args.max_per_label)
    print(json.dumps({"output": str(args.output.resolve()), **manifest["selected_counts"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
