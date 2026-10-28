# 三場景任務資料生成提示詞

本提示詞、Schema與policy可提供給AI。不要附真人NDJSON、指引之外的私人檔、整個專案或實機截圖。本機輸入例子是固定虛構命令；正式ASR測試需另設，這份資料不證明語音辨識效能。

## 可直接複製

請產出手機輔助系統的自創模擬任務資料，用於測試「AI選固定SOP、本機控制資料與執行」的架構。必須依附上的task_schema.json，並參考privacy_policy.json。全部synthetic=true、template_version="2.0"；不得聲稱是真實老人或完整手機紀錄。

只輸出UTF-8 NDJSON，每行一個任務。不得Markdown、註解、陣列、省略號或不完整行。本批生成12個任務，三場景各4個。task_id從SYN_T0001起唯一遞增，user_id用SYN_U001等虛構值，不編碼真人資訊。

每行恰好11個欄位：template_version、synthetic、task_id、user_id、scenario_id、utterance、privacy_mode、grants、confirmation、cancelled、local_context。utterance只使用Schema的對應枚舉，不自行增加真人姓名、網址或自由訊息。不得新增success、期望答案、模型猜測標籤；結果由驗證後的本機規則與後續實機測試產生。

場景SHARE_PHOTO：
- 正常：本機收件人ready、照片selected、分享App available、UI ready、confirmation approved。
- 收件人ambiguous：不得自行猜女兒。
- selected_photo未授權：grants不含selected_photo，任何分享不得進行。
- 使用者denied或cancelled：不得動作。
- local_context只含recipient_binding、photo_state、share_app、ui_state；不含姓名、電話、照片URI/bytes、檔名或EXIF。
- 正常必需grants：voice_command、recipient_binding、selected_photo、share_app_state。

場景CHECK_MESSAGE：
- 有轉帳/急迫/連結等線索的提醒樣本。
- 沒有線索的樣本；不能把它標成一定安全。
- context_sufficient=false或message_state=missing的資料不足樣本。
- 選MINIMIZED_CLOUD但grants不含cloud_features，應回本機處理的樣本。
- confirmation固定not_required。local_context只含message_state和features；features的六個布林為asks_transfer、requests_otp、urgency、has_link、impersonation、context_sufficient。不提供實際訊息、OTP、電話、網址、發訊者或攻擊指令。
- 必需grants：voice_command、selected_message。

場景RESCUE_PHONE：
- 飛航on，確認approved。
- 飛航off但勿擾on，確認pending。
- 飛航與勿擾off，鈴聲音量zero，確認approved。
- 狀態unknown、network_connected=null，需手動指引。
- local_context只含airplane_mode、dnd_mode、ringer_mode、ring_volume_band、network_connected。網路離線不代表飛航一定on；音量是鈴聲音量，不是媒體音量。
- 必需grants：voice_command、system_status、network_state。

privacy_mode取STRICT_LOCAL或MINIMIZED_CLOUD，兩者都要出現。MINIMIZED_CLOUD只有grants另外包含cloud_features時才可提議對外封包；本地處理能力不因不同意雲端而消失。grants是目的綁定授權標籤，不是Android權限字串。不添加無關來源授權湊數。

cancelled為布林。實際執行器不應相信AI生成的確認；這裡只是模擬測試輸入，真正確認必須來自本機UI。本資料不能證明照片真的傳送、設定已修改或訊息真實分類正確。

所有枚舉與型別以Schema為準；只生成輸入，接著由團隊執行驗證與模擬。請開始輸出。

## 擴充規則

首批12件只覆蓋基本分支。後續可擴充照片不唯一、分享App未安裝、UI版本不符、權限撤回、使用者中途取消、多個設定異常等，但每種情況要有獨立測試，而不是增加重複正常資料。每批記錄模型、提示詞與人工修訂；本套件範例由Codex協作手寫，不是已經跑完大批生成。
