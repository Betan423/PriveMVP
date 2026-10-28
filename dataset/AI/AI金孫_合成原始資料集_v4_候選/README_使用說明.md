# AI 金孫｜合成原始資料集擴充版（2026-10-12）

## 本次已完成的部分

這是一組**完全虛構、合成的原始測試資料（Raw Synthetic Dataset）**，供日後團隊自行開發的 Privacy Processing Engine 使用。它不是「已完成隱私處理的衍生資料集」，也不含真實長輩、真實聯絡人、照片像素或真實手機的個人內容。

- **72 份虛構手機環境**（包含原始 `SYN_ENV0001` 種子）。
- **432 筆 v3 結構的原始任務**：照片分享、可疑訊息線索、手機問題排除，各 144 筆。
- **144 筆 v4 候選任務**：撥號、傳訊息、查看通知、設定提醒、導航、開 App，各 24 筆。
- **總計 576 筆原始任務、9 種情境。**

新增資料刻意包含正常、缺照片／訊息、權限拒絕、同類聯絡人歧義、取消、待確認、不同雲端權限與異常系統設定等變化。

## 檔案對照

| 檔案 | 內容 | 可否直接視為處理後資料 |
| --- | --- | --- |
| `source_expanded_v3.json` | 72環境、432筆三類原始任務 | 否 |
| `phone_environments_expanded.csv` | 72筆手機環境，沿用 v3 CSV 欄位 | 否 |
| `task_requests_expanded.csv` | 432筆 v3 類別任務，沿用 v3 CSV 欄位 | 否 |
| `source_extension_v4_candidate.json` | 72環境、144筆新六類情境 | 否 |
| `task_requests_extension_v4_candidate.csv` | 新六類任務（v4候選 schema） | 否 |
| `scenario_catalog.csv` | 情境及筆數列表 | 不適用 |
| `quality_report.json` | 生成方式、結構驗證、限制 | 不適用 |
| `seed_source_v3.json` | 使用者原始提供的 1環境3任務種子 | 否 |
| `seed_phone_environments.csv`、`seed_task_requests.csv` | 對照原版 CSV | 否 |
| `baseline_derived_tasks_v3_3rows.csv` | 使用者原始提供、既有的 **3筆** 衍生資料 | **僅此檔是舊版衍生資料** |
| `generate_raw_dataset.py` | 固定隨機種子、產生 Raw Dataset 的腳本 | 不適用 |

## 使用者的隱私處理程式如何接上

1. 先讀取 `source_expanded_v3.json` 的 `phone_environments` 與 `task_requests`，確認 `environment_id`、`user_id` 與資源 ID 關聯。
2. **先更新 v3 的資料驗證器**：原先 v3 只允許少量固定訊息文字，本資料集擴充了不同內容，不能因欄位名稱相同就假設全部符合 v3 白名單。
3. 由團隊自己的 Privacy Engine 處理每一筆原始任務：決定 `required_data`、執行任務導向最小揭露、檢查權限與使用者同意、產生擬雲端封包。
4. **完成轉換後**再由程式建立新的 `derived_tasks.csv`、`pairs.json` 與 `comparison.json`。目前這些新版本**沒有**被產生，也沒有憑空填入 `success` 或 `planner_packet`。
5. `source_extension_v4_candidate.json` 的六種新情境**必須另行設計隱私規則**才能處理；不要將其直接送進僅支援三種情境的 v3 處理器。

## 如何重新產生

安裝 Python 3.10 或更新版（只用標準函式庫），進入解壓縮的資料夾執行：

```bash
python generate_raw_dataset.py
```

腳本使用固定亂數種子 `20261012`，以本資料夾的 `seed_source_v3.json` 為輸入，重新輸出擴充 JSON、UTF-8 with BOM CSV、情境目錄與品質報告。請保留種子檔與產生腳本，以利競賽查驗與重現。

## 生成式 AI 協作揭露

本次透過 ChatGPT 來發想日常情境、虛構訊息與自然語句模板，再以 Python 根據固定種子進行結構化組合、擴增及規則檢查。**不是每筆任務都獨立呼叫 Gemini 或其他語言模型產生**，也不是以真實私人資料訓練生成模型。這套方法的目的在增加測試資料的廣度、組合多樣性與可重現性。

## 限制（附件六也已揭露）

- 新增的 576 筆**全部都是未經 PETs 處理的原始測試任務**。不得宣稱它們具有 DP、k 匿名、去識別或真人重識別抵抗力。
- 舊版 `baseline_derived_tasks_v3_3rows.csv` 只有 3 筆，與新資料分開統計；舊版工程測試結果不能套用到新資料。
- `0,0` 坐標、`SYN_*` 虛構資源識別碼與 `*.example.invalid` 網址均為研究占位，不應理解成可用的真人地址、電話或公開服務。
- 目前資料不是高齡人口的真實統計樣本，合成資料之品質檢查不等於資料保真度或下游 Agent 成功率驗證。
- 原始輸出的 CSV／JSON 為明文虛構資料，並非加密檔案。正式使用及上傳前仍應逐筆檢查資料與第三方權限。

## 競賽繳交提醒

附件六須填入**可檢視的實際資料集網址**；本次沒有替你上傳至任何平台，因此 Word 文件仍保留待填欄位。提供給主辦單位的正式「隱私衍生資料集」，應在後續隱私流程完成後以 UTF-8 CSV 交付，**而不是直接把新增 Raw Dataset 當成最終衍生集**。
