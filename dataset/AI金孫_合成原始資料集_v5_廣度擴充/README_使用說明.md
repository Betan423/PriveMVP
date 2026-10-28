# AI 金孫：v5 合成原始資料集廣度擴充

此版本只擴充 **Synthetic Raw Dataset**。沒有執行 Privacy Engine、沒有新版衍生任務、沒有 Gemini API 呼叫或 Android 代操作。

## 實際完成

- 192 份新虛構手機環境，5,184 筆新原始任務（每份環境 27 項）。
- 9 類任務，各 576 筆：照片分享、訊息風險檢查、手機狀態救援、撥號、傳訊息、讀通知、提醒、導航、開 App。
- 72 個子情境、288 個指令句型，實例化後有 480 個不同指令文字。
- 32 個訊息家族、96 種訊息句型，包含日常通知、醫療資訊、付款、驗證碼、冒充、連結、含私密占位值及不完整內容。
- 六個年齡區間各 32 環境；API 29..36 各 24 環境。這是設計配額，不是真實長輩分布。
- 5,184 任務的結構、關聯及模板語意檢查通過；29 個工程單元測試通過；5 個主檔獨立重跑的 SHA-256 相同。
- 舊 v4 的 72 環境／576 任務及 v3 的 3 筆衍生結果均保留原處，**沒有併入這次 5,184 筆**。本包 `legacy_v3_baseline/` 若附入，僅為歷史參考。

## AI 如何增加廣度

本輪 ChatGPT／Codex 以生成式 AI 設計中文情境與多種說法，再編寫確定性的 Python 生成與驗證程式。`template_catalog.json` 是可檢視的 AI 協作內容，不只是把既有 576 筆複製多次。`build_catalog.py` 保存模板來源。

程式將指令及參數從同一語意定義產生，例如「開地圖」只能對應 MAPS；「明天下午兩點回診」對應 checkup 與可解析的時間；照片／通知最新選取依時間排序。導航附本機虛構目的地目錄，避免無法解釋的孤立 destination_ref。

**不是每筆獨立請 LLM 生成**，也未發出任何 Gemini／其他 LLM API 請求。大量紀錄來自有限模板的結構化組合，文字與情境彼此相關。擴大 `--environments` 只增加實例數；真正增加語意廣度必須擴充模板家族。不能以筆數增加宣稱族群代表性、真實 AI 正確率或有效統計樣本數。

## 兩段式資料

1. `dataset/phone_environments.csv`：192 列、57 欄。環境初始化與狀態，包括使用者偏好、裝置、聯絡人、App、收藏目的地、權限、網路、音量、UI、照片中繼資料、訊息、通知、行事曆、定位占位與使用情況。
2. `dataset/task_requests.csv`：5,184 列、46 欄。指令、任務情境、環境／使用者引用、參數、授權與確認、取消後續語句、時間錨點及來源 fixture 標記。

使用 `environment_id` 連接兩表；`dataset/source_raw_v5.json` 是兩段合併的權威原始 JSON。CSV 中字典展成點號欄名，陣列保留 JSON 文字；`null` 表示有此欄位但未知／缺失，空儲存格表示該情境無此欄位。CSV 採 UTF-8 BOM，便於 Excel 開啟；JSON 採 UTF-8，明確 LF 換行。

`source_schema.json` 是 v5 的型別／情境參數契約（JSON Schema 2020-12）。隨附 validator 只實作本契約使用的 type、properties、required、additionalProperties、const、anyOf、oneOf、items 關鍵字；不是通用 JSON Schema 工具。新的欄位或情境需同步設計契約與驗證。v5 **不直接相容**舊 v3 Privacy Engine。

## 檔案

