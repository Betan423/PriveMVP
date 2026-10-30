# AI 金孫即時資料接收端 v0.4

這個模組是 Android Collector 的本機 HTTP Receiver，只使用 Python 標準函式庫。它接收、驗證並原子保存 UTF-8 NDJSON，支援冪等重送、查詢、下載，以及提供情境判斷使用的 latest-context。

原始資料傳輸沒有登入、TLS、匿名化或隱私轉換；可疑訊息判讀只接收手機端遮蔽後的 packet。只可在你控制的電腦與受控私人網路測試，不可直接暴露到網際網路。

## 啟動

需求：Python 3.10 以上。從專案根目錄執行：

```powershell
.\receiver\run_receiver.ps1
```

預設監聽 `http://127.0.0.1:8080`，資料寫入 `receiver/data`。Dashboard 位於 `http://127.0.0.1:8080/`，每 5 秒重新整理。

讓同一 Wi-Fi 的實機連線：

```powershell
.\receiver\run_receiver.ps1 -BindAddress 0.0.0.0 -Port 8080
```

Windows 防火牆若詢問，只允許私人網路。手機 App 填入 `http://<電腦區網 IP>:8080`。

其他連線方式：

- Android Emulator：App 使用 `http://10.0.2.2:8080`。
- USB 實機：先執行 `adb reverse tcp:8080 tcp:8080`，App 使用 `http://127.0.0.1:8080`。
- 同一 Wi-Fi：Receiver 監聽 `0.0.0.0`，App 使用電腦區網 IP。

## 傳輸模式與流程

`X-Transmission-Mode` 支援四種值：

| 模式 | 用途 |
| --- | --- |
| `manual` | 使用者手動傳送本機完整歷史；未提供標頭時的預設值 |
| `baseline` | 即時工作階段的首次完整基線 |
| `periodic` | 定期狀態更新與累積事件 |
| `immediate` | 通知／畫面事件觸發的低延遲批次 |

所有 POST 可帶：

- `X-Transfer-Id`：1–128 字元；安全重試必須沿用同一 ID。
- `X-Record-Count`：正整數，若提供會在提交前核對。
- `X-Payload-Sha256`：64 位十六進位，若提供會核對原始 request bytes。
- `X-Client-Version`：1–64 個可列印 ASCII 字元。
- `X-Client-Sent-At`：正整數 Unix epoch milliseconds。
- `X-Transmission-Mode`：上述四種模式。

`baseline`、`periodic`、`immediate` 必須另外提供 `X-Collection-Session-Id`；每筆 record 的 `sessionId` 必須全部與此標頭一致。

同一 Transfer ID 只有在 payload SHA-256、模式與 collection session 都相同時才視為安全重送，回傳 `200` 與 `duplicate: true`。任一語意不同會回 `409 TRANSFER_ID_CONFLICT`，不覆寫資料。

## 手動 curl 範例

```powershell
$PayloadHash = (Get-FileHash -Algorithm SHA256 .\raw_events.ndjson).Hash.ToLowerInvariant()
$RecordCount = (Get-Content .\raw_events.ndjson | Where-Object { $_.Trim() }).Count
curl.exe -X POST `
  -H "Content-Type: application/x-ndjson" `
  -H "X-Transfer-Id: demo-001" `
  -H "X-Record-Count: $RecordCount" `
  -H "X-Payload-Sha256: $PayloadHash" `
  -H "X-Transmission-Mode: manual" `
  --data-binary "@raw_events.ndjson" `
  http://127.0.0.1:8080/api/v1/transmissions
```

成功回執範例：

```json
{
  "apiVersion": "1",
  "transferId": "demo-001",
  "recordCount": 2,
  "receivedRecords": 2,
  "bytes": 731,
  "sha256": "...",
  "receivedAt": "2026-10-28T01:23:45.678000Z",
  "transmissionMode": "periodic",
  "clientVersion": "0.4",
  "clientSentAt": 1790000000000,
  "collectionSessionId": "...",
  "sessionCount": 1,
  "sessionIds": ["..."],
  "sources": {"battery": 1, "network": 1},
  "sensitivities": {"P0": 1, "P2": 1},
  "duplicate": false
}
```

## API

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/health` | 存活狀態與接收數量 |
| GET | `/` | 每 5 秒刷新的 dashboard |
| POST | `/api/v1/transmissions` | 上傳 `application/x-ndjson` |
| GET | `/api/v1/transmissions` | 傳輸 metadata 清單 |
| GET | `/api/v1/transmissions/{id}` | 單筆 metadata |
| GET | `/api/v1/transmissions/{id}/records?offset=0&limit=100` | 分頁查看紀錄；limit 上限 1000 |
| GET | `/api/v1/transmissions/{id}/download` | 下載原始 NDJSON bytes |
| GET | `/api/v1/context/latest?sessionId=...&limitPerSource=100` | 取得同一 live session 的最新來源內容 |
| POST | `/api/v1/assist/check-message` | 可疑訊息判讀：收手機端 planner packet，回風險等級（見下節） |

