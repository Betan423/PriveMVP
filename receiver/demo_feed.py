"""In-memory event feed for the stage demo page (`--demo-feed`, loopback only).

Nothing here is written to disk and the feed empties on restart. Live events hold only what the
Receiver actually received (the packet, never message text). The sandbox runs the phone-side
reference implementation on the operator's laptop to explain the on-device steps.
"""

from __future__ import annotations

import re
import threading
import time
import unicodedata
from collections import deque
from typing import Any

from . import phone_sim

MAX_EVENTS = 50
MAX_SANDBOX_CHARS = 2000
PLACEHOLDER = re.compile(r"\[(?:LINK|PHONE|OTP|ACCOUNT|EMAIL|ID|NAME|ADDRESS|RECORD|SECRET|NUMBER)\]")


class DemoFeed:
    def __init__(self, max_events: int = MAX_EVENTS) -> None:
        self._events: deque[dict[str, Any]] = deque(maxlen=max_events)
        self._seq = 0
        self._lock = threading.Lock()

    def add(self, event: dict[str, Any]) -> None:
        with self._lock:
            self._seq += 1
            self._events.append({**event, "seq": self._seq, "ts": int(time.time() * 1000)})

    def since(self, after: int) -> list[dict[str, Any]]:
        with self._lock:
            return [event for event in self._events if event["seq"] > after]


def phone_steps(text: str, allow_excerpt: bool = False) -> dict[str, Any]:
    """The on-device CHECK_MESSAGE stages, step by step, using the Python reference of MessageGuard."""
    text = text[:MAX_SANDBOX_CHARS]
    normalized = phone_sim.INVISIBLE_FORMAT_CONTROLS.sub("", unicodedata.normalize("NFKC", text))
    redacted = phone_sim.redact(text)
    detection = re.sub(r"\s+", "", phone_sim.HAN_GAP.sub("", redacted))
    without_negation = phone_sim.NEGATED_ACTION.sub("", detection)
    facts = phone_sim.extract_facts(text, "pasted")

    # Which words fired each feature. They come from the redacted text, so no identifier is shown.
    triggers: dict[str, list[str]] = {}
    for key, pattern in phone_sim.PATTERNS.items():
        triggers[key] = sorted({m.group(0) for m in pattern.finditer(without_negation)})
    secret_hits = [m.group(0) for m in phone_sim.SECRET_WORDS.finditer(without_negation)]
    if facts["requests_secret"]:
        triggers["requests_secret"] = sorted(set(secret_hits + [m.group(0) for m in phone_sim.REQUEST_VERBS.finditer(without_negation)]))
    if facts["has_link"]:
        triggers["has_link"] = ["[LINK]"]
    for kind, pattern in phone_sim.IMPERSONATION:
        if kind == facts["impersonation"]:
            triggers["impersonation"] = sorted({m.group(0) for m in pattern.finditer(detection)})

    packet = phone_sim.build_packet(text, "pasted", allow_excerpt=allow_excerpt)
    local_level, local_codes = phone_sim.rule_assessment(facts)
    return {
        "original_chars": len(text),
        "normalized_changed": normalized != text,
        "redacted": redacted,
        "placeholders": PLACEHOLDER.findall(redacted),
        "detection_text": detection,
        "negations_removed": detection != without_negation,
        "facts": facts,
        "triggers": {k: v for k, v in triggers.items() if v},
        "local": {"risk_level": local_level, "reason_codes": local_codes},
        "packet": packet,
    }
