# 台灣繁中訊息資料集實測

實測日期：2026-11-04
評估對象：情境二 `MessageGuard.extractFacts()`＋`MessageGuard.assess()`
正向判定：只有 `HIGH` 算詐騙／高風險命中；`VERIFY` 與 `INSUFFICIENT` 都算未命中

## 1. 本輪資料來源

| 資料集 | 本輪用途 | 授權／存取 | 執行狀態 |
|---|---|---|---:|
| [TS-Bench](https://github.com/mtkresearch/TS-Bench) | 台灣繁中詐騙、金融誘騙與語意相近安全對照 | Apache-2.0；公開下載 | ✅ 已完成196筆相關子集 |
| [Cofacts LINE訊息資料](https://huggingface.co/datasets/Cofacts/line-msg-fact-check-tw) | 真實使用者回報LINE訊息及社群查核回覆 | CC BY-SA 4.0＋Cofacts資料使用者條款；已由資料使用者完成授權 | ✅ 已完成5,000筆詐騙＋5,000筆非詐騙 |

TS-Bench本次固定於revision `b53fd7fabec6e053677cdfa68cdff4d7d1fba1b4`，`TSB400.csv` SHA-256為
`C78AE64BBBA0AB93432F4C9794BA433565040E1F02231FAD8A7D67A12E4E2D45`。

原始資料位於repository外的`D:\PriveMVP\privacy-sms-datasets`，不會包入APK或提交Git。所有判定在本機執行，不呼叫Gemini、Proxy或其他AI服務；評估報告不保存訊息原文，只保存統計、reason code與單向SHA-256反例ID。

## 2. TS-Bench範圍選擇

上游CSV只有`id`、`message`、`label`、`split`，沒有category欄位。完整400筆還包含醫療不實資訊、性別歧視、族群仇恨與政治操弄；將這些全部視為「詐騙」會扭曲情境二的評估。

因此本輪只取：

- ID 1–98：詐騙、金融誘騙與相關社交工程危險內容。
- ID 201–298：與前述內容語意相近的安全困難負例。
- 合計98筆危險＋98筆安全，共196筆。
- ID 99–200及299–400不納入情境二詐騙指標。

內容另依發布順序分成三個分析帶；這是本專案為診斷用的內容分帶，不宣稱為上游正式category欄位：

- 1–32／201–232：購物、帳號、付款與物流釣魚。
- 33–65／233–265：投資招攬與金融話術。
- 66–98／266–298：冒充、商務付款與廣義社交工程。

## 3. TS-Bench實測結果

| 實際標籤 | 預測HIGH | 預測非HIGH | 總數 |
|---|---:|---:|---:|
| 詐騙／高風險 | 39 | 59 | 98 |
| 安全困難負例 | 17 | 81 | 98 |

| 指標 | 結果 |
|---|---:|
| Accuracy | 61.22% |
| Balanced accuracy | 61.22% |
| Precision | 69.64% |
| Recall | 39.80% |
| Specificity | 82.65% |
| F1 | 50.65% |

### 分帶結果

| 內容帶 | TP | FP | FN | TN | Recall | Specificity |
|---|---:|---:|---:|---:|---:|---:|
| 購物／帳號釣魚 | 22 | 9 | 10 | 23 | 68.75% | 71.88% |
| 投資招攬 | 2 | 4 | 31 | 29 | 6.06% | 87.88% |
| 冒充／社交工程 | 15 | 4 | 18 | 29 | 45.45% | 87.88% |

### 工程解讀

1. 現有規則對含索取個資、可疑網址、付款要求的購物釣魚已有部分能力，但仍漏掉10／32筆。
2. 最大缺口是投資招攬：只命中2／33筆。許多訊息以「老師、助理、飆股、法人布局、加碼」建立信任，沒有直接出現轉帳、OTP或惡意網址，因此不符合現有HIGH組合規則。
3. 安全困難負例仍有17／98筆被升為HIGH。代表只看「OTP、驗證、付款、立即」等詞彙，可能把合法訂單或官方安全提醒誤報。
4. 下一輪防禦不能只增加更多單一關鍵詞；應加入投資招攬的組合訊號，同時用對應安全負例鎖住誤報率。
5. 這份結果是本機確定性規則的基準，不代表雲端AI最終判斷，也不是台灣人口母體準確率。

機器產生的完整報告位於：

```text
app/build/reports/taiwan-message-dataset/ts-bench.md
```

## 4. Cofacts篩選規則

Cofacts的`RUMOR`表示訊息含錯誤資訊，不代表它一定是詐騙。因此預處理器使用較保守的標記：

- 只讀`NORMAL`、`TEXT`文章。
- 查核關聯必須是`NORMAL`。
- `positiveFeedbackCount`必須大於`negativeFeedbackCount`。
- 詐騙正例必須同時為`RUMOR`，且查核文字明確肯定它是詐騙、詐欺、釣魚、假投資、假冒機構、騙取個資或誘騙匯款。
- `NOT_RUMOR`作為非詐騙候選。
- 同一文章若同時出現支持的詐騙與非謠言回覆，整筆排除。
- 醫療或政治謠言若沒有明確詐騙斷言，不納入詐騙正例。
- 正規化文字完全相同的訊息只保留一筆。
- 最多各取5,000筆，依SHA-256排序做確定性抽樣。

預處理器會在外部資料目錄建立：

- `cofacts-benchmark.jsonl`：只供本機評估，仍含原文，不可提交Git。
- `manifest.json`：篩選條件、筆數及輸出SHA-256，不含原文。
- `ATTRIBUTION.txt`：Cofacts要求的顯名聲明。

預處理邏輯已用合成CSV測試，確認會排除醫療謠言、否定式「並非詐騙」、零回饋關聯與互相衝突的查核。

## 5. Cofacts實測結果

資料使用者已親自完成Hugging Face與Cofacts資料條款授權。本次只下載`articles.csv`、`article_replies.csv`及`replies.csv`三個必要表，沒有下載使用者表、分析紀錄或其他非必要資料。

篩選結果：

- 全部查核回覆：`RUMOR` 51,636筆、`NOT_RUMOR` 18,473筆、`OPINIONATED` 11,773筆、`NOT_ARTICLE` 67,801筆。
- 符合支持門檻的詐騙關聯：16,665筆。
- 符合支持門檻的非謠言關聯：13,881筆。
- 去除衝突、非文字、無標籤與重複後，詐騙候選12,198筆、非詐騙候選12,457筆。
- 依SHA-256確定性抽樣，各取5,000筆，合計10,000筆。
- 本機衍生JSONL SHA-256：`898723929B91CD675D40AC9A8BEC6DA56BB4020DC0FF2FD600AAF91DC5486BEF`。

| 實際標籤 | 預測HIGH | 預測非HIGH | 總數 |
|---|---:|---:|---:|
| 詐騙／高風險 | 1,092 | 3,908 | 5,000 |
| 非詐騙 | 406 | 4,594 | 5,000 |

| 指標 | 結果 |
|---|---:|
| Accuracy | 56.86% |
| Balanced accuracy | 56.86% |
| Precision | 72.90% |
| Recall | 21.84% |
| Specificity | 91.88% |
| F1 | 33.61% |

### 工程解讀

1. 現有規則偏向「避免誤報」：非詐騙訊息有91.88%不會被標成HIGH。
2. 真實LINE分布的詐騙召回率只有21.84%，代表3,908／5,000筆高信度詐騙案例沒有達到目前HIGH門檻。
3. Cofacts內容常是長篇轉傳、活動訊息、假優惠或間接誘導，不一定直接包含「轉帳、OTP、遠端控制」；這些正是目前規則倚賴的強訊號。
4. Cofacts的詐騙子集仍是由群眾查核回覆衍生，不是警政司法判決資料，結果必須標示取樣偏差與標籤雜訊。
5. 下一輪若加入投資與假活動語意，必須同步重跑5,000筆非詐騙對照，避免用提高誤報率換取表面召回率。

機器產生的完整報告位於：

```text
app/build/reports/taiwan-message-dataset/cofacts.md
```

## 6. 授權下載與重現

重現流程：

```powershell
# 1. 先由資料使用者在Hugging Face頁面登入並接受Cofacts條款。
# 2. 以遮罩輸入具有read權限的token，不要把token貼進對話或寫進指令歷史。
$hfReadToken = Read-Host "Hugging Face read token" -MaskInput
[Environment]::SetEnvironmentVariable("HF_TOKEN", $hfReadToken, "User")
Remove-Variable hfReadToken

# 3. 下載TS-Bench與Cofacts必要三表，不接受條款、不下載使用者表。
.\scripts\fetch_tw_message_benchmarks.ps1 -DownloadCofacts

# 4. 建立本機高信度詐騙／非詐騙子集。
python .\scripts\prepare_cofacts_benchmark.py

# 5. 執行兩套台灣繁中評估。
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.TaiwanMessageDatasetEvaluationTest" --console=plain

# 6. 清除使用者層級token。
[Environment]::SetEnvironmentVariable("HF_TOKEN", $null, "User")
```

## 7. TS-Bench單獨重現

只下載公開TS-Bench並產生來源manifest：

```powershell
.\scripts\fetch_tw_message_benchmarks.ps1
```

執行資料預處理器單元測試：

```powershell
python -m unittest scripts.tests.test_prepare_cofacts_benchmark -v
```

執行台灣資料集評估：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.TaiwanMessageDatasetEvaluationTest" --console=plain
```

如資料放在其他目錄，可設定：

```powershell
$env:PRIVAGENT_TW_DATASET_ROOT = "D:\your-path\privacy-sms-datasets"
```