latest-context 規則：

- 排除 `manual`，不會把手動歷史混入即時判斷。
- 一次只回一個 collection session；指定 `sessionId` 是正式串接的建議做法。
- 未指定時選擇接收端最後收到的 live session，只適合單機展示。
- 每個 source 選該 session 中最新、包含該 source 的傳輸批次。
- `recordCount` 是該 source 在該批次的總筆數；`returned` 是實際回傳數；`truncated` 表示受到 `limitPerSource` 截斷。
- 超過上限時保留批次中最後 N 筆，適合取得最新事件。完整歷史請使用 records 分頁或 download。

Receiver 沒有 WebSocket／SSE。Dashboard 是瀏覽器刷新；後續情境判斷服務應固定攜帶 session 並輪詢 latest-context。

## 可疑訊息判讀（CHECK_MESSAGE）

```text
手機：使用者選定一則訊息
  → 本機遮蔽（電話、OTP、帳號、網址、Email、身分證、稱呼前的姓名、已知聯絡人姓名、SYN_* 占位值）
  → 本機抽特徵（要求轉帳、索取密碼／驗證碼、催促、連結、冒充、中獎、遠端控制、內容是否足夠）
  → 組 planner packet；預設只送結構化特徵。使用者開啟「傳送遮蔽後摘錄」且本機規則判不出高風險時，才附摘錄
Receiver：POST /api/v1/assist/check-message
  → 嚴格契約驗證（未知欄位、缺雲端同意一律拒絕）
  → 禁用值掃描：命中即 422，不呼叫 AI
  → 本機規則分級 + Gemini proxy（/generate，每次無狀態）
  → AI 回應只能是白名單 sop_id／risk_level／reason_codes，否則改用規則結果
  → AI 只能提高、不能降低規則判定的風險
  → 稽核寫入 data/assist_audit.ndjson（只有決策、reason codes 與 packet SHA-256，不含摘錄）
手機：依 reason_codes 套固定文字顯示，不直接顯示 AI 句子
```

風險等級只有 `high`（高風險）、`verify`（需要核實）、`insufficient`（資訊不足），不輸出「安全」。

### 啟動 demo

Receiver 預設 8080；Gemini proxy 使用 8000。

```bash
# 1. Gemini proxy（另一個專案）
cd ~/Documents/gemini-proxymeow && .venv/bin/python -m uvicorn api:app --host 127.0.0.1 --port 8000
# 2. Receiver；--planner-url off 則只用本機規則
python -m receiver.server --port 8080 --planner-url http://127.0.0.1:8000
# 3. 手機端模擬器：單則訊息或 v5 資料集某個環境的 32 則訊息
python -m receiver.phone_sim --message "我是家人，臨時換號碼，請轉帳到 SYN_ACCOUNT_PRIVATE_0001_18" --show-packet
# 加 --allow-excerpt 才會附上遮蔽後摘錄
python -m receiver.phone_sim --message "王小明您好，明天下午三點集合" --allow-excerpt --show-packet
python -m receiver.phone_sim --dataset "dataset/AI金孫_合成原始資料集_v5_廣度擴充/dataset/source_raw_v5.json" --environment 1
# 遮蔽檢查：全部 6,144 則訊息組成的 packet 是否仍含禁用值（不送出）
python -m receiver.phone_sim --dataset "dataset/AI金孫_合成原始資料集_v5_廣度擴充/dataset/source_raw_v5.json" --scan-only
# 完整情境二證據：6,144 則合成訊息、10 個紅隊案例、3 個 Agent 邊界攻擊
make message-eval
```

設定環境變數 `PRIVAGENT_API_TOKEN` 後，Receiver 會要求 `X-Api-Token` 標頭（phone_sim 也讀同一變數）。區網展示時建議設定。

