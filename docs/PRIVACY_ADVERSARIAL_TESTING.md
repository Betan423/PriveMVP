# PrivAgent 生成式隱私對抗測試（初版）

基準日期：2026-11-03  
適用情境：情境一「LINE 圖片傳給女兒」、情境二「詐騙訊息檢查」

## 1. 目的與定位

這套測試把競賽的紅隊思維轉成可重複執行的工程流程：攻擊生成器產生合成的惡意介面／訊息，PrivAgent 執行現有防禦，獨立的隱私不變條件判定器再判斷是否發生未授權操作或資料揭露。

它與典型 GAN 相似之處是「生成攻擊 → 判定結果 → 保留反例 → 加強下一輪」，但第一版沒有訓練神經網路。Android UI 是離散狀態，使用固定種子的 mutation/property-based testing 更容易重現、稽核與在 CI 中執行。日後可再加入 coverage-guided、遺傳演算法或 LLM 攻擊生成器，判定器仍維持規則式，避免由另一個 AI 自行宣告安全。

```text
合成攻擊生成器
  ├─ UI／名稱／語音 mutation（情境一）
  └─ PII／文字混淆／Prompt Injection（情境二）
                 ↓
          PrivAgent 真實規則
                 ↓
        規則式隱私判定器
          ├─ 通過：統計覆蓋
          └─ 失敗：保存最小反例，修正後永久加入回歸測試
```

所有初版案例均為合成資料，不會啟動 LINE、不會讀取真實聯絡人、不會呼叫 AI Proxy，也不會把使用者內容寫入測試報告。

## 2. 初版隱私不變條件

### 2.1 情境一

1. 收件人只允許排版差異後的精確等價名稱；插入文字、零寬字元或同形混淆不得匹配。
2. 「分享」「分享(1)」「更多」「傳送」只接受 allowlist 完整標籤；前後增加誘導文字不得接受。
3. 同一階段出現兩個符合按鈕時，不得視為唯一可操作控制項。
4. 含否定、附加條件或混淆字元的語音不得判為確認傳送。
5. 水平頁籤或多欄集合不得被當成向下搜尋的聯絡人清單。
6. LINE 的已選人數必須明確等於一；0、2～99 均不得授權傳送。
7. 圖片前置分享 capability 只能依照 `Share → Share(1) → More → recipient screen` 的合法邊前進。
8. 即使呼叫端傳入正確的目前階段，也不能指定任意下一階段跳過防禦。

### 2.2 情境二

1. 在詐騙關鍵字間插入空白、符號、emoji 或 Unicode format controls 後，仍須辨識高風險訊號。
2. 姓名、電話、email、身分證、OTP 與 URL 不得出現在雲端封包。
3. 全形、分隔符與常見格式變形不得繞過本機遮罩。
4. 高風險訊息不得附帶 `redacted_excerpt`，即使使用者已允許低風險摘錄。
5. Prompt Injection 不得增加封包欄位、工具能力或可執行 SOP。
6. 封包欄位必須與可執行隱私預算一致，不能因攻擊文字而改變 schema。

## 3. 實作位置

| 元件 | 位置 | 功能 |
|---|---|---|
| Campaign recorder／報告產生器 | `app/src/test/java/com/example/privagentcollector/PrivacyAdversarialCampaign.kt` | 固定 seed、記錄分類、反例與 Markdown 報告 |
| 生成式攻擊測試 | `app/src/test/java/com/example/privagentcollector/PrivacyAdversarialTest.kt` | 產生情境一／二合成攻擊並呼叫真實 production rules |
| Android 階段邊窮舉 | `app/src/androidTest/java/com/example/privagentcollector/PrivacyAdversarialInstrumentedTest.kt` | 使用 Android 真實 Handler／SystemClock 驗證25種 phase pair |
| 防禦規則 | `LineAutomationRules.kt`、`LineAutomation.kt`、`LineShareAccessibilityService.kt` | 精確標籤、單一收件人、合法狀態邊、送出前重驗證 |
| 執行後報告 | `app/build/reports/privacy-adversarial/latest.md` | build artifact，不含真實資料、不提交版本庫 |

## 4. 初次執行基準

固定 seed：`4232026`

