# 原始資料與模板的對照

參考檔為使用者提供的 `raw_events_20261011_163230.ndjson`。本文件只記錄結構與彙總檢查資訊，沒有原始內容值。檔案 630 筆 JSON 紀錄、schemaVersion 2、單一蒐集 session、兩次裝置快照，另有 18 筆 `notification_posted`。session 數不代表受試者數；本次是單一手機测试，不具老人族群代表性。

## 欄位取捨

| 實際來源與欄位 | 模板映射 | 證據與限制 |
|---|---|---|
| usage_events.items: package、type、timestampMs | app_usage 的應用類別、相對起始時間 | 知道有時間化使用事件；區間配對與時長為虛構設計，沒有從原始事件還原 |
| usage_stats.items: totalTimeForegroundMs | app_usage.duration_sec | 單位概念可參考；不複製真實時長、不學習族群分布 |
| notification_posted: package、category、postTime | notification 的應用類別、通知類別、相對時間 | 分類值為新設計；18 次是事件次數，不是18位發訊者或18則獨立訊息 |
| notification_posted: title、text、bigText、subText | message_length_band | 原文字全部排除；長度分級是虛構值，不計算或保留真實文字长度 |
| notification_posted: contentRedacted | content_redacted | 保留遮罩狀態的結構概念；不代表遮罩已涵蓋所有敏感資料 |
| battery: capacityPercent、charging | device_state 的 battery_band、charging | 只參考型別，改用虛構分級電量 |
| network: connected、transports、internetValidated | device_state 的 network_class、internet_available | 分類為設計映射；不宣稱其餘連線類型都在實測中出現 |
| device: androidId、model 等 | 排除 | 不需要識別真實裝置 |
| contacts、calendar、location、images、videos、audio | 排除 | 不属于目前下游任務的必要欄位 |
| notification_snapshot | 不當作新通知事件 | 是現存通知快照，不能和即時通知直接累加 |
| accessibility_event | 不設操作錯誤、點擊或卡關欄位 | 此檔為0筆；無證據支持操作困難標籤 |

## 資料限制

- images、usage_stats、usage_events 有截斷標記。
- 两次快照內容可能重複，不能把原始行數當作獨立樣本。
- 原始 usage_events 的限制會保留查詢區間較舊的事件，不能用來估計完整近期使用分布。
- 原始資料仍含私人內容；未將它上傳給生成服務，也未製作可公開的真人資料集。

## 設計假設而非觀察結果

情境配額、時間窗、使用時長範圍、電量分級、六種應用分類、不同網路狀態，全是團隊可調整的測試設定。不得稱為資料學習出的分布，亦不能據此推論年齡、失能程度、疾病或個人風險。

模板關注手機使用與通知負荷；如果後續要測試「老人操作卡關」，應另外建立操作事件規格、標籤定義與真人/獨立測試依據，而不是新增隨機卡關標籤充當真實證據。
