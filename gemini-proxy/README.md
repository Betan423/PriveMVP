# AI 後端（Gemini／Claude／本機）

Receiver 的 AI 規劃器透過這個小服務呼叫 AI。三種後端可自由切換，對 Receiver 來說介面完全一樣（`POST /generate`）。
只用 Python 標準函式庫，不需要 venv 或安裝套件；不使用任何網頁版 Cookie。

| `AI=` | 後端 | 需要 | 資料去向 |
|---|---|---|---|
| `gemini`（預設） | Gemini 官方 API | `GEMINI_API_KEY`（<https://aistudio.google.com/apikey>） | Google |
| `claude` | Claude API | `ANTHROPIC_API_KEY`（<https://platform.claude.com/settings/keys>） | Anthropic |
| `local` | 本機 Ollama | 先 `ollama pull qwen3.5:9b` 並啟動 Ollama | 不出這台電腦 |

不論哪一種，送出去的都只有手機產生的隱私封包（特徵與遮蔽後摘錄），不是訊息原文。

## 設定與啟動

```bash
make setup                 # 建立 .env；把要用的 Key 取消註解並填入（.env 已被 git 忽略）
make proxy                 # 用 .env 的 AI=（預設 gemini）
make proxy AI=claude       # 臨時改用 Claude
make proxy AI=local        # 改用本機 Ollama
```

不用 make 時：`ANTHROPIC_API_KEY=... python3 gemini-proxy/official_api.py --backend claude --port 8000`。

| 選項 | 說明 |
|---|---|
| `--backend` | `gemini`、`claude`、`ollama`（預設讀環境變數 `AI_BACKEND`，再來是 `gemini`） |
| `--model` | 預設 `gemini-flash-latest`／`claude-opus-5-5`／`qwen3.5:9b`；也可用 `GEMINI_MODEL`、`CLAUDE_MODEL`、`OLLAMA_MODEL` |
| `--host`、`--port` | 預設 `127.0.0.1:8000` |
| `OLLAMA_URL` | 本機後端的 Ollama 位址，預設 `http://127.0.0.1:11434` |

只聽本機；要讓手機從外網用，請開 Receiver 的公開模式（`make public` + `make tunnel`），**不要**直接把這個服務開出去。

## 行為

- 每次呼叫都是獨立、無對話歷史，要求只回傳 JSON（Gemini temperature 0、Claude effort low、Ollama JSON 模式）。
- Claude 預設 `claude-opus-5-5`，並開啟伺服器端拒答備援（`fallbacks: "default"`）；模型若拒答，一律回 502。
- 上游任何失敗（Key 錯誤、配額 429、逾時、拒答、格式不符、Ollama 沒開）都回 **502**，Receiver 會自動退回本機規則。
- 不記錄請求內容，也不記錄 Key；log 只有方法、路徑、後端與模型版本。

## API

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/health` | `{"status":"ok","backend":"claude","model":"..."}` |
| POST | `/generate` | 請求 `{"message":"..."}`，回應 `{"answer":"..."}` |

```bash
curl -s http://127.0.0.1:8000/health
curl -s -X POST http://127.0.0.1:8000/generate -H 'Content-Type: application/json' \
  -d '{"message":"只回傳 JSON：{\"ok\":true}"}'
```

## 測試

```bash
make test-proxy      # 不需要 API Key，也不連網（上游為假的）
```
