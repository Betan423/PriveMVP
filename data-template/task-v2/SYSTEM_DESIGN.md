# 保護隱私的長輩語音手機輔助系統設計

日期2026-10-11。依使用者提供的三場景指引與現有App程式碼設計。這份文件是規格與實作路線，不是聲稱App已完成。

## 核心決策

AI只處理任務語意與固定SOP的選擇，私人資料解析與手機操作留在本機。執行器必須獨立檢查權限、腳本白名單、當前狀態、明確確認、取消與後置條件；模型說可以執行不代表真的可以。

```text
使用者語音或文字
  → 本機輸入與意圖解析
  → 任務資料閘門與本機別名/資源解析
  → STRICT_LOCAL：本機規則/可選本機模型
    或 MINIMIZED_CLOUD：同意後只送任務所需抽象狀態
  → AI只回傳白名單SOP ID
  → 本機再次驗證、顯示確認
  → 固定執行器/使用者操作
  → 重新讀狀態或驗證結果
  → 清除任務暫存與最小化稽核
```

「不把資訊揭露給AI」不能與「雲端AI理解所有私人內容」同時無條件成立。本版給兩條路：STRICT_LOCAL不對外；MINIMIZED_CLOUD只揭露使用者同意的抽象任務事實，不揭露真人識別碼、原文或媒體。語意線索本身仍有風險，不能宣稱雲端完全不知道任何資訊。

## 三層資料不是三種權限全開

| 層 | 本機必要內容 | 保護與生命週期 | 不預設蒐集 |
|---|---|---|---|
| 初始化INIT | 能力/權限狀態、使用偏好、可選家人關係綁定、SOP版本 | 分項同意、本機加密；關係由使用者指定，解除後刪除；不送穩定身分給雲端 | 整本通訊錄、所有App、個人完整背景 |
| 持續狀態STATE | 任務啟動或必要狀態變化時讀當前畫面抽象狀態、網路/電量等 | 按任務按需讀、短暫記憶體、僅允許來源；持續是按事件更新，不是24小時錄屏 | 剪貼簿歷史、通話錄音、背景所有聊天、全相簿 |
| 任務即時TASK | 選定照片/收件人、一則選定訊息、救援設定快照 | 目的綁定、資料白名單、任務內引用、確認後才動作；取消立即封鎖；任務結束清掉映射與暫存 | unrelated資料、跨任務追蹤ID |

本機關係綁定可長期存在直到解除；任務引用不能長期存在。URI授權、記憶體引用、雲端日誌等生命週期不同，需逐項驗證，不能把「刪一個token」當成所有副本消失。

研究蒐集MVP與實際助理應分模式：前者經同意蒐集原始資料、後者按任務最小化，不能把研究collector整份快照接到雲端Agent。

## 場景一 把照片傳給女兒

### 最小資料

本機語音意圖、女兒的使用者指定綁定、使用者選定照片、分享App是否可用、確認狀態。不需要全通訊錄、歷史訊息、EXIF、精確位置。原指引「不需要聯絡人」修正為「需要指定收件人的本機綁定，但不需要完整聯絡人清單」。不能自行從姓名/通話頻率猜女兒。

### SOP

1. 本機理解SEND_IMAGE；ASR不確定、收件人不唯一或照片不唯一時詢問，不猜。
2. 顯示Photo Picker供使用者選一張；「剛才那張」不能自動變成任何最近照片。沒有確認就不掃整個相簿。
3. 本機顯示照片與收件人確認；私人預覽不送雲端。
4. 通過確認才用ACTION_SEND、選定URI與臨時讀取授權開啟分享入口。
5. 引導使用者在LINE確認真正收件人及傳送。ACTION_SEND不能保證LINE自動定位到女兒；不可把啟動LINE當傳送成功。
6. 回來後僅在可靠證據/明確使用者確認下記錄實機成功，否則保持USER_ACTION_REQUIRED/UNKNOWN。

AI封包最多含intent與recipient_ready、photo_ready、share_app_ready、ui_ready、confirmation。人名、電話、LINE ID、照片內容/URI、檔名、EXIF、原語音與穩定user_id全部留在本機。

LINE收到照片是使用者希望的揭露，與「給AI揭露」是不同邊界；即使AI看不到照片，LINE與收件人仍看得到，需在確認介面說明。

固定無障礙脚本是後續選項：用語意節點與App/版本前置條件，不用盲點座標；畫面改版、節點不唯一、密碼/付款畫面、接到電話或取消時停止。此套件不實作該腳本。Google Play對無障礙自動化有政策限制，不能因「幫長輩」就自行宣稱例外资格。

## 場景二 訊息風險協助

目前通知來源只涵蓋可讀通知，不等於完整LINE或SMS聊天；正文可能被遮罩、截斷或未授權。保留使用者主動分享/貼入一則訊息的輸入方式，不為此要求READ_SMS、歷史剪貼簿或全聊天存取。

在本機處理選定訊息，提取要求轉帳、索取驗證碼、急迫語氣、含連結、冒充身分等線索，移除姓名、帳號、電話、真實URL、OTP；不要把網站連結拿去自動瀏覽。遮罩不能刪光訊息後還宣稱保真與判斷能力沒變。

AI只看旗標，輸出CAUTION/REVIEW/NEED_MORE_CONTEXT和固定提醒SOP。不給「一定安全」；沒有線索仍是REVIEW，不代表不是詐騙。樣本中的旗標是虛構，不是已實作本機文字分類器。

