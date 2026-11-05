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

CLIENT_POLICY_VERSION = "phone-msg-4"

# Red teams can insert zero-width or bidi formatting controls inside a phone number,
# URL or scam keyword. They have no semantic value for this task, so remove them
# before both redaction and feature extraction. Keep this range in sync with
# MessageGuard.kt and receiver/assistant.py.
INVISIBLE_FORMAT_CONTROLS = re.compile(r"[\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]")
# "evil。xyz", "evil[.]xyz": a defanged or ideographic dot between two host labels is still a link
# to a reader who retypes it. Only fires between ASCII label characters, never inside a sentence.
DEFANGED_DOT = re.compile(r"(?i)(?<=[a-z0-9])(?:。|\[\.\]|\(\.\)|\{\.\}|\[dot\]|\(dot\))(?=[a-z0-9])")
# Simplified or variant characters spell the same scam ("请立即转账", "滙款"). The detection view
# is folded to the traditional forms the patterns use; the readable excerpt is left as written.
VARIANT_CHARS = str.maketrans({pair[0]: pair[1] for pair in "转轉 账帳 帐帳 汇匯 滙匯 验驗 证證 証證 码碼 马馬 点點 击擊 链鏈 录錄 冻凍 结結 权權 银銀 还還 给給 请請 务務 费費 缴繳 现現 线線 个個 这這 备備 护護 签簽 领領 奖獎 询詢 机機 网網 户戶 异異 处處 罚罰 输輸 发發 时時 间間 开開 关關 没沒 儿兒 妈媽 换換 电電 话話 号號 远遠 软軟 协協 装裝 载載 认認 则則 紧緊 单單 币幣 补補 贴貼 讯訊 简簡 传傳".split()})

