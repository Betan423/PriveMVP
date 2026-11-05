# 情境二：遮罩後資料送本機 Qwen 測試

## 正式定位

本機 Ollama `qwen3.5:9b` 只用來取代開發期間的雲端 API 費用。手機送給它的資料，必須和未來送給雲端模型的 privacy packet 完全同級：本機先萃取特徵、遮蔽直接識別值，再交給模型。未遮蔽原文 Qwen 端點已從 App 與 Receiver 移除。

```text
手機原始訊息
  → 手機端特徵萃取與個資遮蔽
  → Receiver 再次驗證 packet／隱私預算／重播
  → 本機 Qwen（模擬未來雲端 API）
  → 固定 risk level 與 reason codes
```

## 正式採用：v4 單次雙分數＋程式證據閘門

Qwen不再直接回傳`high`。模型只能依嚴格JSON Schema輸出：

- `risk_score`：0–4，訊息本身正在誘騙的證據強度。
- `benign_score`：0–4，合法通知、一般聊天、反詐警告、新聞或查核內容的證據強度。
- `intent_code`：轉帳誘騙、憑證竊取、投資話術、冒充、假獎勵、遠端控制或安全類別等固定白名單。

v4先在手機端補出`message_stance`、`requested_action`、`credential_target`、`direct_request`、`action_negated`、`anti_scam_context`與`fact_check_context`，並以`[OTP_CODE]`、`[BANK_ACCOUNT]`、`[OFFICIAL_URL]`等型別化占位符保留必要語意。正式HIGH仍由程式閘門決定，而不是讓模型自由決定：手機規則HIGH永遠保留；其他訊息須通過開發集凍結的雙分數門檻，並具有本機佐證或足夠強的風險語意。模型無法降低本機規則結果，也不能產生操作指令或自由UI文字。

曾實測「第一階段後再交給第二個提示覆核」；覆核會把過多直接詐騙改成正常，Cofacts dev Recall由38.00%降為26.00%、F1由48.10%降為37.96%，因此正式路徑已移除全面二次覆核。現在每則訊息只做一次結構化模型判斷，降低延遲與token，也避免覆核過度修正。

門檻不是重新訓練Qwen。它先在Cofacts固定種子的平衡dev 400筆選取（要求Specificity至少80%，再最大化Recall／F1），凍結後才評估未參與選取的平衡test 1,000筆。

實測日期：2026-11-05

| Cofacts階段 | TP | FN | FP | TN | Accuracy | Precision | Recall | Specificity | F1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dev純規則 | 27 | 173 | 6 | 194 | 55.25% | 81.82% | 13.50% | 97.00% | 23.18% |
| dev隱私評分（HIGH） | 64 | 136 | 28 | 172 | 59.00% | 69.57% | 32.00% | 86.00% | 43.84% |
| held-out test純規則 | 64 | 436 | 15 | 485 | 54.90% | 81.01% | 12.80% | 97.00% | 22.11% |
| held-out test隱私評分（HIGH） | 162 | 338 | 90 | 410 | **57.20%** | 64.29% | **32.40%** | **82.00%** | **43.09%** |
| held-out test分流（HIGH＋CAUTION） | 346 | 154 | 284 | 216 | 56.20% | 54.92% | **69.20%** | 43.20% | **61.24%** |

相較v4純規則，嚴格HIGH的Recall增加19.60個百分點、F1增加20.98個百分點；代價是Specificity下降15.00個百分點。把CAUTION也當成需要人工核實可將Recall提高到69.20%，但誤報明顯增加，所以CAUTION只適合顯示提醒，不可觸發背景警報或自動封鎖。

必須誠實說明：v4 held-out HIGH成績仍低於前一版v3的Accuracy 58.90%、Recall 38.60%、F1 48.43%，因此不能宣稱同來源整體準確率已提升。v4的價值是把反詐警告、查核、要求動作與憑證類型明確化，並修復可疑網域與直接付款要求的安全規則；下一階段若要突破Cofacts瓶頸，需要獨立資料清理或微調，而不是繼續放寬HIGH門檻。

