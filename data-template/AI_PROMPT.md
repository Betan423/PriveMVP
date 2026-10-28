# AI 生成提示詞

以下文字可直接複製給 AI。只附本套件的 schema、情境規格與虛構範例；不要附原始手機資料。若修改情境，先同步修改 `scenarios.json` 再驗證。本檔沒有任何真人內容。

## 可複製提示詞

你是模擬資料產製助手。請依下面規格生成「手機使用與通知情境」自創原始資料集。這些是設計的虛構情境，不是老人族群實測，不得描述成真實行為，不要從其他資料引用真實個人資訊。

只輸出 UTF-8 NDJSON，每行一個完整 JSON 物件。不要 Markdown、註解、說明、陣列或省略號。以完整使用者為單位分批；無法一次完成時只完成第一位使用者，不要輸出不完整 JSON。續批不可重用 event_id。不要複製下面範例的事件來湊數；範例只解釋格式。

本批次：6位虛構使用者，每人1天，day_index=0。
- SYN_U001、SYN_U002：routine。
- SYN_U003、SYN_U004：notification_burst。
- SYN_U005、SYN_U006：low_activity。

每位使用者只能有一種 scenario_id。所有 event_id 必須唯一，從 SYN_E000001 遞增。同一位使用者的輸出按 day_index、offset_sec 非遞減排序。

每行必須恰好有以下9個欄位：
`template_version, synthetic, user_id, event_id, scenario_id, day_index, offset_sec, event_type, payload`。
- template_version 固定字串 "1.0"；synthetic 固定布林 true。
- user_id 格式 SYN_U加3到6位數字；event_id 格式 SYN_E加6到10位數字。ID不得編碼真實個資。
- day_index 為0到364的整數；offset_sec 為0到86399的整數，只表示模擬當天的相對秒數，不是真實時間。
- scenario_id 只能是 routine、notification_burst、low_activity。

event_type 和 payload：
1. app_usage：只含 app_category、duration_sec。duration_sec 為整數且1到7200，區間不得跨日、同一使用者的前景使用區間不得重疊。這是虛構區間，不代表已從 Android 原始事件重建。
2. notification：只含 app_category、notification_kind、message_length_band、content_redacted。事件次數不等於獨立訊息或閱讀次數。
3. device_state：只含 battery_band、charging、network_class、internet_available。

可用值：
- app_category：communication、social、video、utility、navigation、other。
- notification_kind：message、reminder、system、promotion、other。
- message_length_band：none、short、medium、long、unknown；分別代表無文字、虛構1-20字、21-80字、81字以上、未知。不可输出任何實際文字。
- content_redacted：布林；true時message_length_band必須unknown。
- battery_band：low、medium、high、unknown；前三者為虛構0-20%、21-60%、61-100%的分級。
- charging：布林。
- network_class：wifi、cellular、offline、other、unknown。
- internet_available：布林或null；offline必須false，unknown必須null。

每人每天的情境約束：
- routine：2-4筆app_usage，每筆60-1200秒、總時長不超過4800秒；4-8筆notification；3筆device_state。
- notification_burst：3-6筆app_usage，每筆60-1200秒、總時長不超過7200秒；12-24筆notification，至少12筆在任意一個600秒窗口內；3筆device_state。
- low_activity：1-2筆app_usage，每筆60-300秒、總時長不超過600秒；0-1筆notification；3筆device_state。
- 所有情境的device_state必須各在28800、43200、72000秒產生一筆。
- 讓不同使用者的應用類別、使用時間與通知間隔有所變化。不要將情境與電量或網路固定綁定。不要聲稱這是符合真實族群分布的統計模型。
- 通知可以出現在使用區間內。不得自行新增「已讀」「卡關」「失能」「需要幫助」標籤；沒有這些標籤的真實依據。

禁止任何额外欄位、自由文字、姓名、電話、email、URL、地址、經緯度、原始套件名、androidId、真實日期、通知title/text/bigText/subText及任何真人識別碼。不得讓ID或數字隱藏編碼私人資訊。

格式例子，全部為虛構：
{"template_version":"1.0","synthetic":true,"user_id":"SYN_U001","event_id":"SYN_E000001","scenario_id":"routine","day_index":0,"offset_sec":28800,"event_type":"device_state","payload":{"battery_band":"high","charging":false,"network_class":"cellular","internet_available":true}}
{"template_version":"1.0","synthetic":true,"user_id":"SYN_U001","event_id":"SYN_E000002","scenario_id":"routine","day_index":0,"offset_sec":30000,"event_type":"notification","payload":{"app_category":"communication","notification_kind":"message","message_length_band":"short","content_redacted":false}}
{"template_version":"1.0","synthetic":true,"user_id":"SYN_U001","event_id":"SYN_E000003","scenario_id":"routine","day_index":0,"offset_sec":30100,"event_type":"app_usage","payload":{"app_category":"communication","duration_sec":180}}

開始生成，輸出資料而不附說明。

## 分批續生成

如果AI只完成一位使用者，下一次仍附上相同規格，另指示：

「本次只生成 SYN_U002，情境 routine，day_index=0；event_id 從上一批最大值加1開始。請勿輸出其他使用者。」

按實際狀況替換使用者、情境與起始ID。合併檔案後再做完整批次驗證，不因單批通過就認定整份通過。提示詞、輸出與人工修改需另外保留於生成紀錄。
