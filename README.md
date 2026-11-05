# PrivAgent 隱私助理 MVP v0.4

Android 9（API 28）以上的隱私優先生活助理。compileSdk / targetSdk 36。
產品首頁採正式亮色雙情境介面，只呈現「安心傳送照片」與「詐騙訊息檢查」。過去的原始研究蒐集與手機救援模組仍保留於程式庫供研究驗證，但不再顯示或啟動於產品 UI。

兩個核心情境為分享照片（SHARE_PHOTO）與可疑訊息檢查（CHECK_MESSAGE）。情境二採隱私路徑：手機先萃取特徵並遮蔽直接識別值，只把 privacy packet 送到電腦端，再交給可自選的 AI：Gemini API、Claude API，或本機 Qwen（`AI=local`，資料不出電腦，也用來免費模擬雲端 API）。不論哪一種，AI 都不會收到未遮蔽的原文。

## Repository 導覽

- `app/`：Android Collector 與即時傳輸服務。
- `receiver/`：Python 標準函式庫實作的電腦接收端。
- `gemini-proxy/`：AI 後端（`official_api.py`），可選 Gemini API、Claude API 或本機 Ollama；API Key 只放 `.env` 或環境變數。
- `docs/REALTIME_PIPELINE.md`：即時管線、契約與模組化分工基線。
- `docs/competition/`：決賽與後續開發整合文件。
- `dataset/`：競賽資料集、生成與驗證程式。
- `data-template/`：任務／資料格式範本與示例。
- `.github/workflows/ci.yml`：Receiver、Android tests、Lint 與 APK 自動建置。

GitHub 推送與遠端已有 commit 時的安全整合方式見 [docs/GITHUB_UPLOAD.md](docs/GITHUB_UPLOAD.md)。

## 快速開始（Makefile）

Linux／macOS 在專案根目錄執行 `make` 會列出所有指令。常用流程：

```bash
make setup                 # 建立 .env，填入 GEMINI_API_KEY 或 ANTHROPIC_API_KEY（見 gemini-proxy/README.md）
make test                  # Receiver、proxy、Android 全部測試
make proxy                 # 終端機 A：AI 後端（127.0.0.1:8000）；AI=claude 或 AI=local 可切換
make receiver              # 終端機 B：Receiver（127.0.0.1:8080）
make demo                  # 終端機 C：模擬手機送一則訊息；make demo MESSAGE="..."
make demo-dataset ENV=1    # 送資料集第 1 個環境的 32 則訊息
make message-eval          # 情境二的隱私、規則與紅隊評測（不呼叫雲端）
```

真手機：`make install` 安裝 APK，改用 `make receiver-lan TOKEN=自訂存取碼` 啟動 Receiver，
App 填它印出的位址與同一組存取碼。不接 AI 只用規則可用 `make receiver-rules`。
Android 建置需要 JDK 17 與 Android SDK（`JAVA_HOME`、`ANDROID_HOME`）。

## 對網際網路開放 AI 端點（手機不必和電腦同網段）

只開放 `/health` 和 `/api/v1/assist/*`，蒐集到的資料、儀表板都不會出去；必須帶存取碼，所有人合計每分鐘最多 30 次（`--rate-limit` 可調）。

```bash
make proxy                 # 終端機 1：AI 後端（只聽 127.0.0.1；AI=gemini|claude|local）
make public                # 終端機 2：公開模式 Receiver（127.0.0.1:8081），第一次會產生存取碼
make tunnel                # 終端機 3：Cloudflare Tunnel，印出 https://xxxx.trycloudflare.com
make public-url URL=https://xxxx.trycloudflare.com
make apk                   # 網址和存取碼包進 APK，手機不用再填
```

