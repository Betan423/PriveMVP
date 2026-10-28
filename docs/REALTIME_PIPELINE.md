# 即時資料管線 v0.4：架構、契約與分工基線

這份文件描述目前「先能傳遞」版本的實作邊界，作為後續情境判斷、隱私處理與多人平行開發的共同基礎。v0.4 傳送的是原始資料，沒有把匿名化或任務授權混入傳輸核心。

## 執行流程

```text
使用者啟動
  → 建立 collection session
  → 完整基線蒐集 + 累積通知／畫面事件
  → 等待設定的 baseline 秒數
  → baseline 批次傳送
  → 定期輕量蒐集（裝置／電池／網路／位置）
  → periodic 批次傳送

通知／畫面事件（基線完成後）
  → 寫入本機加密紀錄
  → 放入記憶體 outbox
  → 2 秒 debounce
  → immediate 批次傳送
```

每一筆資料會先成功寫入本機加密紀錄，再加入即時 outbox。即時傳送成功不刪除本機資料。

## 模組邊界

### Android：蒐集與本機紀錄

| 模組 | 責任 | 不負責 |
| --- | --- | --- |
| `CollectionControl` | 同意、來源選擇、session 生命週期 | 網路傳送 |
| `Collectors` | 完整基線與定期輕量快照 | 重試、HTTP |
| `ResearchNotificationListener` / `ResearchAccessibilityService` | 事件來源、既有節流與遮蔽 | 批次管理 |
| `ResearchLog` | 加密落盤、統一 record envelope、發布 live record | 決定何時傳送 |

### Android：即時傳輸

| 模組 | 責任 | 關鍵保證 |
| --- | --- | --- |
| `RealtimeConfig` | 基線秒數、週期、立即事件設定 | 範圍驗證 |
| `RealtimeRuntime` | 跨 Activity／Service 的程序內狀態 | stop/revoke 後拒絕新事件 |
| `RealtimeOutbox` | 有界佇列與 in-flight 批次 | 重試使用相同 bytes 與 Transfer ID |
| `RealtimeTransmissionService` | 前景服務、排程、狀態機、序列化 flush | baseline → periodic；事件 debounce |
| `RawDataSender` / `TransferProtocol` | HTTP、hash、標頭、回執核對 | 只對可恢復錯誤重試 |

### 電腦 Receiver

| 模組 | 責任 | 關鍵保證 |
| --- | --- | --- |
| `receiver/contracts.py` | 標頭與 record envelope 驗證 | 穩定錯誤碼 |
| `receiver/storage.py` | 暫存、hash、原子提交、冪等、latest-context | ID 不覆寫；live session 不混合 |
| `receiver/server.py` | HTTP 路由、Dashboard、查詢 | 標準函式庫即可運行 |

## 傳輸契約

Endpoint：`POST /api/v1/transmissions`

必要或常用標頭：

| Header | manual | live | 說明 |
| --- | --- | --- | --- |
| `Content-Type: application/x-ndjson` | 必填 | 必填 | UTF-8 NDJSON |
| `X-Transfer-Id` | App 必填 | 必填 | 同一重試不可改 |
| `X-Record-Count` | App 必填 | 必填 | 接收端核對 |
| `X-Payload-Sha256` | App 必填 | 必填 | 對 request bytes 計算 |
| `X-Transmission-Mode` | `manual` | `baseline/periodic/immediate` | live mode 表示觸發原因 |
| `X-Client-Version` | 建議 | 必填 | 目前為 `0.4` |
| `X-Client-Sent-At` | 建議 | 必填 | epoch milliseconds |
| `X-Collection-Session-Id` | 不需要 | 必填 | 必須等於所有 record 的 sessionId |

成功回執至少核對 `transferId`、`receivedRecords`、`sha256`；live 另核對 `transmissionMode` 與 `collectionSessionId`。

## 批次與重試語意

- outbox 最大 5000 筆／20 MiB；單批最大 500 筆／5 MiB。
- `beginBatch()` 後 payload 與 Transfer ID 凍結，直到接收端完整回執才 acknowledge。
- 新事件只會排在 in-flight 批次之後，不改變重試內容。
- I/O、HTTP 408、429、5xx：保留批次，下一個週期或事件觸發時重試。
- 其他 4xx、409、回執完整性不一致或程式錯誤：停止即時模式。
- 成功 HTTP 但回執缺欄位視為可重試；同一 ID 可取得 duplicate ack。
- mode 是「這次 flush 的觸發原因」，不是批次中每筆 record 的來源分類。

## 情境判斷讀取介面

`GET /api/v1/context/latest?sessionId=<id>&limitPerSource=100`

- 永遠排除 manual 傳輸。
- 不混合 collection session。
- 每個 source 取最新包含它的 live transmission。
- 超過來源上限時保留最後 N 筆。
- 正式決策模組必須固定傳入 session；省略 session 只用於單機展示。

目前是 pull model。決策模組可每 2–5 秒輪詢；需要低延遲 push 時，再新增 SSE、WebSocket 或事件匯流排，不修改 Collector record envelope。

## 可平行分工

1. Android 蒐集組：新增來源、調整 snapshot/event 策略；只經 `ResearchLog.append` 發布。
2. Android 傳輸組：持久化 outbox、WorkManager／前景服務協調、網路策略；不解析業務 payload。
3. Receiver 組：驗證、儲存、查詢、認證與 TLS；維持冪等契約。
4. Context 組：collection cycle、跨批次來源合併、device instance 與 latest-context schema。
5. Scenario 組：只讀 context API，產生情境特徵與判斷；不直接碰 Android 權限。
6. Privacy 組：未來插在 `ResearchLog` 到 outbox 之間，以新 envelope 欄位表達 purpose／transform；不破壞原始資料保存與重試層。
7. QA 組：Android 版本矩陣、斷網／重連、程序終止、大量事件、接收端重啟與多 session 交錯。

## 下一版優先項目

1. 磁碟持久化 outbox 與程序重啟恢復。
2. `clientInstanceId`，避免多手機共用 Receiver 時依全站最新 session 推斷。
3. `collectionCycleId`、batch sequence、final flag，正確合併被容量切開的同一輪來源快照。
4. Receiver 認證、HTTPS、允許來源與資料保留政策。
5. SSE／事件匯流排與決策服務 checkpoint。
6. Android 15+ 長時間排程策略，避免依賴無限期 dataSync 前景服務。
7. 隱私轉換、最小揭露與任務授權欄位。

## MVP 已知限制

- outbox 是程序內記憶體；程序死亡時未確認批次不會自動恢復，但加密本機紀錄仍存在。
- Receiver 無身份驗證，並允許開發用 HTTP。
- Android 15+ dataSync 前景服務有總時數限制，到期後需重新啟動。
- latest-context 目前以「來源最新單一 transmission」為單位；大型基線若跨批，完整重建應改用 records/download，或等待 cycle-aware 合併。
- 本版沒有任何匿名化；不可用真人敏感資料在不受控網路測試。