### Packet 契約（`privagent.check_message_packet.v2`）

```json
{
  "schema": "privagent.check_message_packet.v2",
  "task_id_alias": "TASK_CZFVITYL",
  "request_nonce": "REQ_QWERTYUIOPASDFGHJKLZ",
  "issued_at_ms": 1793320000000,
  "intent": "CHECK_MESSAGE",
  "locale": "zh-TW",
  "cloud_consent": true,
  "facts": {
    "asks_transfer": true, "requests_secret": false, "urgency": false, "has_link": false,
    "offers_reward": false, "requests_remote_control": false, "context_sufficient": true,
    "impersonation": "family", "source_kind": "pasted"
  },
  "redacted_excerpt": null,
  "allowed_sops": ["MESSAGE_CAUTION", "MESSAGE_VERIFY", "MESSAGE_INSUFFICIENT", "ASK_USER"],
  "client_policy_version": "phone-msg-2"
}
```

回應：

```json
{
  "api_version": "1", "task_id_alias": "TASK_CZFVITYL", "decision": "ALLOW",
  "risk_level": "high", "reason_codes": ["ASKS_TRANSFER", "IMPERSONATION_FAMILY"],
  "sop_id": "MESSAGE_CAUTION",
  "planner": {"kind": "GEMINI_PROXY", "fallback_reason": null},
  "policy_version": "server-msg-2", "privacy_policy_version": "check-message-privacy-2",
  "privacy_policy_sha256": "...", "packet_sha256": "..."
}
```

`planner.kind` 為 `RULES` 時，`fallback_reason` 說明原因：`PLANNER_DISABLED`、`PLANNER_UNREACHABLE`、`PLANNER_TIMEOUT`、`PLANNER_HTTP_<code>`、`PLANNER_BAD_ENVELOPE`、`PLANNER_INVALID_RESPONSE`。

`request_nonce` 每個任務只可使用一次；`issued_at_ms` 超過五分鐘、快於 Receiver 時鐘超過一分鐘，或 nonce 已使用時，Receiver 會在呼叫 AI 前拒絕。可執行隱私預算位於 `receiver/policies/check_message_privacy_policy.json`；允許欄位、事實用途、摘錄上限、保存規則與新鮮度限制由該檔案載入，而不是只寫在說明文件中。高風險訊息即使是惡意客戶端也不得附摘錄。

### 限制

- 遮蔽與特徵抽取是關鍵字／正規表示式規則；對 v5 訊息模板的分級結果只代表規則與模板一致，不是獨立評測。
- 正規表示式無法辨識所有人名（只處理稱呼前的姓名與手機已知的聯絡人姓名），所以摘錄預設不送。
- Gemini proxy 走網頁版帳號，不是正式 API；回應時間實測約 5–11 秒，可能因帳號狀態失效。失效時自動改用本機規則。
- AI 可提高風險等級，因此可能比規則更保守（例如把只有物流連結的訊息判成高風險）。

### 情境二評測證據

`make message-eval` 不呼叫雲端，會產生 `receiver/data/message_eval/`：

- `summary.json`：每項指標的失敗數、分母、失敗率與失敗 ID；同時列出 32 個模板家族結果。
- `message_results.csv`：6,144 則訊息的模板、預期／實際風險與 reason codes，不含訊息文字。
- `attack_results.csv`：拆字、零寬字元、否定詞、提示注入等紅隊案例結果，不含攻擊文字。
- `mutation_results.csv`：由 10 個種子、14 種分隔／符號手法產生的 140 個變形攻擊結果，不含變形後文字。
- `failures.json`：非零失敗清單；全部通過時是空物件。
- `manifest.json`：三個輸入、評測程式與輸出檔的 SHA-256，以及重跑指令。

目前基線：去識別洩漏 0/6,144、高風險漏判 0/2,112（11 個高風險模板家族皆通過）、
安全或不確定訊息誤判高風險 0/4,032、高風險非必要摘錄 0/2,112、紅隊案例失敗 0/10、
變形攻擊失敗 0/140、Agent 邊界攻擊失敗 0/3。
這些數字只證明規則對有限合成模板與明列攻擊的覆蓋，不能宣稱真實世界準確率。

## 分享照片與手機救援（`POST /api/v1/assist/plan`）

分享照片與手機救援仍使用 `privagent.planner_packet.v1` 外框、存取碼與 16 KiB 上限；`intent` 決定欄位：