- 網址與存取碼存在 `local.properties`（已被 git 忽略），**不要提交**；也可用環境變數 `PRIVAGENT_ASSIST_URL` / `PRIVAGENT_ASSIST_TOKEN` 給。
- `trycloudflare.com` 的臨時網址每次重開 `make tunnel` 都會變，要重新 `make public-url` + `make apk`。要固定網址請改用 Cloudflare 帳號的具名 tunnel。
- 寫死在 APK 的存取碼可以被反編譯取出，只能擋路人，不能當成真正的身分驗證；外流時刪掉 `local.properties` 裡那行、`make public-token` 重產，再重新編 APK。
- 手機上「存取碼」欄位有填時優先用手機填的。

## 產品核心兩情境

首頁以兩張獨立功能卡呈現，另將 AI 連線、通知存取與浮動按鈕收納成輔助設定。

| 情境 | 手機做什麼 | 會送到雲端的內容（需同意） | 停下或詢問 |
| --- | --- | --- | --- |
| 分享照片 | 長輩停留在含有圖片的 LINE 聊天室，按浮動「助理」後會出現半透明的全螢幕選取遮罩。長輩點一下目標圖片，該次點擊由遮罩攔截、不會打開圖片；遮罩移除後，專用無障礙服務只在同一座標長按一次，再依序點擊唯一「分享」→「分享(1)」→「更多」，最後沿用女兒 HMAC 選人與確認 | 聊天內轉傳路徑不呼叫雲端，也不取得圖片 bytes、URI 或 EXIF；只有 LINE 本身處理圖片。App 只短暫使用使用者點擊座標與 LINE 可見語意節點 | 必須已設定唯一女兒與 LINE 名稱，且聊天頁有輸入框。點擊位置必須落在可確認的圖片節點內；任何階段按鈕、收件人或送出按鈕不唯一就停止；60 秒未完成也停止 |
| 可疑訊息 | 見下節 | 只送隱私特徵與使用者選用的遮蔽摘錄；目前以本機Qwen模擬雲端API | 見下節 |

「設定家人」把女兒、兒子等關係對應到通訊錄裡的人，只存在這支手機（系統聯絡人挑選器，不需通訊錄權限）。
在「設定家人」選擇關係後，可使用「設定並立即分享照片」或「立即分享照片」，不必返回首頁重走流程。LINE 未安裝時會引導安裝，也可由使用者明確選擇改用其他 App。

### 從 LINE 轉傳收到的圖片給女兒

1. 先在 PrivAgent 設定唯一一位女兒、她的 LINE 顯示名稱，並啟用浮動按鈕與 **PrivAgent LINE 免觸控分享**。
2. 長輩停留在含有目標圖片與訊息輸入框的 LINE 聊天頁，按一下浮動「助理」。App 會覆蓋一層半透明、可取消的全螢幕選取遮罩，並朗讀「請點一下要轉傳給女兒的圖片」。
3. 長輩點一下目標圖片。遮罩會吃掉這次觸控，所以 LINE 不會開啟圖片；App 只記住這一個螢幕座標，並確認該座標位於尺寸足夠的 ImageView／圖片語意節點內。未點到圖片時會重新顯示遮罩。
4. 遮罩移除後，AccessibilityService 在同一座標送出一次 750ms 長按手勢，等待 LINE 顯示黑色操作選單，再從 LINE 所有互動視窗中按下唯一且文字完全相同的「分享」（部分 LINE 版本會把黑色選單做成獨立 PopupWindow）。
5. App 確認進入「選擇訊息」畫面後，按唯一的「分享(1)」；快速分享面板出現後強制按唯一的「更多」。快速面板即使也顯示「選擇傳送對象」，仍不會提早啟動找人。每一階段都有5秒期限，不能跳階。
6. LINE 進入完整「選擇傳送對象」後，原本的一次性 HMAC、只向下搜尋、唯一女兒、「已選 (1)」階段驗證，以及語音／手動最後確認會接續執行。
7. 最後確認成功後，App 第一次把既有 LINE task 喚回前景，重新驗證畫面後按一次傳送；LINE 接受後再喚回第二次，確保最終停留在 LINE，而不是 PrivAgent 確認頁。

