# 手機使用與通知情境 AI 生成模板

版本 1.0，建立日期 2026-10-11。

本套件把手機蒐集資料的「結構」轉成可交給生成式 AI 的規格。用途是建立手機輔助系統的模擬測試資料，研究通知負荷、應用使用與裝置狀態；不是重建真人生活，也不是老人族群的統計模型。

## 已完成與尚未完成

- 已完成：欄位白名單、JSON Schema、三種情境、可複製提示詞、完全虛構範例、驗證與 CSV 匯出程式、測試、AI 使用紀錄樣板。
- 尚未完成：大批 AI 生成、正式 PETs 轉換、獨立隱私攻擊測試、資料保真度與下游任務評測、競賽資料網址。
- `examples/sample.ndjson` 是本次 AI 協作手寫的 10 筆虛構格式範例，不是 API 批次生成成果、不是真實衍生資料，也不是 PETs 已完成的證據。
- 格式檢查通過不代表匿名、差分隱私、零洩漏或符合競賽全部要求。

## 建議使用順序

1. 先讀 `source_mapping.md`，了解哪些結構來自實測、哪些內容是設計假設。
2. 把 `AI_PROMPT.md` 的「可複製提示詞」交給 AI；可附 `schema.json` 和 `scenarios.json`，不要附原始手機檔。
3. 保存 AI 回答為 UTF-8 `generated.ndjson`，不要包 Markdown 程式碼區塊。檢查被截斷的回答，分批生成時不可重用使用者 ID。
4. 執行驗證；不合格資料需修改再跑，不可默默刪除失敗紀錄湊數。
5. 填寫 `generation_record.example.json`，另存為本次生成紀錄。保存實際提示詞、模型名稱、時間、人工修改與驗證結果。
6. 依 `evaluation_plan.md` 實作下一階段 PETs 和評測；不要把目前 CSV 直接改稱隱私驗證成果。

## 執行方式

Python 3.10 以上，只用標準函式庫，不需安裝套件或呼叫任何AI服務。驗證器實作本版本Schema使用的規則子集，不是通用JSON Schema引擎；若自行新增其他Schema關鍵字，必須同步更新驗證器。

在本套件目錄執行：

Windows可用執行入口，自動優先使用本機Codex的內建Python，否則使用PATH中的Python：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-validation.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-validation.ps1 -InputPath .\generated.ndjson -DefaultBatch
```

第一行只驗證10筆範例，第二行要求完整預設批次。結果存於`outputs`，以時間命名；`ExecutionPolicy Bypass`只作用於此次程序，不修改系統設定。也可用`-PythonPath`指定Python執行檔。

或直接使用Python：

```powershell
python validate.py examples/sample.ndjson --report validation_report.json --csv sample.csv
python -m unittest discover -s tests -v
```

生成提示詞預設產出 6 位虛構使用者、每人 1 天，共 3 種情境。完整批次驗證：

```powershell
python validate.py generated.ndjson --default-batch --report generated_report.json --csv generated.csv
```

`--default-batch`會要求與情境設定檔完全相同的六位使用者、情境及天數。自訂批次可改用`--require-coverage --expected-users N --expected-days D`檢查三種情境覆蓋、人數與連續模擬天數，但不指定各情境的人數。

只有全部驗證通過才會匯出 CSV；不合格時退出碼為 1，執行/讀檔錯誤為 2。拒絕覆寫既有報告或 CSV，重跑請換檔名。報告不輸出輸入紀錄的內容。

## 資料模型

每行一個事件，三種事件分別是 `app_usage`（虛構使用區間）、`notification`（單次通知事件）、`device_state`（單次裝置狀態）。共同欄位：

| 欄位 | 意義 |
|---|---|
| template_version | 固定 `1.0` |
| synthetic | 固定 `true`，不得宣稱真人資料 |
| user_id | `SYN_U001` 等，完全重新建立；與真人沒有對照表 |
| event_id | `SYN_E000001` 等，整份檔案唯一 |
| scenario_id | `routine`、`notification_burst`、`low_activity` |
| day_index | 模擬第幾天，0 到 364，無真實日期 |
| offset_sec | 模擬當天秒數，0 到 86399 |
| event_type | 三種事件之一 |
| payload | 事件對應的白名單欄位 |

完整型別與可用值以 `schema.json` 為準。電量、時段、應用類別、訊息長度分級都採設計值，不是從單一手機估出的族群分布。`notification` 的通知次數是事件數，不等同獨立訊息、人數或閱讀次數。

## 保護邊界

沒有自由文字欄位；不接受姓名、電話、聊天文字、行程、定位、郵件、網址、套件名稱、裝置 ID、原始通知 key。時間改成模擬相對時間，應用改成有限類別。這降低模板帶入真人內容的機會，但規格值本身仍可能成為旁路，因此仍需檢查生成來源與流程。不要把真實數字編碼進合成 ID 或時間。

只有 `AI_PROMPT.md`、`schema.json`、`scenarios.json` 與虛構範例可作為生成輸入。原始 NDJSON 仍是私人檔；本套件沒有複製、上传或自動讀取它。

若競賽原始資料集採「AI 自創模擬資料」，登記的是驗證後自創 CSV，而不是私人手机原始檔。來源需說明「參考手機蒐集欄位結構、依團隊情境規格虛構」，不能聲稱是實測老人資料。大會認定與檢視權限仍以正式規則為準。

## 參考

- [JSON Schema 2020-12](https://json-schema.org/draft/2020-12)：資料結構驗證規格。
- [NIST SP 800-226](https://www.nist.gov/publications/guidelines-evaluating-differential-privacy-guarantees)：合成資料不自動等於差分隱私或可靠匿名。
- [NIST PETs Testbed](https://www.nist.gov/itl/applied-cybersecurity/privacy-engineering/pets-testbed)：隱私、保真度與實用性評估方向。
