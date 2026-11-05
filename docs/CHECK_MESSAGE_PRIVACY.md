# 情境二：可疑訊息判讀的隱私與防禦設計

> 目前App正式採用本文的隱私特徵／遮蔽摘錄路徑。本機Qwen只作為未來雲端API的免費替身，接收相同privacy packet，不接收額外未遮蔽原文；同資料集結果見 [`PRIVACY_LOCAL_QWEN_EVALUATION.md`](PRIVACY_LOCAL_QWEN_EVALUATION.md)。原文Qwen只保留離線評測數字作歷史效能上限，HTTP端點已移除。

## 目前採用的架構

```text
使用者貼上／選取一則通知
  → 手機本機正規化與去識別化
  → 手機本機抽取最小風險特徵
  → 本機規則先建立不可降低的風險底線
  ├─ 未同意雲端：直接顯示本機結果
  └─ 同意雲端：顯示即將揭露的內容並再次確認
       → 嚴格 planner packet（預設只有布林特徵、連結網域分級與任務代號）
       → Receiver 再做契約與禁用值檢查
       → 無狀態 AI Agent 只能選白名單 SOP／風險／原因碼
       → 格式錯誤、逾時、越權一律退回本機規則
       → AI 可以提高警戒，但不能降低本機風險
       → App 用固定文案顯示，不直接執行 AI 文字
```

## 實際使用的隱私與安全技術

| 位置 | 技術 | 在本專案的用途 |
| --- | --- | --- |
| Android 手機 | 資料最小化 | 只處理使用者選定的一則訊息；預設封包只含 14 個布林特徵與 6 個列舉值（連結網域分級、冒充類型、語氣、要求動作、憑證目標、來源類型）。 |
| Android 手機 | 本機處理（edge processing） | 去識別、特徵抽取與規則風險底線都在手機完成；未同意雲端時不連網。 |
| Android 手機 | 去識別／遮蔽 | 電話、OTP、帳號、URL、Email、身分證、合成私密值、稱呼姓名與已知聯絡人姓名改成類型占位符。 |
| Android 手機 | 本機連結網域分級 | URL、路徑與查詢參數都不出手機；手機端把每個連結分級為官方白名單（gov.tw、edu.tw 與主要銀行）、短網址、可疑（假冒拼寫、`@` 夾帶、IP 直連、punycode、非 ASCII 網域、高風險 TLD）或無法辨識，封包只送 `link_kind` 類別值。官方通知不再只因「連結＋催促」被判高風險；釣魚網域即使語氣平靜也直接高風險。見下節。 |
| Android 手機 | Unicode 正規化與反規避 | NFKC 統一全形字；移除零寬與 bidi 控制字；特徵掃描另用去空白視圖，避免 `匯 款`、`驗​證碼` 規避。 |
| Android UI | 明確同意與分層揭露 | 雲端開關預設由使用者決定；傳送前顯示實際特徵與是否包含摘錄。摘錄另行 opt-in，且本機已判高風險時不傳。 |
| Packet | 假名化／不可關聯代號 | 每項任務使用新的隨機 `TASK_[A-Z]{8}`，不送手機 ID、帳號或永久使用者 ID。 |
| Packet | 新鮮度與重播防護 | v2 封包加入一次性 `REQ_...` nonce 與簽發時間；超過五分鐘、時鐘過度超前或 nonce 重用時，在呼叫 AI 前拒絕。 |
| Policy-as-Code | 可執行隱私預算 | JSON 政策定義唯一允許欄位、每個事實的用途、摘錄限制、保存方式與封包新鮮度；Receiver 啟動時驗證並載入。 |
| Receiver | 零信任契約 | 嚴格拒絕未知／缺少欄位、錯誤型別、重複 JSON key、超過 16 KiB、未同意雲端與不在白名單的值。 |
| Receiver | 第二層 DLP | 封包再次掃描電話／長數字、URL、Email、身分證、OTP 與 `SYN_*`；也先移除零寬控制字，避免惡意客戶端繞過。 |
| AI Agent | 能力白名單 | AI 只能回傳 `sop_id`、三級風險與固定 reason codes，不能回電話、網址、自由文字或執行動作。 |
| AI Agent | Prompt injection 隔離 | 摘錄被標示為不可信資料；輸出必須是完整單一 JSON。前後夾文字、額外欄位或越權 SOP 全部拒絕。 |
| 決策合併 | 單調安全策略 | 本機風險是 floor；AI 只能提高警戒。AI 故障、逾時或輸出無效時 fail closed 到本機規則。 |
| 稽核 | 最小化稽核 | 只記決策、reason codes、揭露欄位名稱與 packet SHA-256，不記原始訊息或遮蔽後摘錄。 |
| 評測 | 可重現證據 | `message_eval.py` 保存輸入／引擎／輸出 SHA-256、逐筆結果與失敗清單，但不保存訊息文字。 |