聊天內轉傳由 LINE 自己持有並轉送圖片，PrivAgent 不會取得圖片 bytes、URI 或像素，因此這條路徑也**無法重新編碼或驗證 EXIF 是否已被 LINE 移除**。首頁「選擇照片並傳送」仍是另一條路徑，才會在 App 內建立不含 EXIF／GPS 的新 JPEG。

### LINE 免觸控分享（選用、研究／側載功能）

1. 由本人或家屬按「LINE 免觸控設定」，閱讀用途與資料告知後，到 Android 無障礙設定手動開啟 **PrivAgent LINE 免觸控分享**。Android 不允許 App 代替使用者完成首次授權。
2. 第一次分享給某位家人時，確認其 **LINE 分享畫面顯示名稱**；它可能與 Android 通訊錄名稱不同。兩者都只存在手機 SharedPreferences，不進入 AI packet。
3. 使用者說「把最新照片傳給女兒」時，若已預先授予照片權限，App 可直接取得最新一張；否則仍會開啟 Android 系統照片選擇器。任意指定照片也一定由系統選擇器挑選。
4. 聊天內轉傳先建立60秒一次性能力；只有使用者在遮罩親自點選的單一座標可被用來長按一次，後續不用座標。專用服務在Android設定層限定只接收 `jp.naver.line.android` 事件；不截圖、不做OCR、不保存或上傳LINE畫面。
5. 前段狀態機只允許：**遮罩選點 → 同座標長按 → 黑色選單唯一「分享」→「選擇訊息」唯一「分享(1)」→ 快速面板唯一「更多」→「選擇傳送對象」**。收件人階段再以每次新產生的256-bit隨機金鑰做HMAC-SHA256精確比對，找不到時最多向下滑24頁；真正傳送前仍需語音或手動確認。
6. 聊天內轉傳不建立App圖片副本；首頁自行選圖的原流程才會在LINE讀完後清除快取副本。畫面辨識規則固定在手機程式碼內，雲端AI無權決定或產生點擊。HMAC只降低記錄／外洩可讀姓名的風險；無障礙服務比對當下仍必須短暫讀到LINE提供的可見節點文字。

此能力是為高齡／行動不便者的輔助流程；若要透過 Google Play 發布，須另外完成 Accessibility API 聲明、醒目揭露與政策資格審查。若未啟用專用服務，原本的 Android `ACTION_SEND` 手動 LINE 分享仍可使用。

資料集層級的政策引擎與評測：`make core-eval` 把 1,728 筆核心任務逐筆做出 ALLOW／ASK／LOCAL／STOP 決策，輸出 `derived_tasks.csv`、`packets.jsonl`、`summary.json`（每項指標附分子、分母與失敗清單）、`failures.json`、`manifest.json`（輸入與輸出 SHA-256、重跑指令）。細節見 [receiver/README.md](receiver/README.md)。

情境二推回攻擊：`make inference-attack` 讓攻擊者只拿到 6,144 則訊息的封包特徵（14 個布林、6 個列舉值），在其他手機環境上建表後推回模板家族、原文與手機身分，並量測每種特徵組合的等價類大小 k。原文不遮蔽時原文 100%、2,688 則看得到電話或帳號、192 支手機全被唯一指認；只看封包時風險等級 100% 可推回（這是封包的用途）、家族 61.5%、原文 16.7%、最小 k=64、沒有一支手機被唯一指認；開啟摘錄後家族 100%、原文 56.3%。方法與結果見 [docs/CHECK_MESSAGE_INFERENCE_ATTACK.md](docs/CHECK_MESSAGE_INFERENCE_ATTACK.md)。

## 可疑訊息檢查 demo

App 首頁「情境 02｜詐騙訊息檢查」：

