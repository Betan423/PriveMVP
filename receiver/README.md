# AI 金孫即時資料接收端 v0.4

這個模組是 Android Collector 的本機 HTTP Receiver，只使用 Python 標準函式庫。它接收、驗證並原子保存 UTF-8 NDJSON，支援冪等重送、查詢、下載，以及提供情境判斷使用的 latest-context。

本版沒有登入、TLS、匿名化或隱私轉換。只可在你控制的電腦與受控私人網路測試，不可直接暴露到網際網路。

## 啟動

需求：Python 3.10 以上。從專案根目錄執行：

```powershell
.\receiver\run_receiver.ps1
```

預設監聽 `http://127.0.0.1:8000`，資料寫入 `receiver/data`。Dashboard 位於 `http://127.0.0.1:8000/`，每 5 秒重新整理。

讓同一 Wi-Fi 的實機連線：

```powershell
.\receiver\run_receiver.ps1 -BindAddress 0.0.0.0 -Port 8000
```

Windows 防火牆若詢問，只允許私人網路。手機 App 填入 `http://<電腦區網 IP>:8000`。

其他連線方式：

- Android Emulator：App 使用 `http://10.0.2.2:8000`。
- USB 實機：先執行 `adb reverse tcp:8000 tcp:8000`，App 使用 `http://127.0.0.1:8000`。
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
  http://127.0.0.1:8000/api/v1/transmissions
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

latest-context 規則：

- 排除 `manual`，不會把手動歷史混入即時判斷。
- 一次只回一個 collection session；指定 `sessionId` 是正式串接的建議做法。
- 未指定時選擇接收端最後收到的 live session，只適合單機展示。
- 每個 source 選該 session 中最新、包含該 source 的傳輸批次。
- `recordCount` 是該 source 在該批次的總筆數；`returned` 是實際回傳數；`truncated` 表示受到 `limitPerSource` 截斷。
- 超過上限時保留批次中最後 N 筆，適合取得最新事件。完整歷史請使用 records 分頁或 download。

Receiver 沒有 WebSocket／SSE。Dashboard 是瀏覽器刷新；後續情境判斷服務應固定攜帶 session 並輪詢 latest-context。

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