- `SHARE_PHOTO`（`client_policy_version: phone-photo-1`）：`facts` 只有 `recipient_ready`、`photo_ready`、`share_app_ready`、`location_removed`、`private_background_review`、`selection`，另有 `recipient_token`（`CONTACT_[A-Z]{8}`）、`photo_token`（`PHOTO_[A-Z]{8}`）與 `confirmation: approved`。三個 ready 必須為 true：只有手機已解析完並經長輩確認的分享才會送來。`allowed_sops` 固定為 `SHARE_PHOTO_GUIDED`、`ASK_USER`、`STOP`，AI 只能讓結果更保守。
- `RESCUE_PHONE`（`phone-rescue-1`）：`facts` 是症狀類別、細項，以及**只和該症狀有關**的狀態分級（網路類只送飛航／網路／傳輸方式；聲音類只送勿擾／響鈴模式／音量分級）。Receiver 會用同一套規則重算，若手機規則其實已找到原因就以 422 拒收：雲端只在本機找不到原因時參與，且只能在 `RESCUE_INTERNET_SETTINGS`、`RESCUE_SOUND_SETTINGS`、`RESCUE_ASK_DESCRIBE`、`ASK_USER` 中挑選。

回應：`{"task_id_alias", "intent", "decision": "ALLOW", "sop_id", "reason_codes", "planner": {...}, "policy_version": "v5-core-1", "packet_sha256"}`；稽核同樣只記決策與雜湊，不記代號值。

### 政策引擎與評測（`receiver/core_tasks.py`、`receiver/core_eval.py`）

決策順序依藍圖：取消 → 缺用途授權（STOP）→ 缺資料授權（ASK）→ 收件人未設定或歧義、照片不存在、狀態過期（>300 秒）、未確認（ASK；分享被拒絕為 STOP）→ STRICT_LOCAL 或未同意雲端（LOCAL）→ 其餘 ALLOW 並建立封包。手機救援即使允許雲端，也只有本機規則找不到原因時才 ALLOW。

```bash
make core-eval   # = python3 -m receiver.core_eval --dataset <source_raw_v5.json> --out receiver/data/core_eval
make golden      # 改了 core_tasks.py 後重新產生 app/src/test/resources 的 Kotlin 對照資料
```

目前結果（seed 20261107）：1,728 筆、92 個封包；直接識別值外洩、非必要揭露、授權違反、跨任務代號重用、封包契約不符皆為 0／92；任務解析與資料集作者參數不一致 0／1,728。任務解析與規則是對著這些模板寫的，這些數字代表一致性，不是對新說法的泛化能力。

Android 端（`CoreTasks.kt`）以 `core_interpret_golden.tsv`（487 句）與 `core_rescue_golden.tsv`（4,074 組狀態）比對 Python 結果；`CORE_PACKETS_OUT=<檔案> make test-android` 會輸出 Kotlin 封包，供 `validate_plan_packet` 驗證。

## 資料契約與錯誤

每行必須是 UTF-8 JSON object，必要欄位為：

```text
schemaVersion, ts, sessionId, source, sensitivity, payload
```

預設整批上限 25 MiB、單行上限 1 MiB，支援 `Content-Length` 與 HTTP chunked request。所有錯誤使用固定 envelope：

```json
{"error":{"code":"INVALID_NDJSON","message":"...","details":{"line":3}}}
```

Android 端只自動重試網路 I/O、HTTP 408、429 與 5xx。其他 4xx／409 或回執 ID、筆數、SHA、模式、session 不一致會停止即時服務，避免無限重試或語意漂移。

## 儲存規則

- 正式資料：`receiver/data/{transferId}.ndjson`
- Metadata：`receiver/data/{transferId}.metadata.json`
- 上傳先寫入 `receiver/data/.incoming`，整批驗證成功才原子提交。
- 失敗上傳會移除暫存檔；既有 ID 永不覆寫。
- `receiver/data` 已由 `.gitignore` 排除，避免誤提交接收內容。

## 測試

從專案根目錄執行：

```powershell
python -m unittest discover -s receiver/tests -v
```

或一次驗證 Receiver、Android APK、JVM tests 與 Lint：

```powershell
.\verify_mvp.ps1
```

測試涵蓋資料契約、Unicode、原子寫入、固定與 chunked body、冪等重送、語意衝突、live session、latest-context、最後 N 筆語意、查詢下載、大小限制與失敗無殘留。
