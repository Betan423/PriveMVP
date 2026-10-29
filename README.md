# PrivAgent Raw Collector MVP v0.4

Android 9（API 28）以上的研究用本機資料蒐集器。compileSdk / targetSdk 36。
此版本處理資料取得、權限、儲存、匯出、停止控制，以及手機到電腦的手動／即時傳遞。即時模式會先建立基線，再傳送定期狀態與通知／畫面事件。

另含「可疑訊息檢查」demo（核心情境 CHECK_MESSAGE）：手機端遮蔽與抽特徵，經電腦 Receiver 交給雲端 AI（Gemini proxy）判讀後回傳。照片分享與手機救援尚未串接。

## Repository 導覽

- `app/`：Android Collector 與即時傳輸服務。
- `receiver/`：Python 標準函式庫實作的電腦接收端。
- `gemini-proxy/`：Gemini 網頁版 HTTP proxy（可疑訊息判讀的雲端 AI），Cookie 只放本機設定檔。
- `docs/REALTIME_PIPELINE.md`：即時管線、契約與模組化分工基線。
- `docs/competition/`：決賽與後續開發整合文件。
- `dataset/`：競賽資料集、生成與驗證程式。
- `data-template/`：任務／資料格式範本與示例。
- `.github/workflows/ci.yml`：Receiver、Android tests、Lint 與 APK 自動建置。

GitHub 推送與遠端已有 commit 時的安全整合方式見 [docs/GITHUB_UPLOAD.md](docs/GITHUB_UPLOAD.md)。

## 快速開始（Makefile）

Linux／macOS 在專案根目錄執行 `make` 會列出所有指令。常用流程：

```bash
make setup                 # 建 gemini-proxy/.venv；再依 gemini-proxy/README.md 放入自己的 Cookie
make test                  # Receiver、proxy、Android 全部測試
make proxy                 # 終端機 A：Gemini proxy（127.0.0.1:8000）
make receiver              # 終端機 B：Receiver（127.0.0.1:8080）
make demo                  # 終端機 C：模擬手機送一則訊息；make demo MESSAGE="..."
make demo-dataset ENV=1    # 送資料集第 1 個環境的 32 則訊息
```

真手機：`make install` 安裝 APK，改用 `make receiver-lan TOKEN=自訂存取碼` 啟動 Receiver，
App 填它印出的位址與同一組存取碼。不接 AI 只用規則可用 `make receiver-rules`。
Android 建置需要 JDK 17 與 Android SDK（`JAVA_HOME`、`ANDROID_HOME`）。

## 可疑訊息檢查 demo

App 首頁最上方的「可疑訊息檢查」：

1. 貼上訊息，或按「從目前的通知選一則」（需先開啟通知存取；銀行／驗證器等敏感 App 的通知不列出）。
2. 手機本機遮蔽電話、帳號、驗證碼、網址、Email、身分證、稱呼前姓名與通訊錄姓名（已授權聯絡人權限時），並抽出風險特徵。
3. 勾選「同意使用雲端 AI 判讀」時，先顯示「確認要傳送的內容」，按「傳送」才送到 Receiver；未勾選則只在手機本機判讀，不連網。
4. 結果以三級顯示：高風險／需要核實／資訊不足，不會顯示「安全」。文字由 App 依 reason code 套固定措辭；雲端只能提高、不能降低本機判定的風險。

電腦端（Linux 範例；Receiver 預設 8080，Gemini proxy 8000）：

```bash
cd ~/Documents/gemini-proxymeow && .venv/bin/python -m uvicorn api:app --host 127.0.0.1 --port 8000
cd ~/Documents/PriveMVP && python3 -m receiver.server --host 0.0.0.0 --port 8080
```

手機連線：USB 用 `adb reverse tcp:8080 tcp:8080` 並在 App 填 `http://127.0.0.1:8080`；同一 Wi-Fi 填 `http://<電腦區網 IP>:8080`。區網展示請先設定 `PRIVAGENT_API_TOKEN` 環境變數再啟動 Receiver，並在 App 填入相同存取碼。

沒有手機時可用 `python3 -m receiver.phone_sim` 模擬手機端；契約、流程與限制見 [receiver/README.md](receiver/README.md)。

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

## 使用順序

1. 閱讀 App 首頁蒐集說明並勾選同意。
2. 勾選資料類別。聯絡人、媒體、位置、使用狀況、通知與畫面預設不選。
3. 按「要求所選類別的一般權限」。拒絕某項權限仍可取得其他資料。
4. 需要通知、Usage Access、Accessibility 時，分別到系統設定開啟。
   Accessibility 另有 App 內的獨立告知同意。
5. 單次模式：按「開始蒐集」會執行一次快照；之後可按「蒐集一次快照」再取得。
6. 即時模式：先填入電腦 Receiver Base URL、基線秒數與定期間隔，按「測試接收端連線」，再按「啟動即時模式」。
7. 即時模式會顯示持續前景通知；基線完成後先送一次，之後依設定週期傳送。通知與畫面事件可選擇 2 秒 debounce 後立即送出。
8. 已勾選且啟用的通知與畫面服務會在目前工作階段接收事件。
9. 按「停止蒐集」或「停止即時模式」會取消目前階段；舊階段的排隊事件不能寫進新階段。
   程序重啟一律停止，不因先前授權自動開始。
10. 「匯出資料」會先告知明文風險，再由系統文件選擇器選擇目的地。
11. 「手動傳送全部原始資料」保留為完整回送／故障復原功能，操作前須停止蒐集與即時模式。
12. 傳送成功後本機資料仍會保留；接收端回執的筆數、Transfer ID、SHA-256、模式與 session 必須吻合才會顯示成功。
13. 「停止並清除」會確認後停止、清空待送佇列、刪除本機檔案與 Keystore 金鑰。
   已匯出的檔案需自行刪除。

部分 Android 裝置會限制側載 App 的通知／無障礙設定。
請遵循系統允許的手動授權流程；無法開啟時仍可測試其他資料類別。

## 儲存與資料格式

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