## 資料經過處理後的例子

原始訊息只留在手機：

```text
我是客服，請立即把驗證碼 482913 回傳，網址 https://bank.example/login
```

手機內部的遮蔽結果（只有使用者另行允許摘錄時才可能送出）：

```text
我是客服，請立即把驗證碼 [OTP] 回傳，網址 [LINK]
```

預設真正送出的資料：

```json
{
  "facts": {
    "asks_transfer": false,
    "requests_secret": true,
    "urgency": true,
    "has_link": true,
    "link_kind": "unknown",
    "offers_reward": false,
    "requests_remote_control": false,
    "context_sufficient": true,
    "impersonation": "support",
    "source_kind": "pasted"
  },
  "redacted_excerpt": null
}
```

## 連結：為什麼送 `link_kind` 分級，而不是網域或網址

舊版封包只有 `has_link` 一個布林值。網址被整段遮成 `[LINK]` 後，雲端與本機規則都無法分辨「郵局官方追蹤連結」和「釣魚網域」，只能用「連結＋催促＝高風險」這種粗規則，官方通知容易被誤判成高風險，語氣平靜的釣魚連結又會漏掉。評估過兩種補救方式：

| 方案 | 雲端看到什麼 | 隱私成本 | 訊號 | 採用 |
| --- | --- | --- | --- | --- |
| A. 手機抽特徵，只送類別值 `link_kind` | `none / known_official / unknown / shortener / suspicious` 五選一 | 接近零：一個五值枚舉，不含任何網址片段 | 白名單、短網址、IP 直連、punycode、仿冒拼寫、高風險 TLD 都在手機判完 | **是** |
| B. 只送 registrable domain（eTLD+1） | 例如 `esunbank.com.tw`、`example-clinic.com.tw` | 中：網域本身會透露往來銀行、醫院、交友或宗教網站等敏感關係，且 Receiver 的禁用值掃描必須對網址開洞 | 雲端可查黑名單、看拼寫 | 否 |
| B'. Safe Browsing 式 hash 前綴比對 | 網域 SHA-256 的前 4 bytes | 低（k-匿名），但需要雲端維護一份黑名單資料庫並回傳候選雜湊 | 只能回答「是否在黑名單」，看不出短網址、IP、仿冒拼寫 | 未來可加在 A 之上 |

選 A 的理由：它符合本專案「雲端只拿布林／枚舉特徵」的既有邊界，不需要在 Receiver 的 DLP 規則開例外（網址明文仍一律拒收），而且 A 能提供的訊號比 B 多。B' 不是 A 的替代而是補強：若未來接入 165 或 NCC 的黑名單，可在手機先算雜湊前綴，再把命中結果併入 `suspicious`，仍然只送類別值。

手機端分級規則（Android `MessageGuard.classifyLink` 與 `receiver/phone_sim.py` 逐條相同，由 golden case 與 `test_link_lists.py` 固定）：

1. `@` 夾帶（`https://www.gov.tw@evil.example/`）、IPv6／IPv4 直連、非 ASCII 網域 → `suspicious`。
2. 主機以整段 DNS label 比對官方白名單（`gov.tw`、`edu.tw`、主要銀行）→ `known_official`；`post.gov.tw.verify.cc` 不會命中。
3. punycode（`xn--`）、網域去掉點與連字號後含 `govtw`、`165`、`bank` 或銀行品牌字串、高風險 TLD（`.xyz`、`.top`、`.icu`、`.tk` 等）→ `suspicious`。
4. 短網址服務（`reurl.cc`、`lihi.cc`、`bit.ly`、`lin.ee` 等）→ `shortener`。
5. 其餘 → `unknown`。一則訊息有多個連結時送最嚴重的一個。

對風險規則的影響：`known_official` 的連結不再單獨觸發「連結＋催促／獎勵／冒充」的高風險；`suspicious` 單獨即為高風險；`shortener` 與 `unknown` 維持舊規則並多附一個原因碼。匯款、索取驗證碼、遠端控制等其他高風險條件不受連結分級影響，所以官方白名單不會把真正的詐騙降級。

誠實的限制：

- `link_kind` 仍是一個語意特徵。`known_official` 會告訴雲端「這則訊息含政府或銀行連結」，比 `has_link` 多洩漏一點點；這與其他布林特徵一樣受 [PETs Prize 紅隊「布林旗標推回」](../HANDOFF_SCENARIO_1_2_PRIVACY.md) 的提醒約束，未同意雲端時不會送出。
- 白名單與短網址清單是示範用清單，不是完整登錄；上線前應改成可更新的資料檔並定期維護。
- `bank` 這類通用字串會把所有不在白名單的「bank」網域判成可疑（例如 v5 資料集的 `bank.example.invalid` 192 則），這是刻意保守，但代價是未列入白名單的合法銀行會被判高風險。
- 分級只看主機名稱，不看路徑；這是刻意的，因為路徑與查詢參數最常夾帶 session token 與個資。

## 為什麼目前不使用 FL、DP、HE、SMPC

本流程是單筆訊息推論，沒有跨使用者集中訓練，也沒有多方共同計算。聯邦學習（FL）要解決的是分散式模型訓練；差分隱私（DP）通常保護統計發布或訓練更新；同態加密（HE）與安全多方運算（SMPC）適合把計算拆到不互信的資料持有者。因此在目前架構加入它們，會增加延遲與複雜度，卻沒有清楚的受保護訓練或多方運算目標。

若未來真的加入「跨手機共同改善詐騙模型」，才評估：本機訓練／安全聚合的 FL、裁剪與加噪的使用者層 DP、更新驗證與投毒防禦。不能只為了展示技術而在有限合成模板上訓練模型；那樣很可能只記住模板，競賽證據反而較弱。

## 評測與證據邊界

執行 `make message-eval` 會評估 6,144 則 v5 合成訊息、32 個模板家族、10 個人工紅隊案例、由 10 個種子與 15 種手法產生的 150 個變形攻擊，以及 3 種 Agent 邊界攻擊。當前結果全部為零失敗，包括直接識別值洩漏、高風險漏判、高風險非必要摘錄、安全／不確定訊息誤判、上下文不足漏判與 Agent 降級攻擊。

`make inference-attack` 則從攻擊者視角量同一批封包：只看 20 個特徵欄位能推回風險等級 100%（封包的用途）、模板家族 61.5%、原文 16.7%，最小等價類 k=64，192 支手機沒有一支能被唯一指認；開啟摘錄後家族 100%、原文 56.3%。見 [CHECK_MESSAGE_INFERENCE_ATTACK.md](CHECK_MESSAGE_INFERENCE_ATTACK.md)。

這不是實際詐騙偵測準確率。v5 是有限且相關的作者模板，`semantic_tags` 也不是獨立人工真值。下一階段應增加由不同人員撰寫、模板家族完全隔離、包含口語錯字與新型詐騙的盲測集，再決定是否值得訓練小型本機分類器。
