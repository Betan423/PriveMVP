# 語音手機輔助任務模板第二版

本版依使用者的三場景指引設計，取代「只有通知量與使用時長」的主題限制。保留上一版不覆寫。本套件是系統設計、AI生成規格與可執行的本機模擬器，不是已完成的Android Agent。

## 三個場景

1. 把選定照片傳給女兒：本機關係綁定、照片選取、確認、分享入口；不把通訊錄與照片傳給AI。
2. 收到訊息協助辨識風險：只檢查使用者選定的一則訊息；本機提取線索，AI最多收到線索旗標，結論是提醒/待查證，不保證判定詐騙。
3. 「手機是不是壞掉了」：本機讀飛航、勿擾、鈴聲與網路狀態；固定SOP引導到設定，不擅自改設定。

先讀 `SYSTEM_DESIGN.md`。AI生成資料使用 `AI_GENERATION_PROMPT.md`、`task_schema.json`、`privacy_policy.json`；不要提供真實原始手機檔或整個專案目錄。

## 這次完成

- 初始化、持續狀態、任務即時三層資料設計。
- 欄位與三個任務的關聯規格，不再只有泛用手機事件。
- 固定SOP清單、資料最小化規則、權限/確認/取消閘門。
- 本機模擬：產生候選SOP、擬對外AI封包、任務稽核、CSV與受控欄位揭露比較。
- 完全虛構的範例與負向測試。

## 尚未完成

手機ASR、真正AI模型、照片選取/LINE傳送、真實訊息分析、系統狀態讀取、Android SOP執行與端到端實機驗證。模擬器不會操作手機、連網、讀原始私人檔或判斷真實詐騙。

原始手機資料只有欄位參考；女兒身分、語音意圖、確認、飛航/勿擾/音量等是新增設計資料，不能寫成原始匯出已存在的欄位。`required_data`是工作流程需要的本機資料範圍，不代表OS runtime permission名稱。

## 直接執行

Python 3.10以上，只用標準函式庫。在本資料夾執行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-demo.ps1
```

或：

```powershell
python task_engine.py examples/tasks.ndjson --output outputs_demo --require-coverage
python -m unittest discover -s tests -v
```

輸出目錄必須尚不存在，拒絕覆寫；驗證失敗不匯出。Windows入口優先選本機Codex內建Python，未找到再使用PATH中的Python。以AI生成的新檔替換輸入：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-demo.ps1 -InputPath .\generated_tasks.ndjson
```

## 如何讀輸出

- `task_audit.csv`：required_data、accessed_data、disclosed_data、SOP、確認與任務結果。
- `planner_packets.ndjson`：外層task_id只供模擬資料對照；只有內層packet才是設計的對外AI封包。STRICT_LOCAL或未同意對外時packet為null。
- `comparison.json`：相同虛構任務的全任務上下文基準與最小化上下文比較；不是實際手機流量或指認攻擊結果。
- `audit.ndjson`：完整模擬稽核，`success`固定null。開啟分享頁不等於已傳送，開啟設定不等於已修復。

`actual_network_requests=0`、`planner_kind=RULE_BASED_MOCK`是刻意標示本次沒有連AI。`disclosed_data`表示若選擇MINIMIZED_CLOUD並同意時「擬揭露的facts欄位路徑」，不是已實際傳出。

比較欄位中，`totals`包含純本機、拒絕雲端與最小化的合併效果；單獨評估白名單精簡應看`matched_cloud_totals`，只比較同一組有擬對外封包的任務。不得把前者全算成單一PETs演算法的提升。

`sensitive_data_count`僅計算政策列出的直接私人欄位，不能解讀成所有隱私風險為零。風險線索、裝置狀態、網路請求時間仍可能提供間接資訊；真實部署還需要網路封包、洩漏、下游效用與攻擊測試。

## 競賽界線

這是一個AI自創模擬原始資料模板與本機最小化原型。合成資料、資料最小化、本機處理可形成專案路線，但不是差分隱私、聯邦學習或零知識證明；沒有實作的技術不得列為已採用。指引對「用不同PETs會加分」是想法，不是已確認的評分條款。

詳見 `SYSTEM_DESIGN.md` 的驗證與AI揭露段落。後續應把模擬與實機結果分開，再撰寫附件6。
