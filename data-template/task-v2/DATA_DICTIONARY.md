# 實際應用資料字典與模板邊界

資料夠不夠不是看行數，而是能否支持每個任務的來源、解析、判斷、權限與結果。此次任務資料刻意把私人資源與雲端上下文分開。

## 初始化層

| 真正系統欄位 | 本機用途 | 任務模板表示 | 現有原始檔能否直接供應 |
|---|---|---|---|
| user_preferences | 字級、語言、語音/文字方式、確認習慣 | 設計文件，尚未在模板逐項生成 | 未蒐集，須由使用者設定 |
| relation_binding | 使用者指定「女兒」的本機收件人映射 | recipient_binding=ready/missing/ambiguous | 不行；contacts沒有可靠家人關係 |
| os_and_app_capabilities | OS API/語音服務/分享App能力 | share_app與狀態unknown | 有裝置和可見App資訊，但不是全部能力探測 |
| consent_and_grants | 各任務來源與可選雲端範圍 | grants、privacy_mode | 需由任務入口產生，不能用collector總同意取代 |
| sop_registry_version | 允許的固定腳本及前置條件 | sops.json版本2.0 | 新增設計 |

## 持續狀態層

| 真正系統欄位 | 本機用途 | 任務模板表示 | 現有原始檔能否直接供應 |
|---|---|---|---|
| current_ui_guard | 防止錯畫面執行 | ui_state=ready/unknown | 有accessibility collector程式，但樣本無事件；執行guard待實作 |
| available_network | 區分網路/雲端可用性 | network_connected，unknown用null | 有network快照；仍需任務當下重讀 |
| airplane/dnd/ringer/volume | 救援診斷 | 四種狀態枚舉 | 尚未蒐集 |
| selected_message_source | 使用者選定通知/分享輸入 | message_state | 有通知結構，不等於已實作「選一則」 |

不納入整屏原文、背景剪貼簿、所有來電號碼、定位與照片清單。這些不是三個場景的共通必要條件。

## 任務即時層

| 真正系統欄位 | 本機用途 | 任務模板表示 | 雲端邊界 |
|---|---|---|---|
| raw_audio/transcript | 本機語音辨識與意圖解析 | utterance為固定虛構命令表 | 不外傳；現有模擬器不處理音訊 |
| selected_photo_uri | 待分享照片本機引用 | photo_state，沒有URI或像素 | AI只見photo_ready；LINE/收件人收到照片是另行確認的預期揭露 |
| selected_recipient | 使用者指定收件人與本機參數 | recipient_binding | AI只見recipient_ready，不能反查真人 |
| selected_message_text | 本機抽線索，不做自動執行指令 | features六個旗標 | 不傳原文；沒有原文也不能宣稱已完成文字分類器 |
| confirmation/cancel | 防止誤傳、誤改設定 | confirmation、cancelled | 真正授權由本機UI/任務狀態供應，不由模型供應 |
| task_result_evidence | 證明送出/修復/提醒可用 | success=null | 模擬資料無實機證據，不能隨機生成true |

## 可生成的資料與必須實測的資料

AI可生成：模擬命令、抽象任務狀態、權限組合、確認/取消組合与虛構線索。這些可測路由和資料政策。

必須另實作/實測：ASR輸入、關係綁定、照片URI權限、LINE當前UI、實際系統讀值、真正訊息抽特徵、執行結果與真實雲端封包。AI填成ready或success=true不會使真實App具備功能。

若後續需要訓練訊息文字分類器，另設完全虛構的文本基準、獨立人工標註與私密字串canary；不得拿這版六個旗標的規則結果當成真實詐騙標籤。若需要完整Android狀態還原，另設本機私密資料契約，不擴大雲端白名單。
