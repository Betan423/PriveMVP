"""AI backend for the Receiver planner: one /generate contract, three choosable backends.

  gemini  Gemini official API      (GEMINI_API_KEY)
  claude  Claude API               (ANTHROPIC_API_KEY)
  ollama  local model via Ollama   (no key; OLLAMA_URL, default http://127.0.0.1:11434)

Run: python official_api.py --backend gemini|claude|ollama [--model ...] [--port 8000]
Standard library only. Keys are read from the environment and never logged, stored or echoed.
"""

import argparse
import json
import logging
import os
import threading
import urllib.error
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

GEMINI_ROOT = "https://generativelanguage.googleapis.com/v1beta/models"
CLAUDE_URL = "https://api.anthropic.com/v1/messages"
CLAUDE_API_VERSION = "2026-10-21"
# Models that accept server-side refusal fallbacks ("default" form); others are sent without them.
CLAUDE_FALLBACK_MODELS = ("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
DEFAULT_MODELS = {"gemini": "gemini-flash-latest", "claude": "claude-opus-5-5", "ollama": "qwen3.5:9b"}
MODEL_ENV = {"gemini": "GEMINI_MODEL", "claude": "CLAUDE_MODEL", "ollama": "OLLAMA_MODEL"}
KEY_ENV = {"gemini": "GEMINI_API_KEY", "claude": "ANTHROPIC_API_KEY"}
# The Receiver prompts already ask for exactly one JSON object; this repeats it at the system level.
JSON_ONLY = "只輸出一個 JSON 物件，不要 Markdown、不要說明文字。"
MAX_REQUEST_BYTES = 256 * 1024
MAX_MESSAGE_CHARS = 100000
UPSTREAM_TIMEOUT = 40.0

Generator = Callable[[str], tuple[str, str | None]]


class UpstreamRefused(Exception):
    """The model declined; surfaces as 502 so the Receiver falls back to its rules."""


def _post(url: str, body: dict, headers: dict[str, str]) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=UPSTREAM_TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def generate(api_key: str, model: str, message: str) -> tuple[str, str | None]:
    """Gemini: single stateless call; returns (answer text, upstream model version)."""
    body = {
        "contents": [{"role": "user", "parts": [{"text": message}]}],
        # Deterministic JSON output keeps the planner auditable.
        "generationConfig": {"temperature": 0, "responseMimeType": "application/json"},
    }
    value = _post(f"{GEMINI_ROOT}/{model}:generateContent", body, {"x-goog-api-key": api_key})
    parts = value["candidates"][0]["content"]["parts"]
    return "".join(part.get("text", "") for part in parts), value.get("modelVersion")


def generate_claude(api_key: str, model: str, message: str) -> tuple[str, str | None]:
    """Claude Messages API over plain HTTP (keeps this service dependency-free)."""
    body: dict = {
        "model": model,
        "max_tokens": 4096,
        "system": JSON_ONLY,
        "messages": [{"role": "user", "content": message}],
        # A short classification: low effort keeps latency and cost down.
        "output_config": {"effort": "low"},
    }
    headers = {"x-api-key": api_key, "anthropic-version": CLAUDE_API_VERSION}
    if model in CLAUDE_FALLBACK_MODELS:
        body["fallbacks"] = "default"
        headers["anthropic-beta"] = "server-side-fallback-2026-07-01"
    value = _post(CLAUDE_URL, body, headers)
    if value.get("stop_reason") == "refusal":
        raise UpstreamRefused()
    text = "".join(block.get("text", "") for block in value["content"] if block.get("type") == "text")
    return text, value.get("model")


def generate_ollama(base_url: str, model: str, message: str) -> tuple[str, str | None]:
    """Local model through Ollama's chat API; nothing leaves this computer."""
    body = {
        "model": model,
        "messages": [{"role": "system", "content": JSON_ONLY}, {"role": "user", "content": message}],
        "stream": False,
        "think": False,
        "format": "json",
        "options": {"temperature": 0},
    }
    value = _post(base_url.rstrip("/") + "/api/chat", body, {})
    return value["message"]["content"], value.get("model")


def make_generator(backend: str, model: str, secret: str) -> Generator:
    """secret is the API key, or the Ollama base URL for the local backend."""
    function = {"gemini": generate, "claude": generate_claude, "ollama": generate_ollama}[backend]
    return lambda message: function(secret, model, message)


class Handler(BaseHTTPRequestHandler):
    server_version = "PrivAgentAIBackend/2"

    def log_message(self, format: str, *args: object) -> None:
        # Never log request bodies; the status line is enough.
        logging.info("%s %s", self.command, self.path)

    def _json(self, status: int, value: object) -> None:
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802 - inherited API name
        if self.path == "/health":
            self._json(HTTPStatus.OK, {"status": "ok", "backend": self.server.backend, "model": self.server.model})
        else:
            self._json(HTTPStatus.NOT_FOUND, {"detail": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - inherited API name
        if self.path != "/generate":
            self._json(HTTPStatus.NOT_FOUND, {"detail": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 < length <= MAX_REQUEST_BYTES:
            self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"detail": "request size out of range"})
            return
        try:
            message = json.loads(self.rfile.read(length).decode("utf-8"))["message"].strip()
        except (UnicodeDecodeError, ValueError, KeyError, TypeError, AttributeError):
            self._json(HTTPStatus.UNPROCESSABLE_ENTITY, {"detail": "body must be {\"message\": string}"})
            return
        if not message or len(message) > MAX_MESSAGE_CHARS:
            self._json(HTTPStatus.UNPROCESSABLE_ENTITY, {"detail": "message 不可為空白或過長"})
            return
        try:
            with self.server.lock:
                answer, version = self.server.generate(message)
        except urllib.error.HTTPError as error:
            error.close()
            logging.warning("%s HTTP %s", self.server.backend, error.code)
            self._json(HTTPStatus.BAD_GATEWAY, {"detail": f"{self.server.backend} HTTP {error.code}"})
            return
        except Exception as error:  # Upstream defects must surface as 502 so the Receiver falls back to rules.
            logging.warning("%s failed: %s", self.server.backend, type(error).__name__)
            self._json(HTTPStatus.BAD_GATEWAY, {"detail": "AI 請求失敗"})
            return
        logging.info("generated with %s", version)
        self._json(HTTPStatus.OK, {"answer": answer})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", choices=sorted(DEFAULT_MODELS), default=os.environ.get("AI_BACKEND", "gemini"))
    parser.add_argument("--model", help="default: per backend, or GEMINI_MODEL / CLAUDE_MODEL / OLLAMA_MODEL")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    backend = args.backend
    model = args.model or os.environ.get(MODEL_ENV[backend]) or DEFAULT_MODELS[backend]
    if backend == "ollama":
        secret = os.environ.get("OLLAMA_URL") or DEFAULT_OLLAMA_URL
    else:
        secret = os.environ.get(KEY_ENV[backend], "").strip()
        if not secret:
            parser.error(f"--backend {backend} 需要環境變數 {KEY_ENV[backend]}")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.backend, server.model, server.lock = backend, model, threading.Lock()
    server.generate = make_generator(backend, model, secret)
    print(f"AI backend {backend} ({model}) on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
