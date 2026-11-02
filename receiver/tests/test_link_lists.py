"""The Android port must grade links exactly like the Python reference.

MessageGuardTest already pins behaviour through the generated golden cases; this test pins the
four data lists themselves, so a domain added on one side cannot silently be missing on the other.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

from receiver import phone_sim
from receiver.phone_sim import classify_link_detail, extract_facts, link_reasons

KT_PATH = Path(__file__).resolve().parents[2] / "app/src/main/java/com/example/privagentcollector/MessageGuard.kt"


def kotlin_list(name: str) -> list[str]:
    source = KT_PATH.read_text(encoding="utf-8")
    match = re.search(r"private val " + name + r" = (?:listOf|setOf)\((.*?)\)\n", source, flags=re.S)
    if match is None:
        raise AssertionError(f"{name} not found in MessageGuard.kt")
    return re.findall(r'"([^"]+)"', match.group(1))


class LinkListParityTests(unittest.TestCase):
    def test_lists_match_the_android_port(self) -> None:
        self.assertEqual(list(phone_sim.OFFICIAL_DOMAINS), kotlin_list("officialDomains"))
        self.assertEqual(list(phone_sim.SHORTENER_DOMAINS), kotlin_list("shortenerDomains"))
        self.assertEqual(sorted(phone_sim.HIGH_RISK_TLDS), sorted(kotlin_list("highRiskTlds")))
        self.assertEqual(list(phone_sim.LOOKALIKE_TOKENS), kotlin_list("lookalikeTokens"))
        self.assertEqual(list(phone_sim.LINK_KINDS), kotlin_list("linkKinds"))

    def test_lists_are_lowercase_registrable_domains(self) -> None:
        for entry in phone_sim.OFFICIAL_DOMAINS + phone_sim.SHORTENER_DOMAINS:
            self.assertEqual(entry, entry.lower().strip("."), entry)
            self.assertNotIn("/", entry)
        # Every official domain must grade as official, and never leak into the lookalike check.
        for entry in phone_sim.OFFICIAL_DOMAINS:
            self.assertEqual(classify_link_detail("https://www." + entry + "/x"), ("known_official", "official"), entry)
        for entry in phone_sim.SHORTENER_DOMAINS:
            self.assertEqual(classify_link_detail("https://" + entry + "/abc")[0], "shortener", entry)


class LinkReasonTests(unittest.TestCase):
    def test_reason_per_grade(self) -> None:
        cases = {
            "https://www.post.gov.tw/track": ("known_official", "official"),
            "https://ntu.edu.tw/": ("known_official", "official"),
            "https://news.example.invalid/a": ("unknown", "unknown"),
            "https://reurl.cc/abc": ("shortener", "shortener"),
            "http://203.0.113.9/login": ("suspicious", "ipv4"),
            "http://[2001:db8::1]/login": ("suspicious", "ipv6"),
            "https://www.gov.tw@evil.example/": ("suspicious", "userinfo"),
            "https://[EMAIL]/login": ("suspicious", "userinfo"),
            "https://xn--fiq228c.com/x": ("suspicious", "punycode"),
            "https://台灣郵政.com/x": ("suspicious", "non_ascii"),
            "https://post.gov.tw.verify.cc/track": ("suspicious", "lookalike"),
            "https://165-gov.com/report": ("suspicious", "lookalike"),
            "https://my-bank-login.com/": ("suspicious", "lookalike"),
            "https://taiwanpost-verify.xyz/login": ("suspicious", "high_risk_tld"),
            "https://evil.example:8443/x": ("unknown", "unknown"),
            "www.gov.tw": ("known_official", "official"),
        }
        for link, expected in cases.items():
            self.assertEqual(expected, classify_link_detail(link), link)
        for reason in {r for _k, r in cases.values()}:
            self.assertIn(reason, phone_sim.LINK_REASON_TEXT)

    def test_reasons_are_ordered_most_severe_first_and_contain_no_host(self) -> None:
        links = ["https://www.gov.tw", "https://reurl.cc/a", "https://promo.bad.xyz/go"]
        self.assertEqual(["high_risk_tld", "shortener", "official"], link_reasons(links))
        for text in (phone_sim.LINK_REASON_TEXT[r] for r in link_reasons(links)):
            for host in ("gov.tw", "reurl", "xyz"):
                self.assertNotIn(host, text)

    def test_packet_carries_only_the_grade(self) -> None:
        facts = extract_facts("請點 https://taiwanpost-verify.xyz/login 補填資料", "pasted")
        self.assertEqual("suspicious", facts["link_kind"])
        self.assertNotIn("xyz", str(facts))
        self.assertNotIn("taiwanpost", str(facts))


if __name__ == "__main__":
    unittest.main()
