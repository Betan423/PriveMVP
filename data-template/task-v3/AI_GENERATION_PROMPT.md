# 完整環境與任務原始基準生成提示詞

把本文件、SOURCE_FIELDS.md与examples/source.json交給AI。範例全為虛構，不能附真人raw_events。

## 可複製提示詞

請以附上的source.json作為精確結構模板，依SOURCE_FIELDS.md生成AI自創手機環境與任務基準。只輸出一個完整JSON物件，不附Markdown、註解或省略號。根物件固定bundle_version="3.0"、synthetic=true、provenance="DESIGNED_SYNTHETIC_NO_REAL_DEVICE_VALUES"，其他只有phone_environments、task_requests。

生成6個虛構手機環境、12個任務（三場景各4個）。所有範例數字、身分、資源和文字皆是虛構，不重建任何真人。命令不得作為唯一資料：每個手機環境要含完整initialization_data與continuous_state_data的欄位，包含装置/偏好/contacts/apps、電量/網路/飛航/勿擾/鈴聲/畫面、photos/messages/notifications、calendar/location/clipboard/call。不可新增欄位。

continuous_state_data也包含permissions的contacts/media_images/notifications/accessibility/microphone/location/calendar，值限granted/denied/unknown。這是虛構場景可用性，不是真實權限；unknown不能當granted。clipboard.observability為unavailable時text_ref必須null；call.state為idle時caller_ref必須null。sdk_int/android_release只用34/14、35/15、36/16三種配對。

ID唯一且以SYN_前綴；environment_id以SYN_ENV加4位起；task_id用SYN_T加4位起；其他資源ID也必須是SYN_開頭的英數底線。所有*_ref用SYN_占位或null，照片名SYN_*.jpg，pixels_available固定false。不得真姓名、真電話、真人座標、可用帳戶或真實訊息。location.latitude/longitude固定0。訊息只從SOURCE_FIELDS.md的4個虛構文本選，不引入真實網址或OTP。

根據raw環境欄位創造正常與負向案例：照片選定/不唯一收件人/照片未選/分享App不在；訊息有線索/無線索/缺上下文/拒絕雲端；飛航開/勿擾開/鈴聲0/未知狀態。不是只複製12次正常資料。

task用environment_id連到原始快照，user_id和profile一致。requested_offset_sec不得早於captured_offset_sec；照片、訊息、通知、行事曆等時間遵守欄位規則。Wi-Fi可以在飛航下存在，不能一律從offline推論飛航；connected=false必須transport=NONE或UNKNOWN、internet_validated=false或null。鈴聲current不得大於max，unknown以null保留。

task的parameters依場景：SHARE_PHOTO為recipient_relation與photo_id；CHECK_MESSAGE只含message_id；RESCUE_PHONE是空物件。引用不能跨環境。不要生成local_context、features、planner_packet、success或任何處理後結果：它們由團隊的處理程式產生，不能由AI自行聲稱完成或安全。

utterance、grants、confirmation、privacy_mode、cancelled使用附例与SOURCE_FIELDS.md的固定值。兩種privacy_mode都應出現；至少有缺任務授權、pending/denied/取消、未同意cloud_features等不同例子。每批保存實際模型、提示詞與人工修改資訊。

生成的是可供檢視的自創原始資料集；第二部分的衍生資料由程式產出，而非AI同時編造原始與處理結果。先輸出完整JSON，團隊驗證通過後才轉UTF-8 CSV。
