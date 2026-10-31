"""Phone-side simulator for the CHECK_MESSAGE demo.

Mirrors what the Android app will do on the device: redact identifiers, extract
risk features, build the planner packet, then call the Receiver. The original
message text never leaves this process; only the packet is sent.

Examples:
  python -m receiver.phone_sim --message "我是客服，請提供一次性驗證碼 123456"
  python -m receiver.phone_sim --dataset <source_raw_v5.json> --environment 1
  python -m receiver.phone_sim --dataset <source_raw_v5.json> --scan-only
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import string
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .assistant import MAX_EXCERPT_CHARS, MESSAGE_PACKET_SCHEMA, MESSAGE_SOPS, packet_forbidden_hits, rule_assessment

CLIENT_POLICY_VERSION = "phone-msg-3"

# Red teams can insert zero-width or bidi formatting controls inside a phone number,
# URL or scam keyword. They have no semantic value for this task, so remove them
# before both redaction and feature extraction. Keep this range in sync with
# MessageGuard.kt and receiver/assistant.py.
INVISIBLE_FORMAT_CONTROLS = re.compile(r"[\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]")

SYNTHETIC_KINDS = {
    "ACCOUNT": "[ACCOUNT]", "OTP": "[OTP]", "NAME": "[NAME]", "PHONE": "[PHONE]", "ADDRESS": "[ADDRESS]",
    "RECORD": "[RECORD]", "ID": "[ID]", "PASSWORD": "[SECRET]", "LOCATION": "[ADDRESS]",
}
SEP = r"[\s\-./()]"
# Order matters: specific identifiers first, generic number runs last. Input is NFKC-normalised,
# so full-width digits and brackets are already ASCII.
REDACTIONS = (
    (re.compile(r"SYN_(?:V5_)?([A-Z]+)_PRIVATE_[A-Z0-9_]+"), lambda m: SYNTHETIC_KINDS.get(m.group(1), "[SECRET]")),
    (re.compile(r"SYN_[A-Z0-9_]+"), lambda m: "[SECRET]"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), lambda m: "[EMAIL]"),
    (URL_PATTERN := re.compile(r"(?i)(?:https?://|www\.)[^\s，。、「」！？]+"), lambda m: "[LINK]"),
    (BARE_DOMAIN_PATTERN := re.compile(r"(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}\b(?:/[^\s，。、「」！？]*)?"),
     lambda m: "[LINK]"),
    (re.compile(r"\b[A-Z][12][0-9]{8}\b"), lambda m: "[ID]"),
    (re.compile(r"((?:驗證碼|認證碼|OTP|密碼|控制碼|代碼)[^\[\]A-Za-z0-9]{0,6}?)(?=[A-Za-z]*[0-9])[A-Za-z0-9]{4,10}\b", re.I),
     lambda m: m.group(1) + "[OTP]"),
    (re.compile(r"(?<![0-9])(?:\+?886" + SEP + r"*|0)9(?:" + SEP + r"*[0-9]){8}(?![0-9])"), lambda m: "[PHONE]"),
    (re.compile(r"(?<![0-9])(?:\+?886" + SEP + r"*|0)[2-8](?:" + SEP + r"*[0-9]){6,8}(?![0-9])"), lambda m: "[PHONE]"),
    (re.compile(r"(?<![0-9])[0-9](?:" + SEP + r"{0,2}[0-9]){3,}"), lambda m: "[NUMBER]"),
)
# Regexes cannot recognise arbitrary names; catch salutations, and on a real phone also the
# contact names the device already knows (see build_packet known_names).
SALUTATION = re.compile(r"[一-鿿]{2,4}(?=(您好|你好|先生|小姐|女士|阿姨|伯伯|奶奶|爺爺|阿公|阿嬤))")

# The URL itself never leaves the phone ([LINK] in the excerpt, has_link in the facts). To tell a
# phishing link from an official notification without disclosing the URL, the phone classifies each
# link locally and only the category below is sent. Keep lists and order in sync with MessageGuard.kt.
LINK_KINDS = ("none", "known_official", "unknown", "shortener", "suspicious")
_LINK_SEVERITY = {kind: rank for rank, kind in enumerate(LINK_KINDS)}
# Registrable domains treated as official: whole gov.tw plus the retail banks elders actually use.
OFFICIAL_DOMAINS = (
    "gov.tw", "bot.com.tw", "landbank.com.tw", "tcb-bank.com.tw", "firstbank.com.tw", "hncb.com.tw",
    "chb.com.tw", "megabank.com.tw", "ctbcbank.com", "cathaybk.com.tw", "esunbank.com.tw",
    "taishinbank.com.tw", "sinopac.com", "scsb.com.tw", "fubon.com",
)
# A URL shortener hides the destination, so it can never count as official.
SHORTENER_DOMAINS = (
    "bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "t.ly", "reurl.cc", "ppt.cc", "pse.is",
    "lihi.cc", "lihi1.com", "lihi2.net", "lin.ee",
)
# TLDs with near-zero legitimate use in zh-TW messages but heavy phishing abuse.
HIGH_RISK_TLDS = frozenset({"tk", "ml", "ga", "cf", "gq", "xyz", "top", "icu", "vip", "buzz", "cfd", "sbs", "click"})
# Distinctive official tokens appearing inside a NON-official host mean a lookalike domain.
LOOKALIKE_TOKENS = (
    "govtw", "ctbcbank", "cathaybk", "esunbank", "megabank", "taishinbank", "landbank", "hncb",
    "sinopac", "firstbank", "tcbbank", "scsb", "fubon",
)


def classify_link(link: str) -> str:
    """Grade one matched link; only the category ever leaves the phone, never the URL."""
    stripped = re.sub(r"(?i)^https?://", "", link.strip().lower())
    host = re.split(r"[/?#]", stripped, 1)[0]
    if host.startswith("["):
        return "suspicious"  # Raw IPv6 literal: no legitimate zh-TW notification uses one.
    host = host.split(":")[0]
    host = re.sub(r"^[^a-z0-9\u0080-\U0010ffff]+|[^a-z0-9\u0080-\U0010ffff]+$", "", host)
    if not host:
        return "unknown"
    if any(ord(c) > 127 for c in host):
        return "suspicious"  # Non-ASCII host: IDN/homoglyph risk the user cannot read reliably.
    labels = [label for label in host.split(".") if label]
    if labels and all(label.isdigit() for label in labels):
        return "suspicious"  # Raw IPv4 literal.
    for official in OFFICIAL_DOMAINS:
        if host == official or host.endswith("." + official):
            return "known_official"
    if any(label.startswith("xn--") for label in labels):
        return "suspicious"  # Punycode outside the whitelist.
    compact = host.replace(".", "").replace("-", "")
    if any(token in compact for token in LOOKALIKE_TOKENS):
        return "suspicious"  # Official brand token inside a non-official host.
    if labels and labels[-1] in HIGH_RISK_TLDS:
        return "suspicious"
    for shortener in SHORTENER_DOMAINS:
        if host == shortener or host.endswith("." + shortener):
            return "shortener"
    return "unknown"


def classify_links(links: list[str]) -> str:
    """The packet carries one category: the most severe among all links in the message."""
    if not links:
        return "none"
    return max((classify_link(link) for link in links), key=_LINK_SEVERITY.__getitem__)

# "不需要付款", "請勿告訴任何人" describe what NOT to do. Negation covers only the action right
# after it (plus "或／和" chains) and never crosses 請 or punctuation, so "不要猶豫請轉帳" stays risky.
ACTIONS = r"(?:付款|轉帳|匯款|支付|繳費|告訴|提供|分享|輸入|給)"
NEGATED_ACTION = re.compile(
    r"(?:不需要|不需|不用|無需|毋需|不必|請勿|不要|切勿|勿|別)(?:再|去|隨便|任意|向?(?:任何人|他人|別人))?"
    + ACTIONS + r"(?:(?:或|和|、|及)" + ACTIONS + r")*"
)
SECRET_WORDS = re.compile(r"(驗證碼|認證碼|OTP|密碼|控制碼|提款卡|信用卡號|卡號|身分證)", re.I)
REQUEST_VERBS = re.compile(r"(提供|告訴|寄給|給我|傳給|回傳|輸入|回覆|念給|唸給)")
CREDENTIAL_PHISHING = re.compile(r"(帳戶|帳號|會員).{0,12}(驗證|登入|解鎖).{0,20}(輸入|填寫).{0,6}(資料|個資)")
PATTERNS = {
    "asks_transfer": re.compile(r"(轉帳|匯款|先匯|匯到|付款|支付|繳費|繳納|ATM|轉入|儲值)"),
    "urgency": re.compile(r"(立即|立刻|馬上|盡快|趕快|緊急|否則|限時|今天內|停權|凍結|處分|逾期|最後機會)"),
    "offers_reward": re.compile(r"(中獎|獎金|保證獲利|高報酬|穩賺|免費領|紅利|抽中)"),
    "requests_remote_control": re.compile(r"(遠端控制|遠端軟體|遠端協助|螢幕分享|控制碼)"),
}
IMPERSONATION = (
    ("family", re.compile(r"(我是(你的?)?(家人|兒子|女兒|孫子|孫女|媳婦|女婿)|換(了)?(新)?(手機)?號碼)")),
    ("official", re.compile(r"(我是(警察|檢察官|法官|法院|機構人員|公務員|政府)|警察局|檢察署|地檢署)")),
    ("support", re.compile(r"(我是(客服|銀行|專員|電信)|客服人員)")),
)
VAGUE = re.compile(r"(細節還沒|還沒有說明|稍後說明|之後再說|晚點再說)")
FILLER = re.compile(r"^(這是虛構訊息。|虛構測試內容：|虛構\w*通知：)")
# Remove short punctuation/symbol gaps only when they sit inside a Han phrase. Sentence delimiters
# remain boundaries, while attacks such as 轉-帳、驗💸證💸碼 and 客／服 become detectable.
HAN_GAP = re.compile(
    r"(?<=[\u3400-\u9fff])(?:[^\w\u3400-\u9fff\[\]，。！？,!?;；:：]|_){1,3}(?=[\u3400-\u9fff])"
)

ADVICE = {
    "high": "高風險：這則訊息有常見的詐騙特徵。不要匯款、不要提供驗證碼或密碼、不要點連結。"
            "請用你原本就知道的電話，直接問家人或官方客服。",
    "verify": "需要核實：沒有找到明顯的詐騙特徵，但這不代表一定安全。"
              "如果跟錢或個人資料有關，請先用原本的管道確認。",
    "insufficient": "資訊不足：這則訊息的內容不夠判斷。請先不要回應，可以問家人，或把完整訊息給我再看一次。",
}
REASON_TEXT = {
    "ASKS_TRANSFER": "要求轉帳或付款", "REQUESTS_SECRET": "索取驗證碼、密碼或個人資料", "URGENCY": "催促你馬上處理",
    "HAS_LINK": "含有連結", "LINK_SUSPICIOUS": "連結疑似假冒官方、IP 直連或高風險網域",
    "LINK_SHORTENER": "使用短網址，看不出真正的網站",
    "IMPERSONATION_FAMILY": "自稱家人或換了號碼", "IMPERSONATION_OFFICIAL": "自稱警察或政府機關",
    "IMPERSONATION_SUPPORT": "自稱客服或銀行", "OFFERS_REWARD": "提到中獎或保證獲利", "REMOTE_CONTROL": "要求遠端控制手機",
    "CONTEXT_INSUFFICIENT": "內容太少，無法判斷", "NO_STRONG_SIGNAL": "沒有發現明顯詐騙特徵",
}


def _redact_collecting_links(text: str, known_names: tuple[str, ...] = ()) -> tuple[str, list[str]]:
    """Redacts like redact() and also returns what the two link patterns matched, so link
    classification sees exactly the links that became [LINK] (never an email already redacted)."""
    # U+001C..U+001F count as whitespace in Python but not Java; map them to spaces on both sides.
    text = re.sub(r"[\x1c-\x1f]", " ", unicodedata.normalize("NFKC", text))
    text = INVISIBLE_FORMAT_CONTROLS.sub("", text)
    for name in sorted((n for n in known_names if n), key=len, reverse=True):
        normalized_name = INVISIBLE_FORMAT_CONTROLS.sub("", unicodedata.normalize("NFKC", name))
        text = text.replace(normalized_name, "[NAME]")
    links: list[str] = []
    for pattern, replacement in REDACTIONS:
        if pattern is URL_PATTERN or pattern is BARE_DOMAIN_PATTERN:
            def collect(match: re.Match[str]) -> str:
                links.append(match.group(0))
                return "[LINK]"
            text = pattern.sub(collect, text)
        else:
            text = pattern.sub(replacement, text)
    return SALUTATION.sub("[NAME]", text), links


def redact(text: str, known_names: tuple[str, ...] = ()) -> str:
    return _redact_collecting_links(text, known_names)[0]


def extract_facts(original: str, source_kind: str) -> dict[str, Any]:
    redacted, links = _redact_collecting_links(original)
    # Detection uses a compact view so harmless spacing cannot hide phrases such as
    # "匯 款" or "驗 證 碼". The optional excerpt remains readable and unchanged.
    detection_text = re.sub(r"\s+", "", HAN_GAP.sub("", redacted))
    without_negation = NEGATED_ACTION.sub("", detection_text)
    facts: dict[str, Any] = {key: bool(pattern.search(without_negation)) for key, pattern in PATTERNS.items()}
    facts["requests_secret"] = bool(
        (SECRET_WORDS.search(without_negation) and REQUEST_VERBS.search(without_negation))
        or CREDENTIAL_PHISHING.search(detection_text)
    )
    # has_link and link_kind both come from the same matches, so they can never disagree
    # (a literal "[LINK]" typed by the sender is not a link).
    facts["has_link"] = bool(links)
    facts["link_kind"] = classify_links(links)
    facts["impersonation"] = next((kind for kind, pattern in IMPERSONATION if pattern.search(detection_text)), "none")
    core = FILLER.sub("", redacted).strip()
    facts["context_sufficient"] = not VAGUE.search(core) and len(core) >= 8
    facts["source_kind"] = source_kind
    return facts


def build_packet(
    original: str,
    source_kind: str = "pasted",
    *,
    allow_excerpt: bool = False,
    known_names: tuple[str, ...] = (),
    issued_at_ms: int | None = None,
    request_nonce: str | None = None,
) -> dict[str, Any]:
    facts = extract_facts(original, source_kind)
    local_level, _ = rule_assessment(facts)
    # Blueprint ch.9: only send de-identified semantics when local features are not decisive.
    # Redaction cannot catch every name, so the excerpt is opt-in.
    excerpt = None
    if allow_excerpt and local_level != "high":
        excerpt = redact(original, known_names)[:MAX_EXCERPT_CHARS] or None
    alias = "TASK_" + "".join(secrets.choice(string.ascii_uppercase) for _ in range(8))
    nonce = request_nonce or "REQ_" + "".join(secrets.choice(string.ascii_uppercase) for _ in range(20))
    return {
        "schema": MESSAGE_PACKET_SCHEMA,
        "task_id_alias": alias,
        "request_nonce": nonce,
        "issued_at_ms": issued_at_ms if issued_at_ms is not None else int(time.time() * 1000),
        "intent": "CHECK_MESSAGE",
        "locale": "zh-TW",
        "cloud_consent": True,
        "facts": facts,
        "redacted_excerpt": excerpt,
        "allowed_sops": list(MESSAGE_SOPS),
        "client_policy_version": CLIENT_POLICY_VERSION,
    }


def send(receiver: str, packet: dict[str, Any], token: str | None, timeout: float) -> tuple[int, dict[str, Any]]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Api-Token"] = token
    request = urllib.request.Request(
        receiver.rstrip("/") + "/api/v1/assist/check-message",
        data=json.dumps(packet, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def render(result: dict[str, Any]) -> str:
    lines = [ADVICE[result["risk_level"]]]
    lines += ["  · " + REASON_TEXT[code] for code in result["reason_codes"]]
    planner = result["planner"]
    source = "Gemini" if planner["kind"] == "GEMINI_PROXY" else f"本機規則（{planner['fallback_reason']}）"
    lines.append(f"  判讀來源：{source}")
    return "\n".join(lines)


def dataset_messages(path: Path, environment: int | None) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    envs = data["phone_environments"]
    if environment is not None:
        envs = [envs[environment - 1]]
    return [m for env in envs for m in env["continuous_state_data"]["messages"]]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--message", help="One message text, processed as if pasted by the user")
    source.add_argument("--dataset", type=Path, help="v5 source_raw_v5.json")
    parser.add_argument("--environment", type=int, help="1-based environment index when using --dataset")
    parser.add_argument("--scan-only", action="store_true", help="Only check redaction; send nothing")
    parser.add_argument("--receiver", default="http://127.0.0.1:8080")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--show-packet", action="store_true")
    parser.add_argument("--allow-excerpt", action="store_true",
                        help="Also send the redacted text when local rules are not decisive")
    args = parser.parse_args(argv)
    token = os.environ.get("PRIVAGENT_API_TOKEN")

    if args.message is not None:
        items = [{"text": args.message, "template_id": "cli"}]
    else:
        items = dataset_messages(args.dataset, args.environment)

    if args.scan_only:
        leaks = [(m.get("template_id"), packet_forbidden_hits(build_packet(m["text"], allow_excerpt=True))) for m in items]
        leaks = [leak for leak in leaks if leak[1]]
        print(json.dumps({"messages": len(items), "packets_with_forbidden_values": len(leaks),
                          "examples": leaks[:10]}, ensure_ascii=False, indent=2))
        return 1 if leaks else 0

    for item in items:
        packet = build_packet(item["text"], allow_excerpt=args.allow_excerpt)
        print("=" * 72)
        print(f"[{item.get('template_id')}] 原文（只留在手機）：{item['text']}")
        print(f"送出摘錄：{packet['redacted_excerpt']}")
        if args.show_packet:
            print(json.dumps(packet, ensure_ascii=False, indent=2))
        status, result = send(args.receiver, packet, token, args.timeout)
        if status != 200:
            print(f"Receiver 拒絕（HTTP {status}）：{result.get('error')}")
            continue
        print(render(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
