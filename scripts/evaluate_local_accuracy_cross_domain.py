#!/usr/bin/env python3
"""Cross-domain validation of the frozen local-accuracy classifier on TS-Bench."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[1]
if str(REPOSITORY) not in sys.path:
    sys.path.insert(0, str(REPOSITORY))

import joblib  # noqa: E402
import numpy as np  # noqa: E402

from receiver.assistant import rule_assessment  # noqa: E402
from receiver.phone_sim import extract_facts  # noqa: E402
from scripts.optimize_local_scam_classifier import (  # noqa: E402
    Row,
    Usage,
    apply_qwen_rule,
    counts,
    fact_matrix,
    infer_rows,
    metrics,
    pct,
)


def default_root() -> Path:
    return REPOSITORY.parent / "privacy-sms-datasets"


def load_ts_bench(path: Path) -> list[Row]:
    result = []
    with path.open(encoding="utf-8-sig", newline="") as handle:
        for item in csv.DictReader(handle):
            row_id = int(item["id"])
            if 1 <= row_id <= 98:
                label = 1
            elif 201 <= row_id <= 298:
                label = 0
            else:
                continue
            text = item["message"]
            facts = extract_facts(text, "pasted")
            level, _ = rule_assessment(facts)
            result.append(Row(hashlib.sha256(f"ts-bench:{row_id}".encode()).hexdigest()[:16],
                              label, text, facts, int(level == "high")))
    if len(result) != 196:
        raise ValueError(f"Expected 196 TS-Bench rows, got {len(result)}")
    return result


def run(args: argparse.Namespace) -> int:
    rows = load_ts_bench(args.dataset)
    config = json.loads((args.model_dir / "config.json").read_text(encoding="utf-8"))
    vectorizer = joblib.load(args.model_dir / "tfidf_vectorizer.joblib")
    tfidf = joblib.load(args.model_dir / "tfidf_classifier.joblib")
    ensemble = joblib.load(args.model_dir / "ensemble_classifier.joblib")
    tfidf_prob = tfidf.predict_proba(vectorizer.transform([row.text for row in rows]))[:, 1]
    usage = Usage()
    qwen, truncated = infer_rows(rows, args, usage)
    local = np.asarray([row.local_high for row in rows])
    predictions = {
        "rules": local,
        "tfidf": (tfidf_prob >= float(config["tfidf"]["threshold"])).astype(int),
        "qwen_raw": apply_qwen_rule(qwen, local, config["qwen"]),
    }
    features = np.column_stack((tfidf_prob, qwen[:, 0] / 4, qwen[:, 1] / 4, fact_matrix(rows)))
    predictions["ensemble"] = (
        ensemble.predict_proba(features)[:, 1] >= float(config["ensemble"]["threshold"])
    ).astype(int)
    y = np.asarray([row.label for row in rows])
    results = {name: {"counts": counts(y, pred), "metrics": metrics(y, pred)}
               for name, pred in predictions.items()}
    args.report_dir.mkdir(parents=True, exist_ok=True)
    machine = {
        "schema": "privagent.local_accuracy_cross_domain.v1",
        "dataset": "TS-Bench IDs 1..98 and 201..298",
        "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
        "frozen_config": config,
        "results": results,
        "usage": usage.__dict__,
        "truncated": truncated,
    }
    (args.report_dir / "results.json").write_text(json.dumps(machine, ensure_ascii=False, indent=2) + "\n",
                                                   encoding="utf-8")
    lines = [
        "# 本機準確率模型跨資料集驗證",
        "",
        "- 資料：TS-Bench 98筆詐騙／金融誘騙＋98筆安全困難負例",
        "- 所有模型、門檻與融合權重均已由Cofacts train/dev凍結，沒有使用TS-Bench標籤調參",
        "",
        "| 方法 | TP | FN | FP | TN | Accuracy | Precision | Recall | Specificity | F1 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for name, result in results.items():
        c, m = result["counts"], result["metrics"]
        lines.append(f"| {name} | {c['tp']} | {c['fn']} | {c['fp']} | {c['tn']} | {pct(m['accuracy'])} | "
                     f"{pct(m['precision'])} | {pct(m['recall'])} | {pct(m['specificity'])} | {pct(m['f1'])} |")
    lines.extend(["", f"Qwen呼叫 {usage.calls}；快取 {usage.cache_hits}；截斷 {truncated}。"])
    (args.report_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"report={args.report_dir / 'report.md'}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    root = default_root()
    parser.add_argument("--dataset", type=Path, default=root / "TS-Bench" / "data" / "TSB400.csv")
    parser.add_argument("--model-dir", type=Path, default=root / "cofacts-prepared" / "local-accuracy-model")
    parser.add_argument("--cache-dir", type=Path, default=root / "cofacts-prepared" / "local-accuracy-ts-cache")
    parser.add_argument("--report-dir", type=Path,
                        default=REPOSITORY / "app" / "build" / "reports" / "local-accuracy-ts")
    parser.add_argument("--model", default="qwen3.5:9b")
    parser.add_argument("--ollama-url", default="http://127.0.0.1:11434")
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--max-text-chars", type=int, default=3000)
    parser.add_argument("--context", type=int, default=16384)
    parser.add_argument("--timeout", type=float, default=300.0)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