| 檔案 | 用途 |
| --- | --- |
| `template_catalog.json` | AI 協作句型、語意與訊息家族 |
| `generate_raw_dataset.py` | 離線、固定種子原始資料生成 |
| `validate_raw_dataset.py` | 型別、ID、引用、時間、模板一致性、CSV／JSON 核對 |
| `test_raw_dataset.py` | 29 個工程測試（包含刻意損毀輸入） |
| `dataset/scenario_catalog.csv` | 72 子情境與筆數 |
| `dataset/generation_report.json` | 生成統計與限制 |
| `dataset/validation_report.json` | 實際全量驗證結果 |
| `reproducibility_report.json` | 第二次生成與 SHA-256 核對結果 |
| `engineering_tests.json` | 實際單元測試執行結果 |
| `review/現況盤點.md` | v4 問題與版本界線 |
| `competition-documents/` | 本輪附件六 Word 修訂 |

沒有 `derived_tasks.csv`、`pairs.json`、`comparison.json` 新成果。不要將 fixture_labels、generation_report 或 CSV 表格誤認為已經做過隱私處理。

## 執行與重現

需要 Python 3.10 以上；資料生成、驗證、測試全部只用標準函式庫，不需要 API Key、網路或套件安裝。在此資料夾執行：

```powershell
python -B generate_raw_dataset.py --output dataset_reproduced --environments 192 --seed 20261012
python -B validate_raw_dataset.py dataset_reproduced --report validation_reproduced.json
python -B test_raw_dataset.py
```

若電腦沒有 `python` 命令，可用 `py -3` 代替，或指定自己的 Python 執行檔。預設資料、模板、契約都已附入，無須先執行 build_catalog.py／build_schema.py。生成器拒絕已存在的輸出目錄，報告拒絕既有檔案；再次測試請另取新名稱，避免覆蓋。

增加數量例：`python -B generate_raw_dataset.py --output dataset_384 --environments 384 --seed 20261012` 可產生 10,368 項任務，但不新增句型家族。驗證支持 32..1000 環境；單元測試預設驗證本包固定 `dataset/`。

## 可用性與故意缺失

來源涵蓋完整目的授權、缺少一項用途授權、僅語音、未授權、不同意雲端、取消、待確認、拒絕、缺資源／收件人歧義、未知系統狀態、平台權限不足與過期 snapshot fixture。缺失資源以 null 表示，不使用無效引用。

例：162 筆選取資源缺失、231 筆收件人綁定缺失、27 筆歧義、304 筆取消、1,728 筆快照年齡超過 300 秒。這些計數可能重疊，是預先設計的來源測試條件，不是 Privacy Engine 拒絕數或功能失敗率。300 秒也只是 fixture 分層門檻，尚未成為真實系統政策。

## 隱私與實驗界線

- 所有姓名、電話、帳號、驗證碼、病歷、媒體與地址均為 SYN_* 虛構占位；這是資料創作，不是將真人識別資料 token 化。
- 位置 0/0 明確標記為占位，不是可導航座標。照片沒有像素；背景 hint 不能證明實際辨識／移除照片敏感內容。
- privacy_mode、grants、confirmation 只是模擬輸入，不表示真實使用者曾同意或原始資料已經受到 PETs 保護。
- fixture_labels 是原始語意／資源條件標記，**不是**SOP、Privacy Engine 的決策結果或獨立人工真值。
- 模板一致性檢查使用同一套語意定義，不能充當獨立 Fidelity／Utility 成效證據。
- 未對真人資料訓練模型、未估算重識別風險、未執行差分隱私或成員推斷攻擊。沒有隱私保護效力、實機／Gemini 成功率數據。
- 後續由團隊自主 Privacy Processing System 轉換；需另記錄引擎版本、規則、授權、排除原因與真實輸出。

後續評測請同時按環境與模板家族切分：同家族語句不應同時成為訓練及測試答案。若仍使用本包全資料開發規則，應再保留未使用的新家族／獨立人工案例作測試。這一輪不產生 train/test 分割，也不宣稱獨立測試準確率。

## 公開與競賽

資料集網址待使用者自行上傳後補入。原始 v5 與歷史 v3 衍生基準應以不同名稱／子目錄發布，並保留 README、生成程式、模板、契約、報告與 AI 使用揭露。只完成原始資料擴充不代表競賽要求的新版 PETs 衍生資料與隱私／保真度／實用性驗證已完成。

官方 Android API／平台版本對照：https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels
