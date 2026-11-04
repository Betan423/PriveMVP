# 公開詐騙／垃圾簡訊資料集實測

實測日期：2026-11-04  
評估對象：情境二 `MessageGuard.extractFacts()`＋`MessageGuard.assess()`  
判定方式：只有 `HIGH` 算高風險命中；`VERIFY` 與 `INSUFFICIENT` 算未命中

## 1. 資料來源

| 資料集 | 內容 | 本次用途 | 散布處理 |
|---|---|---|---|
| [UCI SMS Spam Collection](https://archive.ics.uci.edu/dataset/228/sms+spam+collection) | 5,574筆英文 SMS；4,827 ham、747 spam | 完整二元混淆矩陣與跨語言壓力測試 | 原始檔只放本機外部資料目錄，不提交 Git |
| [FBS SMS Dataset](https://github.com/Cypher-Z/FBS_SMS_Dataset) | 約14K筆中國假基地台真實 spam，14類，聯絡資料已匿名化 | 中文分類別 HIGH 覆蓋率；`FR_*` 作為最接近詐欺的子集 | 依上游聲明標示來源／論文；不重新散布資料 |

FBS 上游對應論文為 *Lies in the Air: Characterizing Fake-base-station Spam Ecosystem in China*（CCS 2020）。上游 repository 沒有一般 SPDX 授權檔，因此本專案只提供取得腳本、commit revision 與統計結果，不把原始文字納入 GitHub。

本次下載資訊：

- UCI ZIP SHA-256：`1587EA43E58E82B14FF1F5425C88E17F8496BFCDB67A583DBFF9EEFAF9963CE3`
- FBS revision：`49173b12ab0a42eb9f6ce42e401e6acaef1fbdfd`
- UCI有效資料：5,574筆。
- FBS有效非空資料：14,074筆（上游文字檔共14,077行，其中3行為空白）。
- 總實際判定：19,648筆。

## 2. 隱私與安全處理

- 所有訊息只在本機進入正式 `MessageGuard`，沒有送到 Gemini、Proxy 或其他外部服務。
- 外部原始資料位於 `D:\PriveMVP\privacy-sms-datasets`，不在 repository 內。
- Git 只保存下載／轉換腳本與評估器。
- 評估報告不保存簡訊原文，只保存數量、比例、reason code 與 SHA-256 前綴反例 ID。
- FBS 的 Windows 不合法冒號檔名會被安全轉成底線；內容不做翻譯或重寫。

## 3. UCI 實測結果

UCI 的 `spam` 包含廣告、抽獎與一般垃圾訊息，不等同於「會造成財務或個資危害的詐騙」；而且資料為英文。此結果用來測量跨語言／跨任務資料偏移，不能宣稱為繁中詐騙準確率。

| 實際標籤 | 預測 HIGH | 預測非 HIGH | 總數 |
|---|---:|---:|---:|
| Spam | 0 | 747 | 747 |
| Ham | 7 | 4,820 | 4,827 |

| 指標 | 結果 |
|---|---:|
| Accuracy | 86.47% |
| 全部預測非 HIGH 的基準 accuracy | 86.60% |
| Balanced accuracy | 49.93% |
| Spam precision | 0.00% |
| Spam recall | 0.00% |
| Ham specificity | 99.85% |
| F1 | 0.00% |

結論：86.47%的表面 accuracy 來自類別不平衡，甚至比「全部判為非 HIGH」更差。現有規則沒有英文詐騙詞彙，因此不能把這個數字用來宣稱分類有效。

## 4. FBS 中文實測結果

| 資料範圍 | HIGH | 總數 | 原始 HIGH rate | URL placeholder |
|---|---:|---:|---:|---:|
| 全部 FBS spam | 324 | 14,074 | 2.30% | 6,240 |
| `FR_*` 詐欺子集 | 1 | 3,069 | 0.03% | 2,330 |
| `FR_Phishing(Bank)` | 0 | 1,900 | 0.00% | 1,780 |
| `FR_Phishing(Other)` | 0 | 297 | 0.00% | 295 |

原始 HIGH 的主要 reason code：

- `ASKS_TRANSFER`：323。
- `URGENCY`：2。
- `OFFERS_REWARD`：1。

### 為什麼銀行釣魚幾乎是0？

1. FBS 是簡體中文並以空格斷詞，目前規則主要是台灣繁體用詞，例如「轉帳」「驗證碼」。
2. 上游為保護隱私，把真實網址替換成字面 token `URL`。目前 `MessageGuard` 刻意不把單純的 placeholder 當成真網址，因此不會啟動可疑網域、短網址或 `.xyz` 分級。
3. 許多 FBS spam 是廣告、賭博或推廣，不一定要求轉帳、OTP或遠端控制；這與本系統 `HIGH` 的安全語意不同。

### URL placeholder 敏感度分析

評估器另外執行一組**不列入原始準確率**的敏感度測試：只把 `URL` token 換成合成高風險網址 `benchmark-phishing.xyz`，其他文字不變。

- 全部 FBS 中6,240筆含 URL placeholder；6,240筆全部會觸發 HIGH。
- `FR_*` 中2,330筆含 URL placeholder；2,330筆全部會觸發 HIGH。
- `FR_*` 的75.92%包含 URL placeholder，表示真實裝置若能看到實際可疑網址，現有網域分級可覆蓋這一部分。
- 這不是75.92%實測 recall，因為合成網址被刻意設為高風險 `.xyz`；它只是確認「網址特徵若存在，正式規則是否能接住」的敏感度上界。

## 5. 這次測試得到的工程結論

### 已確認有效

- 系統對非高風險訊息採保守策略，UCI ham specificity為99.85%。
- 繁中規則中的轉帳訊號能在部分簡體／斷詞資料中命中。
- 可疑網域分級在合成還原網址後會啟動。
- 外部資料可以離線、批次、可重現地走正式手機規則。

### 已確認缺口

- 沒有英文詐騙訊號，英文 spam recall為0%。
- 簡繁體與兩岸用詞差異造成嚴重漏判。
- 目前只有「高風險詐騙」語意，沒有獨立的「一般垃圾／廣告」分類。
- 資料集匿名化後移除實際 URL，必須分開呈現原始下界與合成敏感度。
- UCI spam與FBS spam都不等同於台灣長輩實際收到的詐騙簡訊，仍需要合法取得、去識別的繁中資料集。

## 6. 建議後續防禦方向

1. 保留現有 `HIGH` 安全門檻，不因追求 spam accuracy 就把所有廣告都顯示成高風險詐騙。
2. 將輸出拆成兩軸：`spam_or_ad` 與 `fraud_risk`，避免任務標籤混淆。
3. 增加本機簡繁正規化或明確的簡體對應詞，但每個新增詞都要跑正常訊息反例，避免誤報。
4. 加入英文最小安全詞彙，例如 transfer、OTP、password、urgent、bank impersonation；仍要求組合訊號才升 HIGH。
5. 針對 FBS 中不含 URL 的739筆 `FR_*` 建立匿名 hash ID failure corpus，逐類分析缺少的 signal。
6. 尋找或自行建立台灣繁中、spam／fraud／normal三標籤資料集，並記錄來源、同意、去識別與保留期限。

## 7. 重現方式

下載資料：

```powershell
.\scripts\fetch_sms_benchmarks.ps1
```

如果開發機因錯誤日期導致官方 UCI 憑證被誤判過期，可明確選用：

```powershell
.\scripts\fetch_sms_benchmarks.ps1 -SkipCertificateCheck
```

此選項只適合已核對官方網址且下載內容為非執行資料時使用；下載後仍應核對 SHA-256。

執行評估：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.ExternalSmsDatasetEvaluationTest" --console=plain
```

完整機器可讀報告產生在：

```text
app/build/reports/external-sms-dataset/latest.md
```

如果資料放在其他位置，可設定：

```powershell
$env:PRIVAGENT_SMS_DATASET_ROOT = "D:\your-path\privacy-sms-datasets"
```
