"""HTTP API and operator dashboard for Collector transmissions."""

from __future__ import annotations

import argparse
import hmac
import html
import json
import os
import re
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Iterator
from urllib.parse import parse_qs, urlsplit

from .assistant import (
    DEFAULT_PLANNER_TIMEOUT,
    DEFAULT_PLANNER_URL,
    MAX_PACKET_BYTES,
    MessageAssistant,
    RequestReplayGuard,
    gemini_proxy_planner,
    parse_packet,
    parse_plan_packet,
)
from .contracts import (
    API_VERSION,
    DEFAULT_MAX_BODY_BYTES,
    DEFAULT_MAX_LINE_BYTES,
    ReceiverError,
    validate_client_sent_at,
    validate_client_version,
    validate_collection_session_id,
    validate_record_count,
    validate_sha256,
    validate_transmission_mode,
)
from .demo_feed import DemoFeed, phone_steps
from .storage import ReceiverStorage

DEMO_PAGE = Path(__file__).resolve().parent / "demo_page.html"
DEMO_EVENTS_PATH = "/api/v1/demo/events"
DEMO_SANDBOX_PATH = "/api/v1/demo/sandbox"
LOOPBACK = frozenset({"127.0.0.1", "::1", "::ffff:127.0.0.1"})
TRANSMISSIONS_PATH = "/api/v1/transmissions"
LATEST_CONTEXT_PATH = "/api/v1/context/latest"
ASSIST_PATH = "/api/v1/assist/check-message"
PLAN_PATH = "/api/v1/assist/plan"
DEFAULT_PORT = 8080
DEFAULT_PUBLIC_RATE = 30


class RateLimiter:
    """Sliding one-minute window shared by all callers: behind a tunnel every client looks the same."""

    def __init__(self, per_minute: int) -> None:
        self.per_minute = per_minute
        self._times: list[float] = []
        self._lock = threading.Lock()

    def allow(self) -> bool:
        now = time.monotonic()
        with self._lock:
            self._times = [t for t in self._times if now - t < 60]
            if len(self._times) >= self.per_minute:
                return False
            self._times.append(now)
            return True


ITEM_PATTERN = re.compile(r"^/api/v1/transmissions/([^/]+)(?:/(records|download))?$")


class ReceiverHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        storage: ReceiverStorage,
        assistant: MessageAssistant,
        replay_guard: RequestReplayGuard,
        api_token: str | None = None,
        assist_only: bool = False,
        rate_limiter: RateLimiter | None = None,
        demo_feed: DemoFeed | None = None,
    ) -> None:
        if assist_only and not api_token:
            raise ValueError("Assist-only (public) mode requires an API token.")
        if assist_only and demo_feed is not None:
            raise ValueError("The demo feed is never served in public mode.")
        super().__init__(address, ReceiverRequestHandler)
        self.storage = storage
        self.assistant = assistant
        self.replay_guard = replay_guard
        self.api_token = api_token
        # Public mode: only /health and the assist endpoints exist; collected data is never served.
        self.assist_only = assist_only
        self.rate_limiter = rate_limiter
        self.demo_feed = demo_feed
        assistant.feed = demo_feed


class ReceiverRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PrivAgentReceiver/1"
    timeout = 60  # Per socket operation; a stalled client cannot hold a thread forever.
    sys_version = ""

    @property
    def storage(self) -> ReceiverStorage:
        return self.server.storage  # type: ignore[attr-defined,no-any-return]

    def log_message(self, format: str, *args: object) -> None:
        # Never log bodies or metadata values. The standard request line is enough.
        super().log_message("%s", format % args)

    def _common_headers(self) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")

    def _send_bytes(
        self,
        status: int,
        body: bytes,
        content_type: str,
        *,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.send_response(status)
        self._common_headers()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def _send_json(self, status: int, value: object, *, headers: dict[str, str] | None = None) -> None:
        body = (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        self._send_bytes(status, body, "application/json; charset=utf-8", headers=headers)

    def _send_error(self, error: ReceiverError) -> None:
        self._send_json(error.status, error.as_dict())

    def do_GET(self) -> None:  # noqa: N802 - inherited API name
        try:
            self._handle_get()
        except ReceiverError as error:
            self._send_error(error)
        except Exception:
            self._send_error(ReceiverError("INTERNAL_ERROR", "Unexpected receiver error.", status=500))

    def do_POST(self) -> None:  # noqa: N802 - inherited API name
        try:
            self._handle_post()
        except ReceiverError as error:
            self.close_connection = True
            self._send_error(error)
        except Exception:
            self.close_connection = True
            self._send_error(ReceiverError("INTERNAL_ERROR", "Unexpected receiver error.", status=500))

    def _handle_get(self) -> None:
        target = urlsplit(self.path)
        if self.server.assist_only:  # type: ignore[attr-defined]
            if target.path != "/health":
                raise ReceiverError("NOT_FOUND", "Route was not found.", status=404)
            self._send_json(HTTPStatus.OK, {"status": "ok", "apiVersion": API_VERSION})
            return
        if target.path == "/health":
            self._send_json(
                HTTPStatus.OK,
                {
                    "status": "ok",
                    "apiVersion": API_VERSION,
                    "transmissionCount": len(self.storage.list_metadata()),
                },
            )
            return
        if target.path == "/":
            self._send_dashboard()
            return
        if target.path in ("/demo", DEMO_EVENTS_PATH):
            feed = self._demo_feed()
            if target.path == "/demo":
                self._send_bytes(HTTPStatus.OK, DEMO_PAGE.read_bytes(), "text/html; charset=utf-8")
            else:
                query = parse_qs(target.query, keep_blank_values=True)
                after = self._query_integer(query, "after", default=0, minimum=0, maximum=2**53)
                self._send_json(HTTPStatus.OK, {"events": feed.since(after)})
            return
        if target.path == TRANSMISSIONS_PATH:
            items = self.storage.list_metadata()
            self._send_json(HTTPStatus.OK, {"apiVersion": API_VERSION, "count": len(items), "items": items})
            return
        if target.path == LATEST_CONTEXT_PATH:
            query = parse_qs(target.query, keep_blank_values=True)
            session_values = query.get("sessionId")
            if session_values is not None and len(session_values) != 1:
                raise ReceiverError("INVALID_QUERY", "Query parameter sessionId must appear once.")
            if session_values is None:
                session_id = None
            else:
                if not session_values[0].strip():
                    raise ReceiverError(
                        "INVALID_COLLECTION_SESSION_ID",
                        "Query parameter sessionId cannot be blank.",
                    )
                session_id = validate_collection_session_id(session_values[0], required=True)
            limit = self._query_integer(
                query,
                "limitPerSource",
                default=100,
                minimum=1,
                maximum=1000,
            )
            self._send_json(
                HTTPStatus.OK,
                self.storage.latest_context(
                    collection_session_id=session_id,
                    limit_per_source=limit,
                ),
            )
            return

        match = ITEM_PATTERN.fullmatch(target.path)
        if not match:
            raise ReceiverError("NOT_FOUND", "Route was not found.", status=404)
        transfer_id, action = match.groups()
        if action is None:
            self._send_json(HTTPStatus.OK, self.storage.get_metadata(transfer_id))
        elif action == "records":
            query = parse_qs(target.query, keep_blank_values=True)
            offset = self._query_integer(query, "offset", default=0, minimum=0, maximum=2_147_483_647)
            limit = self._query_integer(query, "limit", default=100, minimum=1, maximum=1000)
            records, total = self.storage.read_records(transfer_id, offset=offset, limit=limit)
            self._send_json(
                HTTPStatus.OK,
                {
                    "apiVersion": API_VERSION,
                    "transferId": transfer_id,
                    "offset": offset,
                    "limit": limit,
                    "returned": len(records),
                    "total": total,
                    "records": records,
                },
            )
        else:
            path = self.storage.download_path(transfer_id)
            self._send_file(path, transfer_id)

    @staticmethod
    def _query_integer(
        query: dict[str, list[str]],
        name: str,
        *,
        default: int,
        minimum: int,
        maximum: int,
    ) -> int:
        values = query.get(name)
        if not values:
            return default
        if len(values) != 1:
            raise ReceiverError("INVALID_QUERY", f"Query parameter {name} must appear once.")
        try:
            value = int(values[0])
        except ValueError as exc:
            raise ReceiverError("INVALID_QUERY", f"Query parameter {name} must be an integer.") from exc
        if not minimum <= value <= maximum:
            raise ReceiverError(
                "INVALID_QUERY",
                f"Query parameter {name} must be between {minimum} and {maximum}.",
            )
        return value

    def _demo_feed(self) -> DemoFeed:
        """The stage demo exists only with --demo-feed and only for the presenting laptop itself."""
        feed = self.server.demo_feed  # type: ignore[attr-defined]
        if feed is None or self.client_address[0] not in LOOPBACK:
            raise ReceiverError("NOT_FOUND", "Route was not found.", status=404)
        return feed

    def _handle_sandbox(self) -> None:
        self._demo_feed()
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise ReceiverError("LENGTH_REQUIRED", "Content-Length is required.", status=411) from None
        if not 0 < length <= MAX_PACKET_BYTES:
            raise ReceiverError("PACKET_TOO_LARGE", "Sandbox input exceeds 16 KiB.", status=413)
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            text = body["text"].strip()
            allow_excerpt = body.get("allow_excerpt") is True
        except (UnicodeDecodeError, ValueError, KeyError, TypeError, AttributeError):
            raise ReceiverError("INVALID_SANDBOX_INPUT", "Body must be {\"text\": string}.") from None
        if not text:
            raise ReceiverError("INVALID_SANDBOX_INPUT", "Text is empty.")
        steps = phone_steps(text, allow_excerpt)
        packet = steps["packet"]
        # The simulated phone hands only the packet to the same gates a real phone goes through.
        body = json.dumps(packet, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        parse_packet(body)
        self.server.replay_guard.accept(packet)  # type: ignore[attr-defined]
        wire = (f"POST {ASSIST_PATH} HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: {len(body)}"
                "\r\nX-Simulated-Phone: demo sandbox\r\n\r\n" + body.decode("utf-8"))
        steps["result"] = self.server.assistant.assess(packet, origin="sandbox", wire=wire)  # type: ignore[attr-defined]
        self._send_json(HTTPStatus.OK, steps)

    def _handle_post(self) -> None:
        target = urlsplit(self.path)
        if target.path == DEMO_SANDBOX_PATH:
            self._handle_sandbox()
            return
        if target.path in (ASSIST_PATH, PLAN_PATH):
            feed = self.server.demo_feed  # type: ignore[attr-defined]
            try:
                self._handle_assist(target.path)
            except ReceiverError as error:
                # Show only the gate that stopped it; a rejected packet may hold values that must not be displayed.
                if feed is not None:
                    feed.add({"kind": "rejected", "origin": "phone",
                              "intent": "CHECK_MESSAGE" if target.path == ASSIST_PATH else "PLAN",
                              "code": error.code, "status": error.status, "message": error.message})
                raise
            return
        if target.path != TRANSMISSIONS_PATH or self.server.assist_only:  # type: ignore[attr-defined]
            raise ReceiverError("NOT_FOUND", "Route was not found.", status=404)
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type not in {"application/x-ndjson", "application/ndjson"}:
            raise ReceiverError(
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/x-ndjson.",
                status=415,
            )

        transfer_id = self.headers.get("X-Transfer-Id")
        count_header = self.headers.get("X-Record-Count")
        hash_header = self.headers.get("X-Payload-Sha256")
        expected_count = validate_record_count(count_header) if count_header is not None else None
        expected_hash = validate_sha256(hash_header) if hash_header is not None else None
        mode = validate_transmission_mode(self.headers.get("X-Transmission-Mode"))
        client_version = validate_client_version(self.headers.get("X-Client-Version"))
        client_sent_at = validate_client_sent_at(self.headers.get("X-Client-Sent-At"))
        collection_session_id = validate_collection_session_id(
            self.headers.get("X-Collection-Session-Id"),
            required=mode != "manual",
        )
        metadata, duplicate = self.storage.receive(
            transfer_id,
            self._request_body_chunks(),
            expected_record_count=expected_count,
            expected_sha256=expected_hash,
            transmission_mode=mode,
            client_version=client_version,
            client_sent_at=client_sent_at,
            collection_session_id=collection_session_id,
        )
        response = dict(metadata)
        response["duplicate"] = duplicate
        status = HTTPStatus.OK if duplicate else HTTPStatus.CREATED
        self._send_json(
            status,
            response,
            headers={"Location": f"{TRANSMISSIONS_PATH}/{metadata['transferId']}"},
        )

    def _handle_assist(self, path: str) -> None:
        token = self.server.api_token  # type: ignore[attr-defined]
        if token is not None and not hmac.compare_digest(
            self.headers.get("X-Api-Token", "").encode("utf-8"), token.encode("utf-8")
        ):
            raise ReceiverError("UNAUTHORIZED", "X-Api-Token is missing or wrong.", status=401)
        limiter = self.server.rate_limiter  # type: ignore[attr-defined]
        if limiter is not None and not limiter.allow():
            raise ReceiverError("RATE_LIMITED", "Too many assist requests; try again in a minute.", status=429)
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise ReceiverError("UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.", status=415)
        if self.headers.get("Transfer-Encoding"):
            raise ReceiverError("LENGTH_REQUIRED", "Planner packets must use Content-Length.", status=411)
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise ReceiverError("LENGTH_REQUIRED", "Content-Length is required.", status=411) from None
        if length < 0 or length > MAX_PACKET_BYTES:
            raise ReceiverError("PACKET_TOO_LARGE", "Planner packet exceeds 16 KiB.", status=413)
        body = self.rfile.read(length)
        if len(body) != length:
            raise ReceiverError("INCOMPLETE_BODY", "Connection ended before Content-Length bytes arrived.")
        assistant = self.server.assistant  # type: ignore[attr-defined]
        # The stage demo shows the request exactly as received, once it has passed every gate below.
        wire = self._wire(body) if self.server.demo_feed is not None else None  # type: ignore[attr-defined]
        if path == ASSIST_PATH:
            packet = parse_packet(body)
            self.server.replay_guard.accept(packet)  # type: ignore[attr-defined]
            self._send_json(HTTPStatus.OK, assistant.assess(packet, wire=wire))
        else:
            self._send_json(HTTPStatus.OK, assistant.plan(parse_plan_packet(body), wire=wire))

    def _wire(self, body: bytes) -> str:
        """Request line, headers and body as received; the access token is masked for the projector."""
        lines = [self.requestline]
        for name, value in self.headers.items():
            lines.append(f"{name}: {'••••••••' if name.lower() == 'x-api-token' else value}")
        return "\r\n".join(lines) + "\r\n\r\n" + body.decode("utf-8", "replace")

    def _request_body_chunks(self) -> Iterator[bytes]:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").strip().lower()
        if transfer_encoding:
            if transfer_encoding != "chunked":
                raise ReceiverError("UNSUPPORTED_TRANSFER_ENCODING", "Only chunked transfer encoding is supported.")
            yield from self._chunked_body()
            return

        length_header = self.headers.get("Content-Length")
        if length_header is None:
            raise ReceiverError("LENGTH_REQUIRED", "Content-Length or chunked transfer encoding is required.", status=411)
        try:
            remaining = int(length_header)
        except ValueError as exc:
            raise ReceiverError("INVALID_CONTENT_LENGTH", "Content-Length must be a non-negative integer.") from exc
        if remaining < 0:
            raise ReceiverError("INVALID_CONTENT_LENGTH", "Content-Length must be a non-negative integer.")
        if remaining > self.storage.max_body_bytes:
            raise ReceiverError(
                "BODY_TOO_LARGE",
                f"Transmission exceeds the {self.storage.max_body_bytes}-byte limit.",
                status=413,
                details={"maxBytes": self.storage.max_body_bytes},
            )
        while remaining:
            chunk = self.rfile.read(min(64 * 1024, remaining))
            if not chunk:
                raise ReceiverError("INCOMPLETE_BODY", "Connection ended before Content-Length bytes arrived.")
            remaining -= len(chunk)
            yield chunk

    def _chunked_body(self) -> Iterator[bytes]:
        while True:
            size_line = self.rfile.readline(256)
            if not size_line or len(size_line) >= 256 or not size_line.endswith(b"\r\n"):
                raise ReceiverError("INVALID_CHUNKED_BODY", "Invalid chunk-size line.")
            token = size_line[:-2].split(b";", 1)[0].strip()
            try:
                size = int(token, 16)
            except ValueError as exc:
                raise ReceiverError("INVALID_CHUNKED_BODY", "Invalid hexadecimal chunk size.") from exc
            if size < 0:
                raise ReceiverError("INVALID_CHUNKED_BODY", "Chunk size cannot be negative.")
            if size == 0:
                while True:
                    trailer = self.rfile.readline(8192)
                    if not trailer or trailer == b"\r\n":
                        return
                    if len(trailer) >= 8192:
                        raise ReceiverError("INVALID_CHUNKED_BODY", "Chunk trailer is too long.")
            remaining = size
            while remaining:
                chunk = self.rfile.read(min(64 * 1024, remaining))
                if not chunk:
                    raise ReceiverError("INCOMPLETE_BODY", "Connection ended inside a chunk.")
                remaining -= len(chunk)
                yield chunk
            if self.rfile.read(2) != b"\r\n":
                raise ReceiverError("INVALID_CHUNKED_BODY", "Chunk is missing its terminating CRLF.")

    def _send_file(self, path: Path, transfer_id: str) -> None:
        self.send_response(HTTPStatus.OK)
        self._common_headers()
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("Content-Disposition", f'attachment; filename="{transfer_id}.ndjson"')
        self.end_headers()
        try:
            with path.open("rb") as source:
                while chunk := source.read(64 * 1024):
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def _send_dashboard(self) -> None:
        rows = []
        for item in self.storage.list_metadata():
            identifier = html.escape(str(item.get("transferId", "")))
            received = html.escape(str(item.get("receivedAt", "")))
            count = html.escape(str(item.get("recordCount", "")))
            byte_count = html.escape(str(item.get("bytes", "")))
            mode = html.escape(str(item.get("transmissionMode", "manual")))
            session_id = html.escape(str(item.get("collectionSessionId") or "-"))
            rows.append(
                "<tr>"
                f"<td><code>{identifier}</code></td><td>{mode}</td><td><code>{session_id}</code></td>"
                f"<td>{received}</td><td>{count}</td><td>{byte_count}</td>"
                f'<td><a href="{TRANSMISSIONS_PATH}/{identifier}">metadata</a> · '
                f'<a href="{TRANSMISSIONS_PATH}/{identifier}/records">records</a> · '
                f'<a href="{TRANSMISSIONS_PATH}/{identifier}/download">download</a></td></tr>'
            )
        table_rows = "".join(rows) or '<tr><td colspan="7">尚未收到任何傳輸。</td></tr>'
        page = f"""<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="5">
<title>AI 金孫 Receiver</title><style>
body{{font-family:system-ui,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#172033}}
h1{{margin-bottom:.25rem}} .muted{{color:#5d687b}} table{{width:100%;border-collapse:collapse;margin-top:1.5rem}}
th,td{{padding:.7rem;border-bottom:1px solid #d8dee9;text-align:left;vertical-align:top}} th{{background:#edf2f7}}
code{{word-break:break-all}} a{{color:#0759a5}}
</style></head><body><h1>AI 金孫資料接收端</h1>
<p class="muted">API v{API_VERSION} · 即時傳遞 MVP · 每 5 秒更新 · <a href="/health">health</a> · <a href="{TRANSMISSIONS_PATH}">JSON list</a> · <a href="{LATEST_CONTEXT_PATH}">latest context</a></p>
<p>POST NDJSON 至 <code>{TRANSMISSIONS_PATH}</code>，可用 <code>X-Transfer-Id</code> 安全重試。</p>
<table><thead><tr><th>Transfer ID</th><th>模式</th><th>Session</th><th>接收時間（UTC）</th><th>筆數</th><th>Bytes</th><th>操作</th></tr></thead>
<tbody>{table_rows}</tbody></table></body></html>"""
        self._send_bytes(
            HTTPStatus.OK,
            page.encode("utf-8"),
            "text/html; charset=utf-8",
            headers={"Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'"},
        )


def create_server(
    host: str = "127.0.0.1",
    port: int = DEFAULT_PORT,
    *,
    data_dir: str | Path | None = None,
    max_body_bytes: int = DEFAULT_MAX_BODY_BYTES,
    max_line_bytes: int = DEFAULT_MAX_LINE_BYTES,
    planner: Callable[[str], str] | None = None,
    api_token: str | None = None,
    assist_only: bool = False,
    rate_per_minute: int = 0,
    demo_feed: bool = False,
) -> ReceiverHTTPServer:
    root = Path(data_dir) if data_dir is not None else Path(__file__).resolve().parent / "data"
    storage = ReceiverStorage(root, max_body_bytes=max_body_bytes, max_line_bytes=max_line_bytes)
    assistant = MessageAssistant(storage.data_dir / "assist_audit.ndjson", planner)
    return ReceiverHTTPServer((host, port), storage, assistant, RequestReplayGuard(), api_token or None,
                              assist_only, RateLimiter(rate_per_minute) if rate_per_minute > 0 else None,
                              DemoFeed() if demo_feed else None)


def main() -> None:
    parser = argparse.ArgumentParser(description="Receive PrivAgent Collector NDJSON transmissions.")
    parser.add_argument("--host", default="127.0.0.1", help="Bind address (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"TCP port (default: {DEFAULT_PORT})")
    parser.add_argument("--data-dir", type=Path, default=Path(__file__).resolve().parent / "data")
    parser.add_argument("--max-body-bytes", type=int, default=DEFAULT_MAX_BODY_BYTES)
    parser.add_argument("--max-line-bytes", type=int, default=DEFAULT_MAX_LINE_BYTES)
    parser.add_argument(
        "--planner-url",
        default=DEFAULT_PLANNER_URL,
        help=f"Gemini proxy base URL, or 'off' to use local rules only (default: {DEFAULT_PLANNER_URL})",
    )
    parser.add_argument("--planner-timeout", type=float, default=DEFAULT_PLANNER_TIMEOUT)
    parser.add_argument(
        "--assist-only",
        action="store_true",
        help="Public mode for a tunnel: serve only /health and /api/v1/assist/*; PRIVAGENT_API_TOKEN is required",
    )
    parser.add_argument(
        "--rate-limit",
        type=int,
        default=None,
        help=f"Max assist requests per minute, all callers together (default: {DEFAULT_PUBLIC_RATE} in assist-only mode, off otherwise)",
    )
    parser.add_argument(
        "--demo-feed",
        action="store_true",
        help="Stage demo page at /demo (in memory, served only to this machine; not allowed with --assist-only)",
    )
    args = parser.parse_args()
    token = os.environ.get("PRIVAGENT_API_TOKEN")
    if args.assist_only and not token:
        parser.error("--assist-only requires PRIVAGENT_API_TOKEN")
    if args.assist_only and args.demo_feed:
        parser.error("--demo-feed cannot be used with --assist-only")
    rate = args.rate_limit if args.rate_limit is not None else (DEFAULT_PUBLIC_RATE if args.assist_only else 0)
    planner = None if args.planner_url == "off" else gemini_proxy_planner(args.planner_url, args.planner_timeout)
    server = create_server(
        args.host,
        args.port,
        data_dir=args.data_dir,
        max_body_bytes=args.max_body_bytes,
        max_line_bytes=args.max_line_bytes,
        planner=planner,
        api_token=token,
        assist_only=args.assist_only,
        rate_per_minute=rate,
        demo_feed=args.demo_feed,
    )
    address, port = server.server_address[:2]
    print(f"PrivAgent Receiver API v{API_VERSION} listening on http://{address}:{port}", flush=True)
    print(f"Data directory: {server.storage.data_dir}", flush=True)
    print(f"Planner: {'local rules only' if planner is None else args.planner_url}", flush=True)
    print(f"Assist API token: {'required' if server.api_token else 'not set (development only)'}", flush=True)
    if args.assist_only:
        print(f"Public mode: only /health and /api/v1/assist/* are served, at most {rate} requests/minute", flush=True)
    if args.demo_feed:
        print(f"Stage demo: http://127.0.0.1:{port}/demo (this machine only, kept in memory)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping receiver...", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
