# AI 金孫｜ChatGPT 電腦版工作交接（2026-10-12）

## 專案目的

專題名稱：**AI 金孫－不會洩露長輩秘密的生活助理**。
這是既有的高齡友善 Android / Gemini Agent 生活助理衍生出的「資料隱私保護－創新應用組」研究。

原應用預計讓長輩透過語音指令與既有行動劇本完成打電話、分享照片、看訊息、設定提醒等手機操作；新研究則探討這類 Agent 為達成任務會碰到聯絡人、通知、照片中繼資料、位置、手機畫面等私人資訊的問題。

**核心研究：**Task-Centric Minimal Disclosure（任務導向最小揭露）：先判斷任務需要何種 Context，再在手機端做資料最小化、敏感欄位遮蔽、Tokenization / Pseudonymization、本機 Mapping；只將完成任務所需的最小資料交給雲端 AI。

## 本次希望電腦版 ChatGPT 接手的工作

1. 在本機解壓 `AI金孫_合成手機原始測試資料集_72環境576任務.zip`，讀取 README、資料生成腳本及實際 JSON / CSV 結構。
2. 先驗證資料：環境與任務關聯、ID 唯一性、JSON / CSV 讀寫、權限、缺漏、取消、矛盾狀態，以及每一類情境筆數；產出可重現驗證報告。
3. 協助把團隊**自行設計的** Privacy Processing System 與上述 Raw Dataset 整合：先支援 3 個 v3 情境，再視需要擴充其餘 6 個。若隱私處理程式尚未提供，**先要求我提供目前原始碼，不要假設它已存在或擅自將資料視為已完成隱私處理**。
4. 由隱私處理器實際轉換後，才建立新的 `derived_tasks.csv`、`pairs.json` 與 `comparison.json`；記錄轉換版本、參數、已處理筆數、排除原因。
5. 實作最小可行的 Privacy / Fidelity / Utility 評測：資料揭露項目、必要 Context 保留、與獨立參考 SOP 一致程度。不得把資料縮減比例當成正式的重識別防護率，也不得虛構 Agent / Android 實機成功率。
6. 依實際處理結果更新 `附件6_AI金孫_衍生資料集說明_合成資料擴充版.docx`，符合官方範本 Word A4、最多 5 頁、標楷體 14 字、單行間距、段前後 3pt、2cm 四邊邊界及 1cm 裝訂邊。公開資料集網址須由我上傳後補入。

## 現有資料和確定完成的範圍

`AI金孫_合成手機原始測試資料集_72環境576任務.zip` 包含：
- `source_expanded_v3.json`：72 份虛構手機環境、432 筆三類任務；手機環境和任務原始資料。
- `phone_environments_expanded.csv`：72 筆虛構環境，沿用 v3 CSV 欄位。
- `task_requests_expanded.csv`：432 筆 v3 結構任務，各 144 筆：`SHARE_PHOTO`、`CHECK_MESSAGE`、`RESCUE_PHONE`。
- `source_extension_v4_candidate.json`、`task_requests_extension_v4_candidate.csv`：144 筆新六類情境，`CALL_CONTACT`、`SEND_MESSAGE`、`READ_NOTIFICATION`、`SET_REMINDER`、`NAVIGATE_TO_PLACE`、`OPEN_APP`，各 24 筆。這些情境**尚無 v3 隱私處理規則**。
- `quality_report.json`、`scenario_catalog.csv`、`generate_raw_dataset.py`：資料統計、情境表與確定性產生程式。
- `seed_source_v3.json`、`seed_phone_environments.csv`、`seed_task_requests.csv`：最初的 1 環境、3 任務種子。
- `baseline_derived_tasks_v3_3rows.csv`：**只有這 3 筆是舊版已產生的衍生結果**，不可混同新資料。

總計 **72 環境、576 筆合成原始任務、9 類情境**。其中 432 筆只是結構上沿用 v3，還沒實際經 v3 隱私處理器跑過。新增 144 筆是 v4 候選，需新增規則。

## 資料來源、AI 使用方式及重要限制

- 全部新增資料都是**虛構的 Synthetic Raw Data**；沒有引用實際長輩的聯絡人、照片像素或個人資訊。
- 生成方式：ChatGPT 協助設計情境與句子模板，再用固定種子 `20261012` 的 Python 結構化組合；**不是每筆都由 LLM 獨立生成**。
- `SYN_*` 是虛構值、位置 0/0 是占位、URL 使用 `*.example.invalid`。
- 這次**沒有**對 576 筆新任務執行 PETs、沒有生成新版 `derived_tasks.csv`、沒有測實際手機操作或 AI 成功率。
- 舊版 v3 記錄「1 份環境／3 任務、39 個工程單元測試、354→26 葉值及 9,974→626 bytes」；這些結果**不得套用至擴充版**，且資料量縮減不是隱私保護效果的正式量化。
- v3 原處理器有嚴格的訊息白名單及情境規則，擴充資料可能需要修改 Validator 才能通過。
- 實機 Raw Collector 曾取得手機資料，但含私人資訊；**未附入本交接包**。不要要求使用者把真實手機 Raw Data 上傳雲端，除非已去識別並取得相關同意。

## 交付標準

希望你優先提供：
1. 可以直接在我的本機執行的完整程式或修改後的專案。
2. 執行說明與測試指令，以及實際執行結果（成功／失敗數）。
3. 必要時提供完整 ZIP、更新後的衍生 CSV、JSON 驗證報告。
4. 更新附件六 Word 時，只填「真實已產生／實測」內容；未實作的項目明確標為規劃中。

## 建議第一步

先讀取資料包的 README 與 JSON / CSV，列出現有 Schema；然後向我確認 **v3 Privacy Engine / Validator 原始碼目前放在哪個資料夾**。如已能存取我的工作目錄，先檢查檔案再修改，不要覆蓋原始 v3 基準；新成果另存資料夾並保留版本。