1. 貼上訊息，或按「從目前的通知選一則」（需先開啟通知存取；銀行／驗證器等敏感 App 的通知不列出）。
2. 手機先以型別化占位符遮蔽電話、帳號、驗證碼、網址、Email、身分證與可辨識姓名，並萃取訊息立場、要求動作、憑證類型及反詐／查核脈絡等緊湊特徵；本機已判為高風險時一律不附文字摘錄。
3. 勾選AI判讀後，手動檢查會先預覽實際要送出的privacy packet。目前Receiver把相同packet交給本機Ollama/Qwen，作為未來雲端API的免費替身；取消同意時完全只用手機規則。
4. 結果分為高風險／請謹慎核實／暫無強烈訊號／資訊不足，不宣稱「安全」。所有文字由App固定reason code產生，AI不能輸出或執行任意UI指令；背景通知仍只有高風險會發警報。

雲端封包使用情境二 v4 契約：每次產生一次性 nonce 與簽發時間，Receiver 會阻擋過期、時鐘異常與重播；可揭露欄位（含連結分級、訊息立場、要求動作與憑證類型）與摘錄限制由 `receiver/policies/check_message_privacy_policy.json` 的可執行隱私預算控制。

勾選「自動檢查新進來的通知」後，每則新通知會在背景自動檢查（需通知存取權）：

- 略過常駐／進度／來電通知、群組摘要、敏感 App 與排除清單內的 App；同一則訊息被聊天 App 重新發布只檢查一次。
- 背景通知沒有逐筆預覽，因此即使啟用AI也只送特徵packet，不附摘錄；敏感App與排除清單仍一律不處理。
- 只有「高風險」會跳出「可疑訊息提醒」通知，其餘結果只列在 App 的「最近自動檢查」（最近 10 則，只存在記憶體）。

電腦端（Receiver 預設 8080，AI 後端 8000；`AI=` 可選 gemini、claude、local）：

```bash
make proxy AI=gemini       # 或 AI=claude；AI=local 需先 ollama serve 並 ollama pull qwen3.5:9b
make receiver-lan AI=gemini TOKEN=xxx   # 同一個 AI=；AI=local 時訊息檢查改用本機 Qwen
```

手機連線：USB 用 `adb reverse tcp:8080 tcp:8080` 並在 App 填 `http://127.0.0.1:8080`；同一 Wi-Fi 填 `http://<電腦區網 IP>:8080`。區網展示請先設定 `PRIVAGENT_API_TOKEN` 環境變數再啟動 Receiver，並在 App 填入相同存取碼。

沒有手機時可用 `python3 -m receiver.phone_sim` 模擬手機端；契約、流程與限制見 [receiver/README.md](receiver/README.md)。

目前正式的遮罩後本機Qwen測試見 [docs/PRIVACY_LOCAL_QWEN_EVALUATION.md](docs/PRIVACY_LOCAL_QWEN_EVALUATION.md)；隱私威脅模型與技術取捨見 [docs/CHECK_MESSAGE_PRIVACY.md](docs/CHECK_MESSAGE_PRIVACY.md)。原文Qwen結果只保留於 [docs/LOCAL_ACCURACY_MODE.md](docs/LOCAL_ACCURACY_MODE.md) 作歷史效能上限，不再是正式產品路徑。

## 目前可以取得的資料

