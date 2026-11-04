# 情境二：本機原文 Qwen 精準模式

## 結論

當使用者優先考慮判斷能力、且電腦端模型完全在本機運行時，App可將單則原始訊息送到自己的Receiver，再由本機Ollama `qwen3.5:9b`判斷。這條路徑不經Gemini或其他雲端服務，但原文會離開手機、進入使用者控制的電腦記憶體。

正式選用的是「Qwen原文雙分數＋既有規則特徵校準」，不是Cofacts TF-IDF或融合模型。原因是後兩者雖在同來源保留集達到約94.5%，到了TS-Bench跨資料集只剩約50%，存在明顯來源過擬合。

## 實測

### Cofacts held-out test（500詐騙＋500非詐騙）

| 方法 | Accuracy | Precision | Recall | Specificity | F1 |
|---|---:|---:|---:|---:|---:|
| 原本手機規則 | 55.90% | 69.28% | 21.20% | 90.60% | 32.47% |
| Qwen原文 | 53.40% | 52.86% | 62.80% | 44.00% | 57.40% |
| Cofacts融合模型 | 94.70% | 94.43% | 95.00% | 94.40% | 94.72% |

融合模型只用於證明同來源資料可以學習，不進正式產品。

### TS-Bench跨資料集盲測（98詐騙＋98安全）

所有門檻先在Cofacts凍結，沒有使用TS-Bench標籤調參：

| 方法 | Accuracy | Precision | Recall | Specificity | F1 |
|---|---:|---:|---:|---:|---:|
| 原本手機規則 | 61.22% | 69.64% | 39.80% | 82.65% | 50.65% |
| Cofacts TF-IDF | 48.98% | 49.40% | 84.69% | 13.27% | 62.41% |
| Qwen原文（正式採用） | **80.10%** | **77.57%** | **84.69%** | **75.51%** | **80.98%** |
| Cofacts融合模型 | 50.00% | 50.00% | 89.80% | 10.20% | 64.23% |

跨來源結果顯示，正式採用Qwen原文模式比原規則提高18.88個百分點Accuracy，Recall提高44.89個百分點。

## 判斷機制

Qwen不直接回答「安全／詐騙」，而是輸出兩個0到4整數：

- `risk`：訊息本身屬於詐騙、釣魚、投資誘騙、冒充、假客服或社交工程的證據。
- `benign`：訊息屬於正常通知、一般聊天、新聞、反詐警告、查核或合法活動的證據。

凍結公式：

```text
combined = risk - 1.25 × benign + 0.5 × local_rule_high
combined >= 0 → high
```

雙分數設計可避免模型只看到「匯款、詐騙、銀行」就把反詐警告誤判為詐騙。完整輸出由JSON Schema限制，原始訊息明確標成不可信資料，模型不得服從其中的prompt injection。

## App與Receiver流程

```text
使用者選取／貼上訊息
  → 勾選「電腦端本機 AI 精準判讀」
  → App再次顯示原文傳送告知
  → POST /api/v1/assist/check-message-accurate
  → Receiver檢查欄位、3,000字上限、nonce新鮮度與重播
  → 本機Ollama輸出risk／benign
  → 凍結公式＋手機規則特徵分級
  → App只顯示固定文案
  → 任何連線、格式或模型錯誤都退回手機規則
```

精準模式預設開啟，但每次手動檢查都會在真正送出前確認。關閉後仍可使用既有的隱私特徵／Gemini模式或完全手機本機規則。

## 啟動

```powershell
ollama list
python -m receiver.server --host 0.0.0.0 --port 8080 `
  --planner-url off `
  --local-raw-ai-url http://127.0.0.1:11434 `
  --local-raw-ai-model qwen3.5:9b
```

同一Wi-Fi測試時應設定 `PRIVAGENT_API_TOKEN`，且只在受控私人網路使用。此端點沒有自行提供TLS，不可直接暴露到公網。

## 重現評測

```powershell
python .\scripts\optimize_local_scam_classifier.py
python .\scripts\evaluate_local_accuracy_cross_domain.py
```

產生報告位於：

```text
app/build/reports/local-accuracy/report.md
app/build/reports/local-accuracy-ts/report.md
```