| 攻擊分類 | 生成檢查 | 通過 | 已知繞過 |
|---|---:|---:|---:|
| 情境一：重複控制項 | 128 | 128 | 0 |
| 情境一：模糊／否定語音 | 256 | 256 | 0 |
| 情境一：水平頁籤誤滑 | 512 | 512 | 0 |
| 情境一：收件人混淆 | 512 | 512 | 0 |
| 情境一：允許的排版等價 | 5 | 5 | 0 |
| 情境一：單一收件人標記 | 100 | 100 | 0 |
| 情境一：偽造控制項 | 384 | 384 | 0 |
| 情境二：混淆詐騙訊號 | 512 | 512 | 0 |
| 情境二：隱私預算／PII 洩漏 | 256 | 256 | 0 |
| 情境二：Prompt Injection 限制 | 256 | 256 | 0 |
| **總計** | **2,921** | **2,921** | **0** |

另在 Pixel 6 Pro AVD／API 34 執行 Android instrumentation：11／11 通過。其中新增測試窮舉 `LineChatForwardSession` 的 5×5＝25 種 phase pair，只允許3條設計內合法邊。

完整回歸結果：

- JVM unit tests：72／72（包含另行下載後執行的公開簡訊資料集評估；沒有外部資料時該項會跳過）。
- Android instrumentation：11／11。
- Android Lint：0 errors、36 warnings。
- 初版生成式隱私檢查：2,921／2,921，0 個基準後反例。

「0 個基準後反例」只代表目前 seed、生成器與不變條件沒有發現繞過，不能解讀為形式化證明或真實世界零風險。

## 5. 初步紅隊分析找到並修正的弱點

### PA-001：前置分享狀態機可由內部呼叫端指定任意下一階段

- 原因：`LineChatForwardSession.advance(id, from, to)` 原本只檢查 session id 與目前 phase，沒有在 session 物件內限制合法 `from → to`。
- 風險：未來呼叫端修改錯誤、延遲事件或內部整合缺陷可能直接跳到 recipient screen，讓狀態機的防禦只存在於呼叫端慣例。
- 修正：session 內建三條合法邊 allowlist；其他22種 phase pair 一律拒絕。
- 回歸：Android 裝置測試窮舉全部25種組合。

### PA-002：「已選」畫面標記接受2～99人

- 原因：原本的 regex 把 `已選 (1)` 到 `已選 (99)` 都當成可信畫面，但產品隱私宣告是單一收件人。
- 風險：使用者額外點到其他對象、LINE 畫面狀態改變或 UI 被混淆時，可能在不只一位收件人的狀態繼續確認。
- 修正：解析可見計數但只信任 `count == 1`；語音詢問前與真正按送出前各驗證一次。出現其他計數時立即 fail closed。
- 回歸：中文、英文、日文以及0～99人共100個生成案例。

這兩項說明了紅隊方法的價值：不是只檢查正常展示流程能否成功，而是主動呼叫不應發生的階段邊、測試規格與 regex 的差異。

## 6. 執行方式

只跑合成生成式 campaign：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.PrivacyAdversarialTest" --console=plain
```

報告輸出：

```text
app/build/reports/privacy-adversarial/latest.md
```

跑完整 JVM、Lint 與 APK 編譯：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

啟動可拋棄模擬器後跑裝置測試：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

若測試失敗，JUnit 訊息與 `latest.md` 會列出 category、case id 與不包含原始個資的最小證據。修正後應把反例保留在 corpus 或固定測試中，不得只換 seed 讓失敗消失。

## 7. 目前限制與下一階段

初版尚未涵蓋：

- 真實 LINE 不同版本的完整 AccessibilityNodeInfo tree。
- 視窗重疊、透明 overlay、實際座標 race condition 與廠牌差異。
- 真實 ASR 誤聽分布。
- Proxy 的 MITM、斷線、重送與 rate-limit 動態攻擊。
- 依 coverage 自動保留高價值 mutation。
- 由 LLM 產生新型語意詐騙，再經固定 oracle 判定。

建議下一步依序完成：

1. 建立自有的 Fake LINE fixture App，控制重複按鈕、同名對象、延遲載入及無限清單。
2. 將成功反例存成不含個資的 JSON corpus，加入 CI 永久回歸。
3. 加入 coverage-guided／遺傳式 mutation，以「新分支、接近違規、最小案例」作為 reward。
4. 對情境二加入 Proxy replay、過期 nonce、schema smuggling 與錯誤回應的負向整合測試。
5. 最後才加入 LLM attack generator；隱私 oracle 仍由固定規格判定。

## 8. 對外報告建議用語

> 我們建立了生成式隱私紅隊測試初版，使用固定種子產生2,921個合成攻擊檢查，並在 Android 裝置上窮舉前置分享狀態機的25種階段組合。初步紅隊分析實際找出「狀態機可指定非法下一步」及「已選人數接受2～99人」兩項規格落差，修正後基準測試為0個已知繞過。這不是宣稱系統絕對安全，而是把紅隊攻擊、藍隊修正與永久回歸變成可重複量測的流程。