| 類別 | 內容與限制 |
| --- | --- |
| 裝置／硬體 | 廠牌、型號、版本、安全更新、Android ID、語系、時區、螢幕、CPU ABI／核心數、RAM、內部儲存空間、開機時間 |
| 電池 | 容量、電流、charge counter、充電狀態、溫度、電壓、健康與供電來源；不支援的 property 記為 null |
| 網路 | 連線、transport、網路驗證、計量網路與頻寬；Wi-Fi SSID／BSSID 可能被系統遮蔽 |
| 感測器 | Sensor 清單與硬體規格，沒有連續讀取感測值 |
| App | Android 可見的已安裝 App，非完整手機 App 清單；最多 2000 筆 |
| 聯絡人 | 姓名、電話、Email、郵寄地址、群組 membership；依 mimeType 解讀 value；最多 10000 筆 Data rows |
| 行事曆 | 事件、描述、位置、時間與 recurrence rule；最多 1000 筆，非展開後的重複 Instances |
| 媒體 | 圖片／影片／音訊的檔名、類型、大小、日期、尺寸或長度；每類最多 500 筆，不讀取檔案內容／EXIF |
| 使用狀況 | 七天 Usage Stats 最多 300 筆 daily records、一天 Usage Events 最多 5000 筆 |
| 位置 | Last Known Location 與 ageMs；只讀快取，沒有主動定位或背景定位 |
| 通知 | 現有通知 snapshot 最多 200 筆、後續 posted／removed 事件；敏感 App 排除，疑似 OTP 內容遮蔽 |
| 畫面 | Accessibility events 及 active window tree；兩秒節流、去重、最多 200 節點／六層／每層 80 子節點 |

本版本未加入 SMS、通話紀錄、IMEI、其他 App 的私有檔案或完整歷史聊天紀錄。
Package visibility 與其他限制由 Android 控制，不繞過平台安全機制。

## Windows 編譯與安裝

開啟的是含 settings.gradle.kts 的專案根目錄，請保持純英文路徑。

建置版本固定為：

- Android Gradle Plugin 8.10.1
- Kotlin 2.1.20
- Gradle Wrapper 8.11.1（gradlew / gradlew.bat 與 gradle/wrapper 已補齊）
- JDK 17；Java 與 Kotlin target 都為 17
- SDK Platform 36、SDK Build Tools 35.0.0

用 Android Studio 的 SDK Manager 安裝 Platform 36 與 Build Tools 35.0.0。
local.properties 的 sdk.dir 指向本機 Android SDK；它不應提交到 Git。
此電腦的 JDK 17 放在 .tools 下（不提交到 Git）。

PowerShell 在專案根目錄執行：

```powershell
.\build.ps1
```

此指令會自動找 .tools 內的 JDK 17，執行 assembleDebug、lintDebug 與 testDebugUnitTest。
如果 PowerShell 阻擋本機腳本，可只對此程序執行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

也可自行指定 JDK：

```powershell
.\build.ps1 -JavaHome "C:\path\to\jdk-17"
```

APK 產生於 app/build/outputs/apk/debug/app-debug.apk。
測試與 Lint 報告位於 app/build/reports。

若要一次驗證接收端契約／HTTP 整合與 Android APK，可執行：

```powershell
.\verify_mvp.ps1
```

此指令會先跑 Receiver 的 Python 測試，再執行 `build.ps1`。Receiver HTTP 測試會暫時使用本機 loopback，不會把測試資料留在 `receiver/data`。

Android Studio：File → Settings → Build, Execution, Deployment → Build Tools → Gradle，
將 Gradle JDK 設為本機 JDK 17（可指定 .tools 下的 JDK 17 資料夾），再 Sync 與 Run。
勿沿用本機預設 JDK 25 搭配此 Gradle 版本。

手機開啟開發者選項及 USB debugging，接上 USB 並在手機接受電腦授權後：

```powershell
.\build.ps1 -Install
```

以上只會安裝 debug APK。正式發布需要獨立 release 簽章。

## App 使用順序

1. 如需雲端 AI，先從右上角「連線設定」填入 Receiver 網址與存取碼；未啟用雲端時仍可使用手機本機規則。
2. 情境 01：按「選擇照片並傳送」或「傳送最新一張照片」，選擇家人後依畫面完成照片清理與 LINE 分享。
3. 第一次使用 LINE 免觸控時，由本人或家屬在「LINE 免觸控」閱讀告知，再到 Android 系統設定手動啟用服務。
4. 情境 02：貼上訊息或從目前通知選一則，決定是否同意雲端與遮蔽後摘錄，再按「檢查這則訊息」。
5. 若要自動檢查新通知，先在底部「輔助與權限」開啟通知存取，再勾選自動檢查。
6. 浮動按鈕是選用功能，只提供上述照片分享與訊息檢查，不會啟動研究資料蒐集。

