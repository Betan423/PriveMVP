"""Official Gemini API backend with the same /generate contract as api.py.

Run: GEMINI_API_KEY=... python official_api.py [--port 8000]
Standard library only. The key is read from the environment and never logged or stored.
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

API_ROOT = "https://generativelanguage.googleapis.com/v1beta/models"
DEFAULT_MODEL = "gemini-flash-latest"
MAX_REQUEST_BYTES = 256 * 1024
MAX_MESSAGE_CHARS = 100000
UPSTREAM_TIMEOUT = 40.0


def generate(api_key: str, model: str, message: str) -> tuple[str, str | None]:
    """Single stateless call; returns (answer text, upstream model version)."""
    body = {
        "contents": [{"role": "user", "parts": [{"text": message}]}],
        # The Receiver prompts ask for exactly one JSON object; deterministic output keeps the planner auditable.
        "generationConfig": {"temperature": 0, "responseMimeType": "application/json"},
    }
    request = urllib.request.Request(
        f"{API_ROOT}/{model}:generateContent",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=UPSTREAM_TIMEOUT) as response:
        value = json.loads(response.read().decode("utf-8"))
    parts = value["candidates"][0]["content"]["parts"]
    return "".join(part.get("text", "") for part in parts), value.get("modelVersion")


class Handler(BaseHTTPRequestHandler):
    server_version = "GeminiOfficialProxy/1"

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
            self._json(HTTPStatus.OK, {"status": "ok", "backend": "official", "model": self.server.model})
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
                answer, version = generate(self.server.api_key, self.server.model, message)
        except urllib.error.HTTPError as error:
            error.close()
            logging.warning("Gemini API HTTP %s", error.code)
            self._json(HTTPStatus.BAD_GATEWAY, {"detail": f"Gemini API HTTP {error.code}"})
            return
        except Exception as error:  # Upstream defects must surface as 502 so the Receiver falls back to rules.
            logging.warning("Gemini API failed: %s", type(error).__name__)
            self._json(HTTPStatus.BAD_GATEWAY, {"detail": "Gemini 請求失敗"})
            return
        logging.info("generated with %s", version)
        self._json(HTTPStatus.OK, {"answer": answer})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--model", default=os.environ.get("GEMINI_MODEL", DEFAULT_MODEL))
    args = parser.parse_args()
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        parser.error("請設定環境變數 GEMINI_API_KEY")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.api_key, server.model, server.lock = api_key, args.model, threading.Lock()
    print(f"Gemini official API proxy on http://{args.host}:{args.port} (model {args.model})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