SYNTHETIC_KINDS = {
    "ACCOUNT": "[BANK_ACCOUNT]", "OTP": "[OTP_CODE]", "NAME": "[NAME]", "PHONE": "[PHONE_NUMBER]",
    "ADDRESS": "[ADDRESS]", "RECORD": "[RECORD]", "ID": "[NATIONAL_ID]", "PASSWORD": "[SECRET]",
    "LOCATION": "[ADDRESS]",
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
    (re.compile(r"\b[A-Z][12][0-9]{8}\b"), lambda m: "[NATIONAL_ID]"),
    (re.compile(r"((?:帳號|帳戶|匯到|轉入|轉至)[^0-9\[\]\n]{0,8})[0-9](?:" + SEP + r"{0,2}[0-9]){3,}"),
     lambda m: m.group(1) + "[BANK_ACCOUNT]"),
    (re.compile(r"(?i)(?:(?:NT\$|TWD|新臺幣|台幣|\$)\s*)?[0-9][0-9,.\s]{2,}(?=\s*(?:元|塊|萬))"),
     lambda m: "[AMOUNT]"),
    (re.compile(r"((?:驗證碼|認證碼|OTP|密碼|控制碼|代碼)[^\[\]A-Za-z0-9]{0,6}?)(?=[A-Za-z]*[0-9])[A-Za-z0-9]{4,10}\b", re.I),
     lambda m: m.group(1) + "[OTP_CODE]"),
    (re.compile(r"(?<![0-9])(?:\+?886" + SEP + r"*|0)9(?:" + SEP + r"*[0-9]){8}(?![0-9])"), lambda m: "[PHONE_NUMBER]"),
    (re.compile(r"(?<![0-9])(?:\+?886" + SEP + r"*|0)[2-8](?:" + SEP + r"*[0-9]){6,8}(?![0-9])"), lambda m: "[PHONE_NUMBER]"),
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
# Registrable domains treated as official: all of gov.tw / edu.tw plus the retail banks elders
# actually use. Matching is on whole DNS labels (host == entry or host ends with "." + entry), so
# "post.gov.tw.verify.cc" is NOT official. This is a demo list, not a complete registry.
OFFICIAL_DOMAINS = (
    "gov.tw", "edu.tw", "bot.com.tw", "landbank.com.tw", "tcb-bank.com.tw", "firstbank.com.tw",
    "hncb.com.tw", "chb.com.tw", "megabank.com.tw", "tbb.com.tw", "ctbcbank.com", "cathaybk.com.tw",
    "esunbank.com.tw", "esunbank.com", "taishinbank.com.tw", "sinopac.com", "scsb.com.tw", "fubon.com",
    "linebank.com.tw", "ubot.com.tw", "skbank.com.tw", "yuantabank.com.tw", "kgibank.com.tw",
    "bankchb.com", "taipeifubon.com.tw", "o-bank.com",
)
# A URL shortener hides the destination, so it can never count as official.
SHORTENER_DOMAINS = (
    "bit.ly", "tinyurl.com", "tiny.cc", "goo.gl", "t.co", "is.gd", "t.ly", "cutt.ly", "rb.gy",
    "shorturl.at", "ow.ly", "buff.ly", "reurl.cc", "ppt.cc", "pse.is", "lihi.cc", "lihi1.com",
    "lihi2.net", "lurl.cc", "risu.io", "0rz.tw", "lin.ee",
)
# TLDs with near-zero legitimate use in zh-TW messages but heavy phishing abuse.
HIGH_RISK_TLDS = frozenset({"tk", "ml", "ga", "cf", "gq", "xyz", "top", "icu", "vip", "buzz", "cfd", "sbs", "click"})
# Distinctive official tokens appearing inside a NON-official host mean a lookalike domain
# ("gov-tw.com", "esunbank-tw.net"). Hosts are compared with dots and hyphens removed.
LOOKALIKE_TOKENS = (
    "govtw", "ctbcbank", "cathaybk", "esunbank", "megabank", "taishinbank", "landbank",
    "hncb", "sinopac", "firstbank", "tcbbank", "scsb", "fubon",
)
# Generic words count only as a whole dot/hyphen segment ("165-gov.com", "my-bank-login.com"):
# as substrings they would flag citibank.com.tw or shop1650.com.tw.
LOOKALIKE_SEGMENTS = ("165", "bank")
# zh-TW messages often run a URL straight into text ("請至https://www.post.gov.tw查詢"). A complete
# ASCII host followed by CJK text ends the link there; a host that is itself non-ASCII stays whole.
CJK_TEXT = re.compile(r"[\u3000-\u303f\u3400-\u9fff\uff00-\uffef]")
ASCII_HOST = re.compile(r"(?i)[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}(?::[0-9]+)?")
# Fixed, host-free wording for why a link got its grade; shown in the on-device preview and the demo.
LINK_REASON_TEXT = {
    "none": "沒有連結", "official": "政府、學校或主要銀行的官方網域", "unknown": "無法辨識的一般網域",
    "shortener": "短網址服務，看不出真正目的地", "ipv4": "直接用 IP 位址，不是網域名稱",
    "ipv6": "直接用 IPv6 位址，不是網域名稱", "userinfo": "網址用 @ 夾帶假網域",
    "non_ascii": "網域含非英數字元，可能是同形字仿冒", "punycode": "網域使用 punycode 編碼，可能是同形字仿冒",
    "lookalike": "網域夾帶政府或銀行名稱但不是官方網域", "high_risk_tld": "使用常見於釣魚的高風險頂級網域",
}


def classify_link_detail(link: str) -> tuple[str, str]:
    """Grade one matched link -> (kind, reason). Only the kind ever leaves the phone, never the URL;
    the reason is a fixed LINK_REASON_TEXT key for the on-device preview and the stage demo."""
    stripped = re.sub(r"(?i)^https?://", "", link.strip().lower())
    host = re.split(r"[/?#]", stripped, maxsplit=1)[0]
    if "@" in host or host.startswith("[email]"):
        return "suspicious", "userinfo"  # "https://www.gov.tw@evil.example/": the real host is after the @.
    if host.startswith("["):
        return "suspicious", "ipv6"  # Raw IPv6 literal: no legitimate zh-TW notification uses one.
    host = host.split(":")[0]
    host = re.sub(r"^[^a-z0-9\u0080-\U0010ffff]+|[^a-z0-9\u0080-\U0010ffff]+$", "", host)
    if not host:
        return "unknown", "unknown"
    if any(ord(c) > 127 for c in host):
        return "suspicious", "non_ascii"  # IDN/homoglyph risk the user cannot read reliably.
    labels = [label for label in host.split(".") if label]
    if labels and all(label.isdigit() for label in labels):
        return "suspicious", "ipv4"
    for official in OFFICIAL_DOMAINS:
        if host == official or host.endswith("." + official):
            return "known_official", "official"
    if any(label.startswith("xn--") for label in labels):
        return "suspicious", "punycode"  # Punycode outside the whitelist.
    compact = host.replace(".", "").replace("-", "")
    segments = re.split(r"[.-]", host)
    if any(token in compact for token in LOOKALIKE_TOKENS) or any(s in LOOKALIKE_SEGMENTS for s in segments):
        return "suspicious", "lookalike"  # Official brand token inside a non-official host.
    if labels and labels[-1] in HIGH_RISK_TLDS:
        return "suspicious", "high_risk_tld"
    for shortener in SHORTENER_DOMAINS:
        if host == shortener or host.endswith("." + shortener):
            return "shortener", "shortener"
    return "unknown", "unknown"


def classify_link(link: str) -> str:
    return classify_link_detail(link)[0]


def link_reasons(links: list[str]) -> list[str]:
    """Fixed reason keys (never hosts) for the links in a message, most severe first."""
    details = sorted((classify_link_detail(link) for link in links), key=lambda d: -_LINK_SEVERITY[d[0]])
    return list(dict.fromkeys(reason for _kind, reason in details))


def classify_links(links: list[str]) -> str:
    """The packet carries one category: the most severe among all links in the message."""
    if not links:
        return "none"
    return max((classify_link(link) for link in links), key=_LINK_SEVERITY.__getitem__)

# "不需要付款", "請勿告訴任何人" describe what NOT to do. Negation covers only the action right
# after it (plus "或／和" chains) and never crosses 請 or punctuation, so "不要猶豫請轉帳" stays risky.
ACTIONS = r"(?:付款|轉帳|匯款|支付|繳費|告訴|提供|分享|輸入|給)"
NEGATED_ACTION = re.compile(
    # "不會要求民眾提供" is how anti-scam notices phrase it; keep in sync with MessageGuard.kt.
    r"(?:不需要|不需|不用|無需|毋需|不必|請勿|不要|切勿|勿|別|"
    r"(?:絕不會|不會|從不|不得)(?:要求|請)?(?:你|您|民眾|大眾|客戶|用戶)?)(?:再|去|隨便|任意|向?(?:任何人|他人|別人))?"
    + ACTIONS + r"(?:(?:或|和|、|及)" + ACTIONS + r")*"
)
# Reported speech describes someone else's request ("如有人要求您匯款至安全帳戶，請勿照做",
# "假冒檢察官要求民眾匯款"); it is removed before detection like a negated action. The cue must be
# third-person: a scam says "我是檢察官，請匯款", a warning says "自稱檢察官要求匯款".
REPORTED_REQUEST = re.compile(
    r"(?:如有人|若有人|如果有人|若接到|如接到|假冒|冒充|自稱|詐騙集團|詐團|歹徒|騙徒|詐騙者).{0,16}"
    r"要求(?:你|您|民眾|被害人|受害者|長輩)?(?:立即|立刻|馬上)?" + ACTIONS
)
# A fact check quotes the scam it debunks. Only that quoted text is excused; a request outside the
# quotes is still a live request, so "【網傳】請立即匯款到…" cannot launder itself as a report.
QUOTED_SPAN = re.compile(r"[「『“\"][^」』”\"]{1,120}[」』”\"]")
SECRET_WORDS = re.compile(
    # "簡訊收到的6碼", "手機剛收到的數字", "點數序號": the code asked for without naming it.
    r"(驗證碼|認證碼|OTP|密碼|控制碼|提款卡|信用卡號|卡號|身分證|(簡訊|手機|電話).{0,6}(收到|傳來|裡|上)的?(數字|代碼|[0-9一二三四五六七八九十]?碼)"
    r"|點數.{0,6}序號|序號.{0,6}(給我|回傳|傳給))", re.I)
REQUEST_VERBS = re.compile(r"(提供|告訴|寄給|給我|傳給|回傳|輸入|回覆|念給|唸給)")
CREDENTIAL_PHISHING = re.compile(r"(帳戶|帳號|會員).{0,12}(驗證|登入|解鎖).{0,20}(輸入|填寫).{0,6}(資料|個資)")
PATTERNS = {
    "asks_transfer": re.compile(r"(轉帳|匯款|先匯|匯到|匯至|匯錢|匯過|轉錢|轉過|轉(到|至).{0,6}(帳|戶|銀行)|把錢|付款|支付|繳費|繳納|ATM|轉入|儲值|買.{0,4}點數)"),
    "urgency": re.compile(r"(立即|立刻|馬上|盡快|趕快|緊急|否則|限時|今天內|停權|凍結|處分|逾期|最後機會)"),
    "offers_reward": re.compile(r"(中獎|獎金|保證獲利|高報酬|穩賺|免費領|紅利|抽中)"),
    "requests_remote_control": re.compile(r"(?i)(遠端控制|遠端軟體|遠端協助|遠端桌面|螢幕分享|螢幕共享|控制碼|AnyDesk|TeamViewer|QuickSupport|AirDroid)"),
}
FINANCIAL_PROMISE = re.compile(r"(保證獲利|高報酬|穩賺|零風險|老師帶單|投資群組|內線消息|翻倍獲利)")
ACCOUNT_THREAT = re.compile(r"((帳戶|帳號|會員).{0,12}(異常|凍結|停用|停權|鎖定|被盜)|扣款失敗|交易失敗|驗證失敗)")
CONTACT_CHANGE = re.compile(r"(換(了)?(新)?(手機)?號碼|這是我的新號碼|加(我|新的?)(LINE|line)|改用.{0,8}(聯絡|帳號))")
ANTI_SCAM_CONTEXT = re.compile(r"(警方|警政署|165|反詐騙|反詐).{0,12}(提醒|呼籲|宣導|表示)")
FACT_CHECK_CONTEXT = re.compile(r"(事實查核|查核結果|謠言|假消息|不實訊息|網傳|新聞報導|媒體報導|轉傳內容|有人收到)")
DIRECT_REQUEST = re.compile(
    r"((請|麻煩|務必|記得|幫我|需要你|立刻|立即|趕快).{0,32}"
    r"(轉帳|匯款|匯到|匯至|匯錢|轉錢|轉(到|至).{0,6}(帳|戶|銀行)|轉入|把錢|付款|支付|繳費|繳納|買.{0,4}點數|提供|告訴|輸入|回覆|回傳|唸給|念給|點擊|登入|安裝|下載|聯絡|回電|加入|加我)|"
    r"(轉帳|匯款|先匯|匯錢|轉錢|付款|支付|繳費|繳納).{0,8}(給我|給我們|過來|到|至|帳戶|帳號)|"
    r"(加入.{0,8}(投資|群組)|老師帶單|私訊.{0,8}(領取|獲利)))"
)
CLICK_ACTION = re.compile(r"(點擊|點選|點|開啟|登入|進入|查看|上).{0,12}(\[LINK\]|\[OFFICIAL_URL\]|\[SHORT_URL\]|\[SUSPICIOUS_URL\])")
CONTACT_ACTION = re.compile(r"(回電|聯絡|回覆|加我|加入.{0,6}(LINE|line|群組)|私訊)")
NOTICE_CONTEXT = re.compile(r"(通知|公告|提醒|帳單|繳費單|包裹|訂單|交易紀錄)")
# "我是玉山銀行客服", "我是刑事局偵查員": the role may sit a few characters after 我是. Keep in sync
# with MessageGuard.kt.
IMPERSONATION = (
    ("family", re.compile(r"(我是(你的?)?(家人|兒子|女兒|孫子|孫女|媳婦|女婿)|換(了)?(新)?(手機)?號碼)")),
    ("official", re.compile(r"(我是.{0,6}(警察|檢察官|法官|法院|偵查員|書記官|機構人員|公務員|政府)|警察局|檢察署|地檢署|刑事局|警局|調查局|健保署|國稅局|勞保局|監理站|法務部)")),
    ("support", re.compile(r"(我是.{0,6}(客服|銀行|專員|電信)|客服人員)")),
)
VAGUE = re.compile(r"(細節還沒|還沒有說明|稍後說明|之後再說|晚點再說)")
FILLER = re.compile(r"^(這是虛構訊息。|虛構測試內容：|虛構\w*通知：)")
# Remove punctuation/symbol gaps only when they sit inside a Han phrase. Sentence delimiters
# remain boundaries, while attacks such as 轉-帳、驗💸證💸碼、匯....款 and 客／服 become detectable.
HAN_GAP = re.compile(
    r"(?<=[\u3400-\u9fff])(?:[^\w\u3400-\u9fff\[\]，。！？,!?;；:：]|_){1,8}(?=[\u3400-\u9fff])"
)
# A fact check is excused from the quoted scam only when it also passes a verdict on it; a scam
# that merely wraps itself in 「」 after a "網傳" header is still a live request.
FACT_CHECK_VERDICT = re.compile(
    r"(假消息|假訊息|假新聞|不實|謠言|是假的|詐騙(手法|訊息|簡訊|話術|集團)|澄清|查證|闢謠|駁斥|勿轉傳|勿信|勿相信|別上當|勿照做|勿理會)"
)

ADVICE = {
    "high": "高風險：這則訊息有常見的詐騙特徵。不要匯款、不要提供驗證碼或密碼、不要點連結。"
            "請用你原本就知道的電話，直接問家人或官方客服。",
    "caution": "需要核實：發現可疑訊號，但證據不足以直接判定高風險。"
               "如果跟錢或個人資料有關，請先用原本的管道確認。",
    "low_signal": "未發現明顯風險訊號，但這不代表一定安全。遇到金錢、密碼或陌生連結仍請先確認。",
    "insufficient": "資訊不足：這則訊息的內容不夠判斷。請先不要回應，可以問家人，或把完整訊息給我再看一次。",
}
REASON_TEXT = {
    "ASKS_TRANSFER": "要求轉帳或付款", "REQUESTS_SECRET": "索取驗證碼、密碼或個人資料", "URGENCY": "催促你馬上處理",
    "HAS_LINK": "含有連結", "LINK_SUSPICIOUS": "連結疑似假冒官方、IP 直連或高風險網域",
    "LINK_SHORTENER": "使用短網址，看不出真正的網站",
    "IMPERSONATION_FAMILY": "自稱家人或換了號碼", "IMPERSONATION_OFFICIAL": "自稱警察或政府機關",
    "IMPERSONATION_SUPPORT": "自稱客服或銀行", "OFFERS_REWARD": "提到中獎或保證獲利", "REMOTE_CONTROL": "要求遠端控制手機",
    "ACTION_NEGATED": "內容是在提醒不要執行高風險行動", "FINANCIAL_PROMISE": "出現保證獲利或投資帶單話術",
    "ACCOUNT_THREAT": "以帳戶異常或停權施壓", "CONTACT_CHANGE": "要求改用新號碼或其他帳號聯絡",
    "ANTI_SCAM_CONTEXT": "內容屬於反詐提醒", "FACT_CHECK_CONTEXT": "內容屬於新聞、引用或事實查核",
    "CONTEXT_INSUFFICIENT": "內容太少，無法判斷", "NO_STRONG_SIGNAL": "沒有發現明顯詐騙特徵",
    "AI_SCAM_PATTERN": "AI覆核後仍發現詐騙語意", "AI_BENIGN_CONTEXT": "AI辨識為一般通知、反詐或查核內容",
}


def split_link_text(match: str) -> tuple[str, str]:
    """Splits "https://host.tw查詢進度" into the link and the text glued after it."""
    cjk = CJK_TEXT.search(match)
    if cjk is None:
        return match, ""
    head = match[:cjk.start()].rstrip(",.;:!?)'\"")  # Sentence punctuation belongs to the text.
    host = re.split(r"[/?#]", re.sub(r"(?i)^https?://", "", head), 1)[0]
    if ASCII_HOST.fullmatch(host):
        return head, match[len(head):]
    return match, ""


def _redact_collecting_links(text: str, known_names: tuple[str, ...] = ()) -> tuple[str, list[str]]:
    """Redacts like redact() and also returns what the two link patterns matched, so link
    classification sees exactly the links that became [LINK] (never an email already redacted)."""
    # U+001C..U+001F count as whitespace in Python but not Java; map them to spaces on both sides.
    text = re.sub(r"[\x1c-\x1f]", " ", unicodedata.normalize("NFKC", text))
    text = DEFANGED_DOT.sub(".", INVISIBLE_FORMAT_CONTROLS.sub("", text))
    for name in sorted((n for n in known_names if n), key=len, reverse=True):
        normalized_name = INVISIBLE_FORMAT_CONTROLS.sub("", unicodedata.normalize("NFKC", name))
        text = text.replace(normalized_name, "[NAME]")
    links: list[str] = []
    for pattern, replacement in REDACTIONS:
        if pattern is URL_PATTERN or pattern is BARE_DOMAIN_PATTERN:
            def collect(match: re.Match[str]) -> str:
                link, rest = split_link_text(match.group(0))
                links.append(link)
                # The text after the link stays visible to the later patterns and to detection.
                return {
                    "known_official": "[OFFICIAL_URL]",
                    "shortener": "[SHORT_URL]",
                    "suspicious": "[SUSPICIOUS_URL]",
                }.get(classify_link(link), "[LINK]") + rest
            text = pattern.sub(collect, text)
        else:
            text = pattern.sub(replacement, text)
    return SALUTATION.sub("[NAME]", text), links


def redact(text: str, known_names: tuple[str, ...] = ()) -> str:
    return _redact_collecting_links(text, known_names)[0]


def _detection_view(redacted: str) -> str:
    """Compact, variant-folded text that the feature patterns run on; never shown or sent."""
    return re.sub(r"\s+", "", HAN_GAP.sub("", redacted)).translate(VARIANT_CHARS)


def extract_facts(original: str, source_kind: str) -> dict[str, Any]:
    redacted, links = _redact_collecting_links(original)
    # Detection uses a compact view so harmless spacing cannot hide phrases such as
    # "匯 款" or "驗 證 碼". The optional excerpt remains readable and unchanged.
    detection_text = _detection_view(redacted)
    fact_check_framing = bool(FACT_CHECK_CONTEXT.search(detection_text))
    if fact_check_framing:
        # Quote marks sit between Han characters, so drop the quoted scam before HAN_GAP eats the
        # marks; and only when the text around the quotes actually passes a verdict on it.
        unquoted = _detection_view(QUOTED_SPAN.sub("", redacted))
        if FACT_CHECK_VERDICT.search(unquoted):
            detection_text = unquoted
    action_negated = bool(NEGATED_ACTION.search(detection_text))
    without_negation = REPORTED_REQUEST.sub("", NEGATED_ACTION.sub("", detection_text))
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
    facts["action_negated"] = action_negated
    facts["financial_promise"] = bool(FINANCIAL_PROMISE.search(without_negation))
    facts["account_threat"] = bool(ACCOUNT_THREAT.search(without_negation))
    facts["contact_change"] = bool(CONTACT_CHANGE.search(without_negation))
    organization_warning = bool(ANTI_SCAM_CONTEXT.search(detection_text))
    direct_request = bool(
        DIRECT_REQUEST.search(without_negation) or CLICK_ACTION.search(without_negation) or
        CREDENTIAL_PHISHING.search(without_negation) or facts["requests_secret"]
    )
    if facts["financial_promise"] and not fact_check_framing:
        direct_request = True
    # A "165 提醒" or "網傳／事實查核" header is only a warning or report when nothing in the message
    # still asks the reader to act: real warnings negate the action ("請勿點擊") and real fact checks
    # quote it, and both were removed above. Letting a header override a live request let
    # "【165反詐騙提醒】帳戶異常，請立即點擊…" and "【網傳】請立即匯款到…" through as low_signal.
    facts["anti_scam_context"] = (organization_warning or action_negated) and not direct_request
    facts["fact_check_context"] = fact_check_framing and not direct_request
    facts["direct_request"] = direct_request
    if facts["anti_scam_context"]:
        facts["message_stance"] = "warning"
    elif facts["fact_check_context"]:
        facts["message_stance"] = "report"
    elif facts["direct_request"]:
        facts["message_stance"] = "direct_request"
    elif NOTICE_CONTEXT.search(detection_text):
        facts["message_stance"] = "notice"
    else:
        facts["message_stance"] = "general"
    requested = []
    if facts["asks_transfer"] and facts["direct_request"]:
        requested.append("transfer")
    if facts["requests_secret"] and facts["direct_request"]:
        requested.append("share_secret")
    if facts["has_link"] and CLICK_ACTION.search(detection_text) and facts["direct_request"]:
        requested.append("open_link")
    if facts["requests_remote_control"] and facts["direct_request"]:
        requested.append("install_remote")
    if CONTACT_ACTION.search(without_negation) and facts["direct_request"]:
        requested.append("contact")
    facts["requested_action"] = "none" if not requested else requested[0] if len(set(requested)) == 1 else "multiple"
    credential_targets = []
    if re.search(r"(\[OTP_CODE\]|驗證碼|認證碼|OTP|控制碼)", detection_text, re.I):
        credential_targets.append("otp")
    if re.search(r"(密碼|\[SECRET\])", detection_text):
        credential_targets.append("password")
    if re.search(r"(信用卡|卡號|提款卡)", detection_text):
        credential_targets.append("card")
    if re.search(r"(身分證|\[NATIONAL_ID\])", detection_text):
        credential_targets.append("identity")
    if re.search(r"(帳號|帳戶|\[BANK_ACCOUNT\])", detection_text):
        credential_targets.append("account")
    facts["credential_target"] = (
        "none" if not credential_targets else credential_targets[0]
        if len(set(credential_targets)) == 1 else "multiple"
    )
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
    if planner["kind"] == "LOCAL_QWEN_PRIVACY":
        source = "本機 Qwen 模擬雲端（隱私 packet）"
    elif planner["kind"] == "GEMINI_PROXY":
        source = "Gemini"
    else:
        source = f"本機規則（{planner['fallback_reason']}）"
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
