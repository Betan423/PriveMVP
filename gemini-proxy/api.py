"""HTTP interface for the Gemini web chat client. Run with uvicorn api:app."""

import asyncio
import json
import importlib.util
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from gemini_webapi.constants import AccountStatus
from gemini_webapi.exceptions import TimeoutError as GeminiTimeoutError
from pydantic import BaseModel, Field

from gemini_client import StatusAwareClient


CONFIG_FILE = Path(__file__).resolve().with_name("config.json")


def load_cookie_jar(path: Path = CONFIG_FILE) -> dict[str, str]:
    header_file = path.with_name("cookies.txt")
    if not header_file.exists():
        psid, psidts = load_cookies(path)
        return {"__Secure-1PSID": psid, "__Secure-1PSIDTS": psidts}
    try:
        header = header_file.read_text(encoding="utf-8").strip()
    except OSError:
        raise RuntimeError("無法讀取 cookies.txt") from None
    if header.lower().startswith("cookie:"):
        header = header[7:].strip()
    cookies = {}
    for item in header.split(";"):
        name, separator, value = item.strip().partition("=")
        if (not separator or not name or not value or
                any(c.isspace() for c in name + value) or name in cookies):
            raise RuntimeError("cookies.txt 格式錯誤，請貼上單一請求的完整 Cookie 值")
        cookies[name] = value
    if not all(cookies.get(key) for key in ("__Secure-1PSID", "__Secure-1PSIDTS")):
        raise RuntimeError("cookies.txt 缺少 __Secure-1PSID 或 __Secure-1PSIDTS")
    return cookies


def create_client():
    config = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    if config.get("CLIENT_SOURCE") == "test.py":
        spec = importlib.util.spec_from_file_location(
            "working_gemini_chat", CONFIG_FILE.with_name("test.py")
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.create_client()
    cookies = load_cookie_jar()
    client = StatusAwareClient(cookies["__Secure-1PSID"], cookies["__Secure-1PSIDTS"])
    client.cookies = cookies
    return client


def load_cookies(path: Path = CONFIG_FILE) -> tuple[str, str]:
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise RuntimeError("找不到 config.json，請在 api.py 同目錄建立並填入 Cookie") from None
    except (ValueError, OSError):
        raise RuntimeError("無法讀取 config.json，請確認檔案權限與 JSON 格式") from None
    if not isinstance(config, dict):
        raise RuntimeError("config.json 必須是 JSON 物件")
    cookies = tuple(config.get(key) for key in ("SECURE_1PSID", "SECURE_1PSIDTS"))
    if any(not isinstance(value, str) or not value.strip() for value in cookies):
        raise RuntimeError("請在 config.json 填入 SECURE_1PSID 與 SECURE_1PSIDTS")
    return tuple(value.strip() for value in cookies)


@asynccontextmanager
async def lifespan(app: FastAPI):
    client = await initialize_client()
    try:
        app.state.client = client
        app.state.chats = {}
        # Serialize requests because the upstream client shares account state.
        app.state.lock = asyncio.Lock()
        yield
    finally:
        await client.close()


async def initialize_client():
    for attempt in range(1, 4):
        client = create_client()
        try:
            await client.init()
            return client
        except GeminiTimeoutError:
            await client.close()
            if attempt == 3:
                raise RuntimeError(
                    "連續三次載入 Gemini 登入頁面逾時。請以 .venv/bin/python test.py "
                    "確認同一環境是否仍能連線；這次錯誤發生於登入初始化階段。"
                ) from None
            logging.warning("Gemini 初始化逾時（%s/3），即將重新連線", attempt)
            await asyncio.sleep(attempt * 2)
        except BaseException:
            await client.close()
            raise


app = FastAPI(title="Gemini HTTP API", lifespan=lifespan)


def require_account_access():
    raw_status = getattr(app.state.client, "raw_account_status", None)
    if raw_status is not None and raw_status not in {int(status) for status in AccountStatus}:
        raise HTTPException(503, detail={
            "code": "UNKNOWN_ACCOUNT_STATUS",
            "upstream_status": raw_status,
            "message": "Gemini 回傳套件尚未定義的帳號狀態，無法判定其意義；"
                       "套件會將未知值轉為 ACCOUNT_REJECTED。",
        })
    if app.state.client.account_status == AccountStatus.ACCOUNT_REJECTED:
        raise HTTPException(503, detail={
            "code": "ACCOUNT_REJECTED",
            "message": "Gemini 拒絕目前帳號存取。請確認同一帳號能在 Gemini 網頁聊天，"
                       "重新取得該帳號的 Cookie，更新 config.json 後重啟服務。",
        })


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=100000)
    conversation_id: str | None = None


class ChatResponse(BaseModel):
    conversation_id: str
    answer: str
    cid: str | None


class GenerateRequest(BaseModel):
    message: str = Field(min_length=1, max_length=100000)


@app.post("/generate")
async def generate(body: GenerateRequest):
    message = body.message.strip()
    if not message:
        raise HTTPException(422, "message 不可為空白")
    async with app.state.lock:
        try:
            response = await app.state.client.generate_content(message)
            return {"answer": response.text}
        except Exception:
            logging.exception("Gemini generation failed")
            raise HTTPException(502, "Gemini 請求失敗，請查看伺服器紀錄") from None


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/ready")
async def ready():
    require_account_access()
    return {"status": "ok", "account_status": app.state.client.account_status.name}


@app.post("/chat", response_model=ChatResponse)
async def send_message(body: ChatRequest):
    message = body.message.strip()
    if not message:
        raise HTTPException(422, "message 不可為空白")
    async with app.state.lock:
        conversation_id = body.conversation_id
        if conversation_id is not None and conversation_id not in app.state.chats:
            raise HTTPException(404, "找不到對話；伺服器重啟後需建立新對話")
        require_account_access()
        is_new = conversation_id is None
        try:
            chat = (app.state.client.start_chat() if is_new
                    else app.state.chats[conversation_id])
            response = await chat.send_message(message, temporary=False)
        except Exception:
            logging.exception("Gemini request failed")
            raise HTTPException(502, "Gemini 請求失敗，請查看伺服器紀錄") from None
        if is_new:
            conversation_id = str(uuid4())
            app.state.chats[conversation_id] = chat
        return ChatResponse(
            conversation_id=conversation_id, answer=response.text, cid=chat.cid
        )


@app.get("/chats/recent")
async def recent_chats():
    async with app.state.lock:
        require_account_access()
        try:
            chats = app.state.client.list_chats() or []
            return {"chats": [{"cid": chat.cid, "title": chat.title} for chat in chats]}
        except Exception:
            logging.exception("Failed to list Gemini chats")
            raise HTTPException(502, "讀取近期紀錄失敗，請查看伺服器紀錄") from None


@app.delete("/chats/{conversation_id}")
async def delete_chat(conversation_id: str):
    async with app.state.lock:
        if conversation_id not in app.state.chats:
            raise HTTPException(404, "找不到對話")
        del app.state.chats[conversation_id]
        return {"deleted": True}
