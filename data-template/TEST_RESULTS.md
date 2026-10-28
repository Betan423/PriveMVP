# 本次驗證結果

2026-10-11，使用本機內建Python執行，沒有呼叫外部生成服務。

- `python -m unittest discover -s tests -v`：28項測試全部通過。
- `python validate.py examples/sample.ndjson --report ... --csv ...`：10筆虛構事件全部通過，成功匯出UTF-8 CSV。
- `powershell -NoProfile -ExecutionPolicy Bypass -File .\run-validation.ps1`：成功選用內建Python，產出報告與CSV。
- 完整六位虛構使用者、三種情境的批次約束已由單元測試覆蓋；沒有將測試用批次當成正式AI生成資料集交付。

測試包括：欄位白名單、不回顯私密值、識別碼格式與重複、JSON重複鍵與NaN、型別、時間順序、跨日/重疊、遮罩與網路邏輯、情境配額、通知密集窗口、預設使用者分配、CSV回讀、不合格不匯出、拒絕覆寫與輸入輸出路徑碰撞。

這些是軟體與規格測試。尚未執行真人隱私攻擊評測、差分隱私機制、族群保真度或下游任務成效測試。
