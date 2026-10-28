# 手機環境原始資料與隱私衍生資料第三版

本版修正前版只呈現AI任務摘要的不足。競賽材料分成兩個部分：完整的**自創模擬原始基準**（手機環境＋任務），與由程式產出的**隱私衍生資料**（最小化AI上下文＋稽核）。完整是相對三場景所設計的來源，不代表所有Android資料都可讀取，也不是實測手機或已完成助理。

## 第一部分 原始基準

`examples/source.json`包含兩張邏輯表：

- `phone_environments`：初始化資料、持續狀態資料。包括虛構裝置/偏好/關係綁定/可見App、電量/網路/鈴聲/勿擾/飛航、畫面/通知、照片metadata、訊息、行事曆、位置、剪貼簿與通話狀態。
- `task_requests`：用environment_id連到任務當下快照，包含虛構命令、所選收件人關係/照片/訊息引用、用途授權、確認/取消、相對時間與隱私模式。

命令不是整份原始資料，AI封包也不是整份原始資料。這份資料原文與識別值全為虛構，占位電話不能撥打、`.invalid`連結不能當成真實網站；照片只有metadata，無影像像素、音訊或實機畫面。

## 第二部分 衍生資料

`bundle_engine.py`把任務連到環境，檢查引用與時間，從本機基準解析收件人歧義、照片可用性、分享App與畫面狀態，從選定虛構訊息抽簡單線索、從系統狀態產生救援上下文，再沿用第二版閘門與固定SOP模擬。

輸出：
- `phone_environments.csv`：原始手機環境表。物件拆成欄位，清單保存為JSON字串，仍為UTF-8 CSV。
- `task_requests.csv`：原始任務表，保留environment_id，可與環境表連接。
- `derived_tasks.csv`：只保留合成配對ID、場景、資料範圍、SOP、擬對外封包與結果，不含整份环境、真人身分或來源原文。
- `pairs.json`：每個任務的`part_1_original`與`part_2_derived`，便於看處理前後。
- `comparison.json`：完整虛構環境＋任務基準與擬最小化封包的配對欄位/byte比較；不是實際網路流量或隱私攻擊結果。

STRICT_LOCAL不產生擬對外封包；MINIMIZED_CLOUD也要有cloud_features同意，且通過任務閘門。沒有真人原始檔讀取、AI API或手機操作。CSV是資料管線的輸出，規格與生成主要以JSON表示。

平台可用性以`permissions`另行模擬，不混同目的同意`grants`。照片/訊息場景所需平台狀態未知或拒絕時，不產生擬對外封包。這些是場景級可用性，不是完整Android權限實作。

目前只提供1份手機快照與3個任务，是可擴充的結構範例，不是大量訓練或代表老人族群的資料集。生成提示詞可擴充6環境/12任務，但這批尚未生成與獨立標註。

## 執行

Python3.10以上、無第三方套件：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-demo.ps1
```

或：

```powershell
python bundle_engine.py examples/source.json --output outputs_demo
python -m unittest discover -s tests -v
```

输出目錄必須不存在。新增AI資料可使用`-InputPath .\generated_source.json`。只把`AI_GENERATION_PROMPT.md`、`SOURCE_FIELDS.md`和完全虛構範例給生成服務，不提供真實raw_events或整個專案。

## 必須說明的限制

- 裝置/電量/網路/contacts/media/notification結構參考既有collector；家人關係、語音、飛航/勿擾/鈴聲、剪貼簿、通話等新增欄位是設計情境，不是現在已抓到的資料。
- 研究基準可以包含合成無關資料，以測試是否被錯誤揭露；真實產品不能因此預設蒐集所有來源。資料集作者允許檢視合成素材，也不等於任務執行時允許外傳。
- local解析在本機模擬程式執行；不等於Android權限/實體資料存取已實作。驗證器會解析完整JSON來檢查結構，`accessed_data`是任務邏輯範圍，不是作業系統讀取證據。
- 原始資料是AI自創模擬基準，不是「真實raw_events去識別後」的資料。可用於競賽自創來源路線，但資格和評分仍以大會認定為準。
- 文本線索提取只是中文關鍵詞示範，不是真實詐騙模型；沒有線索仍為待核實。這次不建立真實成功率、DP或攻擊保護率。
- `success=null`，照片只有metadata，不能證明照片已傳送；系統快照不能證明已修復。語音命令也是文字範例而非音訊。
- 完整原始表不可送給雲端規劃器；`pairs.json`是供比較的文件，不能當成AI輸入封包。只有`part_2_derived.planner_packet`是擬對外資料。
- `sensitive_data_count`沿用v2欄位名稱，只計數政策列舉的直接私人欄位，不涵蓋間接識別或推論風險；0不表示完全沒有隱私風險。擬對外封包的任務與系統狀態本身仍可能是敏感資訊。

本版保留v1/v2與使用者指引不動；詳情與虛構值約束見SOURCE_FIELDS.md。正式作品仍需實機與獨立隱私/效用評測。

## 文件如何分成兩段

1. **原始資料集與使用情境**：交代自創來源、三場景、環境欄位、三階段資料、來源與取得限制，列示`phone_environments.csv`和`task_requests.csv`。
2. **隱私衍生流程與驗證**：交代必要欄位選擇、訊息線索抽取、識別內容不進入擬對外封包、兩種模式/同意/確認閘門、固定SOP，列示`derived_tasks.csv`、配對範例與測試。欄位/byte比較僅算最小化程度，另補獨立攻擊風險與任務實用性實測。

這個分段是作品組織建議，並不表示大會要求公開真實手機所有私人內容。AI協作揭露見AI_USAGE.md，已跑驗證見TEST_RESULTS.md。
