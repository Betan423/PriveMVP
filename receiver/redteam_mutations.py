"""Deterministic CHECK_MESSAGE red-team mutation generator.

The generated text stays in memory. Evidence outputs identify only seed and
technique, so the evaluator does not create another message corpus containing
sensitive-looking examples.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


SEPARATORS = {
    "ascii_space": " ",
    "tab": "\t",
    "newline": "\n",
    "ideographic_space": "\u3000",
    "zero_width_space": "\u200b",
    "word_joiner": "\u2060",
    "hyphen": "-",
    "underscore": "_",
    "ascii_dot": ".",
    "slash": "/",
    "fullwidth_slash": "／",
    "middle_dot": "·",
    "bullet": "•",
    "emoji": "💸",
}


def load_and_generate(path: Path) -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if type(value) is not dict or set(value) != {"version", "seeds"} or type(value["seeds"]) is not list:
        raise ValueError("mutation seed corpus must contain exactly version and seeds")
    required = {"id", "text", "target", "expected_level", "expected_facts"}
    results: list[dict[str, Any]] = []
    ids: set[str] = set()
    for seed in value["seeds"]:
        if type(seed) is not dict or set(seed) != required or seed["text"].count(seed["target"]) != 1:
            raise ValueError("every mutation seed must use the exact schema and contain its target once")
        if len(seed["target"]) < 2 or seed["expected_level"] not in {"high", "verify", "insufficient"}:
            raise ValueError("mutation target or expected level is invalid")
        for technique, separator in SEPARATORS.items():
            attack_id = f"{seed['id']}__{technique}"
            if attack_id in ids:
                raise ValueError("duplicate mutation id")
            ids.add(attack_id)
            obfuscated = separator.join(seed["target"])
            results.append({
                "id": attack_id,
                "seed_id": seed["id"],
                "technique": technique,
                "text": seed["text"].replace(seed["target"], obfuscated),
                "expected_level": seed["expected_level"],
                "expected_facts": seed["expected_facts"],
            })
    return results