部分 Android 裝置會限制側載 App 的通知／無障礙設定。
請遵循系統允許的手動授權流程；無法開啟時仍可測試其他資料類別。

## 保留的研究模組：儲存與資料格式

以下為仍留在 repository 供研究與測試的舊資料蒐集模組；正式雙情境首頁不提供啟動、匯出或傳送這些原始資料的操作入口。

新資料保存在 App internal storage 的 raw_encrypted/*.enc。
以 Android Keystore 中的 AES-256 金鑰與 AES-GCM 加密，每筆使用獨立 nonce 與 authentication tag。
只在匯出串流中解密，App 不建立明文匯出暫存檔。

- 每檔最多約 5 MB，日期變更時輪替。
- 總量最多 25 MB；追加前先刪除最舊檔案。
- 檔案最後修改時間超過 7 天便會在啟動、追加或匯出時清理。
- 未開啟 App 且没有事件時，不會在背景單獨啟動定時清理。
- 阻止雲端備份與裝置轉移；加密金鑰不可跟資料檔一起搬到其他手機。
- 若升级後發現 v0.1 的 raw_events.ndjson，保留原檔並在首頁提示。
  這份舊檔仍為明文；請先匯出再用清除按鈕刪除，不會默默丟棄舊資料。

匯出格式為 UTF-8 NDJSON，每行一筆 JSON：

```json
{"schemaVersion":2,"ts":1790000000000,"sessionId":"uuid","source":"contacts","sensitivity":"P3","payload":{"status":"ok","chunkIndex":0,"items":[]}}
```

使用敏感度標籤：P0 非個人硬體狀態、P1 一般操作資訊、P2 可識別裝置／行為資訊、P3 私人內容。
這是研究分類，不等於匿名化；姓名與電話等在明文匯出中仍是原始內容。

集合查詢以每 25 筆分塊，最後一塊含總 count、truncated、maxRows；單一文字最多 512 字元。
source 的 payload.status 可能是 ok、empty、partial、truncated、permission_missing、
special_access_missing、provider_unavailable、no_cached_location、error 或 stopped。
缺欄位／provider 故障會獨立記錄該來源 error，不阻止其他來源。
UI 顯示每類結果與事件佇列溢位數；沒有資料時不會誤報已成功抓到內容。

通知與畫面過濾是 best effort：依常見 package 名稱及額外排除清單拒收，
略過密碼事件／密碼節點，遮蔽驗證碼類文字。請勿把這些過濾視為完整匿名化。

## 即時傳輸行為

即時模式採四階段設計：

1. `baseline`：啟動後完整蒐集所有已選來源，預設等待 30 秒累積事件，再送出第一批。
2. `periodic`：預設每 60 秒更新裝置、電池、網路與最後已知位置，並送出所有尚未確認的事件。
3. `immediate`：通知或 Accessibility 新事件在基線完成後，以 2 秒 debounce 觸發送出；可在 UI 關閉。
4. `manual`：使用者明確操作時，傳送本機保存的完整歷史資料，作為驗證與復原途徑。

基線可設定 5–600 秒，週期可設定 10–3600 秒。傳送採單一序列工作者；每批最多 500 筆／5 MiB，待送記憶體佇列最多 5000 筆／20 MiB。失敗批次會凍結同一組 bytes 與 Transfer ID，後續事件排在其後。只重試網路 I/O、HTTP 408、429 與 5xx；其他 4xx、409 或完整性不一致會停止並顯示錯誤。

## 開發接收端傳遞

這是為端到端串接準備的最初原版功能，會傳送完整、未匿名化的 NDJSON。請只連線到由你控制的開發環境，不要用真人敏感資料測試。

- 預設 Base URL 為 Android Emulator 可連到宿主機的 `http://10.0.2.2:8080`。
- 實機請改填開發電腦的區網 IP，例如 `http://192.168.1.20:8080`；接收服務需監聽 `0.0.0.0`，Windows Firewall 也需允許該連接埠。
- `GET {baseUrl}/health` 用於連線測試。
- `POST {baseUrl}/api/v1/transmissions` 使用 `application/x-ndjson` 與固定 Content-Length。
- 請求標頭包含 `X-Transfer-Id`、`X-Record-Count`、`X-Payload-Sha256`、`X-Client-Version`、`X-Transmission-Mode` 與 `X-Client-Sent-At`。
- `baseline`、`periodic`、`immediate` 另須包含 `X-Collection-Session-Id`，且須與每一筆 record 的 `sessionId` 相同。
- 接收端成功回應必須包含相同的 `transferId`、`receivedRecords`、`sha256`；即時批次還要回傳相同模式與 session。
- 在完整回執成功前，App 會為相同 SHA-256 沿用同一個 Transfer ID；連線中斷或回應遺失後可冪等重試。資料內容改變時才建立新的 ID。
- 傳送失敗或回執不一致都不會刪除本機資料，可修正接收端後重送。
- `GET /api/v1/context/latest?sessionId=...&limitPerSource=100` 提供後續情境判斷模組讀取同一 live session 的最新來源資料；手動傳輸不會混入。
- 為方便可信任區網測試，v0.4 開發版允許明文 HTTP；正式部署應停用並改用 HTTPS。

完整協定、模組邊界與分工建議見 [docs/REALTIME_PIPELINE.md](docs/REALTIME_PIPELINE.md)，接收端操作見 [receiver/README.md](receiver/README.md)。

## 測試

```powershell
.\build.ps1
```

JVM 測試涵蓋加密 round trip、nonce、篡改與錯誤金鑰、記錄上限、保留／輪替邊界、
敏感 App 排除、OTP 遮蔽、敏感類別預設 opt-in、即時佇列凍結／容量／session 隔離、重試分類，以及接收端 URL、端點與回執協定。

另附 Android instrumentation tests，驗證 Keystore 儲存／匯出、
停止與新工作階段隔離、App 清單及權限不足時的快照結果。
只在可丟棄的 Emulator 執行，因為它們會清除 App 內的測試資料：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

### 生成式隱私紅隊測試

專案另有固定 seed 的合成對抗 campaign，針對兩個正式情境生成收件人混淆、偽造／重複按鈕、模糊語音、水平頁籤、詐騙文字混淆、PII 洩漏與 Prompt Injection。初版共2,921項隱私不變條件檢查，另在 Android 裝置窮舉前置分享狀態機的25種 phase pair。完整威脅模型、初次實測及限制請見 [`docs/PRIVACY_ADVERSARIAL_TESTING.md`](docs/PRIVACY_ADVERSARIAL_TESTING.md)。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.PrivacyAdversarialTest" --console=plain
```

### 台灣繁中訊息資料集實測（目前主要基準）

目前主要以MediaTek Research的TS-Bench台灣華語安全配對資料，以及Cofacts真實LINE訊息資料進行情境二評估。TS-Bench已完成98筆詐騙／金融誘騙與98筆安全困難負例；Cofacts已依資料使用者授權完成5,000筆詐騙與5,000筆非詐騙高信度子集實測。原文只在repository外的本機資料目錄處理，不送AI Proxy，報告只保存統計與匿名反例ID。方法、結果及Cofacts授權重現步驟見 [`docs/TAIWAN_MESSAGE_DATASET_EVALUATION.md`](docs/TAIWAN_MESSAGE_DATASET_EVALUATION.md)。

另以Cofacts固定種子平衡抽樣200筆完成A／B／C初測：A本機規則Recall 22%／Specificity 96%；B隱私特徵＋本機Qwen為57%／27%；C隱私特徵＋遮蔽摘錄＋本機Qwen為74%／46%。C明顯增加攔截，但誤報仍高，尚不應直接作為強制警報。20筆一批、快取及prompt injection防禦方式見 [`docs/PRIVACY_ABC_AI_EVALUATION.md`](docs/PRIVACY_ABC_AI_EVALUATION.md)。

歷史準確率上限實驗另外使用8,600則Cofacts train、400則dev與1,000則held-out test，再以完全不同來源的TS-Bench驗證。未遮罩原文Qwen在TS-Bench達Accuracy 80.10%、Recall 84.69%，但不符合目前正式隱私邊界，因此不再採用。現行v4改成型別化遮罩、訊息立場／要求動作特徵、單次`risk_score`／`benign_score`與程式證據閘門；Cofacts held-out嚴格HIGH為Accuracy 57.20%、Recall 32.40%、Specificity 82.00%、F1 43.09%，HIGH＋CAUTION人工分流Recall為69.20%。已看過的TS-Bench僅作診斷：嚴格HIGH Accuracy 69.39%、Recall 51.02%、Specificity 87.76%、F1 62.50%。v4未在Cofacts全面超越v3，文件不宣稱整體準確率已提升。正式結果見 [`docs/PRIVACY_LOCAL_QWEN_EVALUATION.md`](docs/PRIVACY_LOCAL_QWEN_EVALUATION.md)，歷史上限見 [`docs/LOCAL_ACCURACY_MODE.md`](docs/LOCAL_ACCURACY_MODE.md)。

```powershell
.\scripts\fetch_tw_message_benchmarks.ps1
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.TaiwanMessageDatasetEvaluationTest" --console=plain
python .\scripts\privacy_abc_evaluation.py --per-class 100 --batch-size 20
python .\scripts\optimize_local_scam_classifier.py
python .\scripts\evaluate_local_accuracy_cross_domain.py
```

### 英文／簡體中文公開簡訊補充基準

可下載 UCI 的正常／垃圾英文簡訊，以及 FBS 約14K筆匿名化中文真實 spam，在本機批次送入正式 `MessageGuard`。原始資料不提交 Git，也不送到 AI Proxy；報告只保存統計與匿名反例 ID。實測方法、混淆矩陣、資料偏移與限制見 [`docs/EXTERNAL_SMS_DATASET_EVALUATION.md`](docs/EXTERNAL_SMS_DATASET_EVALUATION.md)。

```powershell
.\scripts\fetch_sms_benchmarks.ps1
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.privagentcollector.ExternalSmsDatasetEvaluationTest" --console=plain
```

實機驗收：Android 9／12／14／16 分別檢查首次拒絕權限、
Android 14 只選部分照片、撤銷同意、通知／畫面啟停、匯出取消、
儲存空間不足、快速停止再開始、重新開啟 App 與大量事件情境。

## 已知限制與下一階段

- 待送 outbox 目前只在程序記憶體；App 程序被系統終止時，未確認批次不會自動續送，但資料仍在本機加密紀錄，可用手動完整傳送復原。
- Android 15+ 對 `dataSync` 前景服務有總時數限制；到期會安全停止並提示重新啟動，不保證無限期常駐。
- `transmissionMode` 代表觸發傳送的原因，單一批次可能同時包含較早累積的其他事件。
- Receiver 未提供登入、TLS、WebSocket 或 SSE；Dashboard 每 5 秒刷新，決策模組應輪詢 latest-context。
- 未指定 `sessionId` 的 latest-context 只適合單機展示；正式多手機環境必須加入持久化 client/device instance ID 並固定查詢 session。

後續可加入持久化 outbox、collection cycle／batch sequence、裝置識別、事件匯流排，以及每筆的 purpose、taskId、requiredByTask、
allowedToLeaveDevice、privacyTransform 與 derivedPayload，建立匿名化資料與本機映射庫。
只把經使用者確認的衍生資料提供給 AI。

若轉成 Google Play 正式產品，需依實際核心功能縮小權限範圍、
提供隱私揭露與政策申報。此研究側載版不是已完成上架審核的產品。