## TS-Bench 診斷對照

以下資料已在先前開發過程中看過，所以只列跨資料集診斷，不能再稱為盲測。v4採單次雙分數與程式證據閘門。

- 詐騙／金融誘騙：98筆
- 語意相近安全困難負例：98筆
- 模型：本機 `qwen3.5:9b`
- 每批20筆；結果可由匿名分數快取重現
- 只有`high`算成功攔截
- AI只能提高警戒，不能降低手機規則結果

| 組別 | 模型收到的資料 | Accuracy | Precision | Recall | Specificity | F1 |
|---|---|---:|---:|---:|---:|---:|
| v4純規則 | 不呼叫AI | 62.24% | 77.27% | 34.69% | **89.80%** | 47.89% |
| v4嚴格HIGH | 緊湊特徵＋最多300字型別化遮蔽摘錄 | **69.39%** | **80.65%** | 51.02% | 87.76% | 62.50% |
| v4人工分流 | HIGH＋CAUTION都視為需要核實 | **69.39%** | 63.97% | **88.78%** | 50.00% | **74.36%** |

混淆矩陣：

| 組別 | TP | FN | FP | TN |
|---|---:|---:|---:|---:|
| v4純規則 | 34 | 64 | 10 | 88 |
| v4嚴格HIGH | 50 | 48 | 12 | 86 |
| v4人工分流 | 87 | 11 | 49 | 49 |

## 結果解讀

1. v4嚴格HIGH相較純規則，在TS診斷集多抓16筆詐騙並新增2筆FP；跨來源仍有增益，但不能視為盲測。
2. HIGH＋CAUTION分流可抓87／98筆風險訊息，但會把49／98筆安全困難負例送去人工核實，所以不能把它包裝成自動警報準確率。
3. 先前未遮罩原文Qwen在同一TS-Bench的Accuracy 80.10%、F1 80.98%，只能當效能上限；資料邊界不同，不能當成目前正式隱私版本的成績。
4. 正式門檻只用Cofacts dev選取；Cofacts held-out test才是本輪未參與調參的主要成績。TS-Bench僅作診斷。
5. 本輪沒有重新訓練或微調Qwen，只做資料邊界、提示、固定輸出與門檻校準。

## 模型與雲端替換原則

- 本機Qwen不是隱私保證本身；隱私保證來自模型之前的手機端資料最小化、遮蔽與Receiver拒收閘門。
- 未來改用Gemini或其他雲端API時，不得擴充packet欄位，也不得改送原文。
- Receiver稽核只保存揭露欄位名稱、決策、reason codes、耗時及packet SHA-256，不保存原文、prompt或遮蔽摘錄。
- 測試快取只保存雙分數、意圖代碼與token統計，不保存prompt或訊息內容。
- Qwen只能回傳雙分數與意圖白名單；其他模型也必須受固定輸出契約限制。任何模型都不能產生App操作指令或自由UI文字。

## 重現

```powershell
python .\scripts\evaluate_privacy_score_classifier.py
```

產出：

```text
app/build/reports/privacy-score/report.md
app/build/reports/privacy-score/results.json
```

GitHub可直接查看本文件中的完整混淆矩陣與Accuracy／Precision／Recall／Specificity／F1；[`docs/evidence/SCENARIO2_V4_VALIDATION_2026-11-05.json`](evidence/SCENARIO2_V4_VALIDATION_2026-11-05.json)另保存本次可提交的機器可讀數據快照、測試數量與APK雜湊。執行上列指令後，`results.json`會產生更完整的凍結設定、各階段統計、資料集SHA-256與模型呼叫／快取用量。錯誤分析檔只含匿名ID、標籤、分數及枚舉特徵，不含訊息原文。

原始TS-Bench資料與模型快取位於repository外，不提交Git。
