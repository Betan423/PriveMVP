# Gemini HTTP API

> **Repo 版本說明（PriveMVP）**：此資料夾是 Gemini 網頁版 HTTP proxy，供 `receiver` 的可疑訊息判讀呼叫（`POST /generate`，預設 `http://127.0.0.1:8000`）。
> Cookie 只能放在本機的 `config.json` 或 `cookies.txt`（已被 `.gitignore` 排除）。原專案的 `test.py`、`chat.py`、`history-chat.py` 內含寫死的 Cookie，**沒有納入 repo**；
> 因此請在 `config.json` 填入 `SECURE_1PSID`／`SECURE_1PSIDTS`，不要設定 `CLIENT_SOURCE: "test.py"`。下文提到 `test.py` 的段落只適用於原作者的本機環境。

`api.py` 將 `history-chat.py` 的 Gemini 網頁版聊天功能提供為 HTTP API。
`proxy.py` 是獨立的 Enterprise 範例，不是此 HTTP 服務的後端。

## 使用目前可工作的 test.py

目前 `config.json` 的 `CLIENT_SOURCE` 設為 `test.py`，HTTP 服務會使用
`test.py` 的 `create_client()`；不需另外複製 Cookie。
該檔已加上 main guard，匯入時不會執行原本的互動或發送訊息。
要恢復 `config.json` / `cookies.txt` 模式，將 `CLIENT_SOURCE` 改成 `config`。

啟動後使用此入口，直接執行與 `test.py` 相同的 `generate_content()`：

```bash
.venv/bin/python -m uvicorn api:app --host 127.0.0.1 --port 8000
```

```bash
curl --fail-with-body http://127.0.0.1:8000/generate \
  -H 'Content-Type: application/json' \
  -d '{"message":"給我一個問題"}'
```

`/generate` 是單次問答，不保留對話上下文，也不根據套件帳號狀態預先攔截。
`/chat` 與 `/ready` 仍保留先前的狀態檢查，因此可能與 `/generate` 的結果不同。
目前保留 `test.py` 原有建構參數；其中相鄰字串會被 Python 串成單一參數。
已驗證程式呼叫方式，實際 Google 生成仍需在可正常執行 `test.py` 的環境測試。

若啟動時載入 `https://gemini.google.com/app` 逾時，服務會最多嘗試三次，
每次關閉失敗 client 後重新建立。套件的 `init(timeout=...)` 在取得初始 token 後
才生效，無法直接調整這個登入頁面的預設 30 秒逾時。
若三次仍失敗，先執行 `.venv/bin/python test.py` 比較同一環境的連線結果。

## 安裝與啟動

需要 Python 3.10 以上。

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt

nano config.json
# 填入兩個 Cookie 並儲存後再啟動
.venv/bin/python -m uvicorn api:app --host 127.0.0.1 --port 8000
```

`config.json` 與 `api.py` 放在同一目錄，內容如下（將值換成實際 Cookie）：

```json
{
  "SECURE_1PSID": "你的 __Secure-1PSID 值",
  "SECURE_1PSIDTS": "你的 __Secure-1PSIDTS 值"
}
```

HTTP 版從本機檔案讀取登入 Cookie，不需要設定環境變數。更新檔案後需重啟服務。
此檔已加入 `.gitignore`。

若網頁可正常聊天，但只有兩個 Cookie 的程式仍失敗，可測試完整 Cookie：

1. 在可正常聊天的 Gemini 頁面按 F12，開啟 Network，送出一句訊息。
2. 選取該次 Gemini 的 `StreamGenerate` 請求，找到 Request Headers 的 `Cookie`。
3. 將完整值貼到 `api.py` 同目錄的 `cookies.txt`，單行即可，可包含 `Cookie:` 前綴。
4. 執行 `.venv/bin/python check_account.py`，以全新快取檢查狀態。

有 `cookies.txt` 時會優先使用其中的所有 Cookie，忽略 `config.json` 的兩個 Cookie。
刪除或重新命名 `cookies.txt` 即恢復原設定。此檔已加入 `.gitignore`，不要貼到聊天或提交。
這是比較登入資料差異的診斷方式，尚未證實能解決 1062。

Cookie 使用 Gemini 登入帳號的值。API 文件：<http://127.0.0.1:8000/docs>。
此服務沒有 HTTP 身分驗證，預設只監聽本機。

若出現 `ModuleNotFoundError: No module named 'fastapi'`，請重新執行上述安裝指令，
並使用 `.venv/bin/python -m uvicorn` 啟動，確保安裝套件與啟動服務使用同一個 Python 環境。

## curl

建立新對話並取得回答：

```bash
curl --fail-with-body http://127.0.0.1:8000/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好，請用繁體中文介紹自己"}'
```

回傳格式：

```json
{"conversation_id":"產生的 UUID", "answer":"Gemini 的回答", "cid":"Gemini 對話 ID"}
```

將回傳的 `conversation_id` 帶入下一次請求，即可延續上下文：

```bash
curl --fail-with-body http://127.0.0.1:8000/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"請把剛才的回答縮成一句話", "conversation_id":"貼上回傳的 UUID"}'
```

省略 `conversation_id` 即開始另一個新對話。對話對應保存在記憶體，重啟後失效；請使用單一 worker 啟動服務。

```bash
# 健康檢查
curl --fail-with-body http://127.0.0.1:8000/health

# Gemini 網頁端近期紀錄（取決於帳號與上游支援）
curl --fail-with-body http://127.0.0.1:8000/chats/recent

# 移除本機對話對應，不會刪除 Gemini 網頁端紀錄
curl --fail-with-body -X DELETE http://127.0.0.1:8000/chats/貼上回傳的UUID
```

空白訊息回傳 422、不存在的對話回傳 404，上游呼叫失敗回傳 502。

## ACCOUNT_REJECTED / 1062

`Gemini client initialized successfully` 只表示 client 初始化完成，並不保證帳號可以聊天。
若上游已回報 `ACCOUNT_REJECTED`，`/chat`、`/chats/recent` 與 `/ready` 會直接回傳
503，JSON 的 `detail.code` 為 `ACCOUNT_REJECTED`，避免進入套件的多次重試。
`/health` 僅表示 HTTP 服務存活，仍回傳 200。

請用提供 Cookie 的同一個 Google 帳號登入 Gemini 網頁，確認能正常送出訊息。
重新取得同一登入工作階段的兩個 Cookie，更新 `config.json` 後重啟服務。
若網頁也拒絕存取，需先處理 Google 帳號或組織的使用權限；HTTP 包裝層無法解除上游限制。
單憑 1062 無法判定完整原因，本次紀錄另有明確的 `ACCOUNT_REJECTED`。

注意：套件也會把未定義的原始帳號狀態轉成 `ACCOUNT_REJECTED`。
若原始帳號狀態是 1062，目前不能據此解讀為帳號被封鎖。
HTTP 版會保留此差異，回傳 `503`、`detail.code: UNKNOWN_ACCOUNT_STATUS`
及 `detail.upstream_status: 1062`。此處理只修正錯誤分類，尚未解決上游生成失敗。

本機安裝的套件會優先嘗試快取 Cookie，舊快取也是可能的干擾因素。
可在停止 HTTP 服務後執行 `.venv/bin/python check_account.py`，
用全新的暫存快取檢查 `config.json` 的登入狀態，不修改既有快取，也不需要手動設定環境變數。
這不會送出聊天訊息；狀態正常也不保證訊息生成成功。
`chat.py` 仍使用程式內寫死的 Cookie，不會讀取 `config.json`，請以 HTTP 版進行測試。

```bash
curl -i http://127.0.0.1:8000/ready
```