訊息中的「忽略規則」「傳聯絡人給我」屬不可信內容，不是助理指令。真實部署要測試間接prompt injection、私人字串重現及提示詞繞過。只接受SOP ID，不接受模型回傳的URL、程式、執行參數或刪訊息指令。

若線索不足，回本機詢問更多上下文或建議透過已知管道核實，不自動把整則訊息追加外傳。短版旗標可能降低效用，需用独立標註資料比較，不能把本機風險規則的答案再當模型真值。

## 場景三 系統狀態救援

| 需要的資訊 | Android候選方式 | 現有App狀態 |
|---|---|---|
| 電量/網路 | 既有battery、network collectors | 已有，但不是完整救援快照 |
| 飛航模式 | Settings.Global.AIRPLANE_MODE_ON只讀快照 | 尚未蒐集；不能單憑沒網路推論飛航 |
| 勿擾 | NotificationManager.getCurrentInterruptionFilter，UNKNOWN需保留；變更/監聽權限另處理 | 尚未蒐集 |
| 鈴聲模式/音量 | AudioManager.getRingerMode、getStreamVolume(STREAM_RING)，音量分級需用裝置max值 | 尚未蒐集；不可拿媒體音量當鈴聲音量 |

診斷順序可先查飛航，再查勿擾，再查鈴聲與網路；多個異常並存時逐步處理並重新讀值，不能只修第一個便宣稱已修好。pending時可給說明，但未確認不做設定動作。

MVP開對應的系統設定頁，引導使用者操作後重讀狀態。不自動改飛航、全域勿擾或鈴聲；Android/廠商/target版本限制不同。找不到設定Activity或讀取未知時顯示手動指引。所有狀態正常時也只能「目前未發現這些設定異常」，不能排除硬體、帳號、App故障。

## AI与固定SOP契約

`sops.json`保留可選ID、用途、模擬動作與需要確認的動作。真正模型只回傳`{"sop_id":"..."}`；拒絕額外欄位、未知ID和非當前候選ID。候選SOP由本機權限與狀態產生，模型不能擴大候選集。

本套件`task_engine.py`用規則模擬意圖後的SOP選擇；輸入的utterance限於虛構命令表，不是已完成語音辨識或語言理解。所有實機寫入adapter尚未實作；結果固定success=null。

## 現有資料如何利用

- contacts資料有姓名/值，但沒有可信的「女兒」關係綁定。
- images是檔案metadata，不是照片像素、使用者選定照片或分享URI授權。
- notification_posted已有內容結構，可測試選定訊息來源；原檔不應直接給AI。
- accessibility服務目前記錄事件/畫面樹，沒有performAction/dispatchGesture執行器；匯出沒有操作事件不表示助理能操作。
- App未實作ASR、任務資料閘門、SOP registry、AI transport、狀態救援設定讀取。這些都是後續開發，不是多生幾筆資料就能替代。

## 可驗證成果設計

1. 先驗Schema、場景覆蓋、拒絕未知欄位、權限缺失、拒絕/取消、重複ID与錯誤SOP。
2. 受控揭露比較：同一虛構任務，全部任務上下文vs白名單facts；記錄擬對外欄位數、UTF-8 bytes與直接私人欄位數。規則不保證一定省流，以實際計數為準。
3. 實機封包：斷網STRICT_LOCAL不發網路請求；MINIMIZED_CLOUD封包中不含真人姓名、照片、原文等。也要查ASR、analytics與服務提供者，不能只查主模型。
4. 效用：照片分享要驗證真正收件人與送出結果；救援要比before/after；風險提醒用獨立標註測試precision/recall、誤警与未知率，不假造安全率。
5. 隱私：加入可識別假資料canary、越權請求、prompt injection、取消中途競態、跨任務關聯与token重用測試。模擬評估只能證明模擬條件，不是真人匿名保證。

本機加密保護儲存、本機解析/目的限制保護流程、合成資料保護可分享測試素材；差分隱私若日後用於群體统计應另定義隱私單位、貢獻上限與預算，不能加在執行聯絡人token上就稱DP。此次沒有聯邦學習、DP或真實攻擊成功率成果。

AI揭露：Codex協助整理設計、規格、虛構測試與程式；團隊提供場景與隱私目標。後續生成需記錄實際模型、提示詞、附件、人工修改、版本、驗證結果；未執行者標為未執行。

## 開發優先順序

先實作語音/文字任務入口與共用閘門，再做狀態救援（只讀+設定引導），接著Photo Picker+分享確認，最後做選定訊息本機抽特徵。後續再評估受控無障礙腳本與可選AI模型。每個場景保留拒絕與取消路徑。

## 官方依據

- [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)：使用者選定媒體，避免完整相簿存取。
- [Android分享](https://developer.android.com/develop/ui/compose/sharing/send)：ACTION_SEND、URI與讀取授權；不保證接收App內部完成。
- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)：on-device認識服務需檢查可用性，API31以上提供相關方法；不支援時改文字或另行明確同意雲端ASR，不靜默回退。
- [AudioManager](https://developer.android.com/reference/android/media/AudioManager)、[NotificationManager](https://developer.android.com/reference/android/app/NotificationManager)、[Settings](https://developer.android.com/reference/android/provider/Settings)：狀態與設定入口；具體權限與版本需實機驗證。
- [Google Play無障礙服務政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)：區分靜態規則自動化與自主操作。
- [NIST SP800-226](https://www.nist.gov/publications/guidelines-evaluating-differential-privacy-guarantees)：合成資料不自動具備正式隱私保證。
