import asyncio
import unittest
from pathlib import Path
from types import SimpleNamespace
from tempfile import TemporaryDirectory
from unittest.mock import AsyncMock, Mock, patch

from fastapi import HTTPException
from gemini_webapi.constants import AccountStatus
from gemini_webapi.exceptions import TimeoutError as GeminiTimeoutError

from api import (ChatRequest, GenerateRequest, app, create_client, generate,
                 health, load_cookie_jar, load_cookies, ready, send_message)
from api import initialize_client


class StartupTests(unittest.IsolatedAsyncioTestCase):
    async def test_timeout_retries_with_fresh_client(self):
        first = SimpleNamespace(init=AsyncMock(side_effect=GeminiTimeoutError("timeout")), close=AsyncMock())
        second = SimpleNamespace(init=AsyncMock(), close=AsyncMock())
        with patch("api.create_client", side_effect=[first, second]):
            with patch("api.asyncio.sleep", new_callable=AsyncMock):
                self.assertIs(await initialize_client(), second)
        first.close.assert_awaited_once()
        second.close.assert_not_awaited()

    async def test_retries_are_bounded(self):
        clients = [SimpleNamespace(init=AsyncMock(side_effect=GeminiTimeoutError("timeout")), close=AsyncMock()) for _ in range(3)]
        with patch("api.create_client", side_effect=clients) as factory:
            with patch("api.asyncio.sleep", new_callable=AsyncMock):
                with self.assertRaisesRegex(RuntimeError, "連續三次"):
                    await initialize_client()
                self.assertEqual(factory.call_count, 3)
        for client in clients:
            client.close.assert_awaited_once()

    async def test_other_errors_are_not_retried(self):
        client = SimpleNamespace(init=AsyncMock(side_effect=ValueError("invalid")), close=AsyncMock())
        with patch("api.create_client", return_value=client) as factory:
            with self.assertRaises(ValueError):
                await initialize_client()
            factory.assert_called_once()
        client.close.assert_awaited_once()


class ConfigTests(unittest.TestCase):
    @unittest.skipUnless(Path(__file__).with_name("test.py").exists(),
                         "test.py holds local session cookies and is not part of the repo")
    def test_working_client_factory_uses_original_single_argument(self):
        with patch.object(Path, "read_text", return_value='{"CLIENT_SOURCE":"test.py"}'):
            with patch("gemini_webapi.GeminiClient") as constructor:
                self.assertIs(create_client(), constructor.return_value)
                self.assertEqual(len(constructor.call_args.args), 1)
                self.assertTrue("sidts-" in constructor.call_args.args[0])

    def test_full_header_keeps_additional_cookies_and_equals(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.with_name("cookies.txt").write_text(
                "Cookie: __Secure-1PSID=test; __Secure-1PSIDTS=test-ts; SID=extra=value\n"
            )
            cookies = load_cookie_jar(path)
            self.assertEqual(cookies["SID"], "extra=value")
            self.assertEqual(cookies["__Secure-1PSID"], "test")

    def test_invalid_header_does_not_leak_cookie(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            for header in ("SID=private-value", "invalid-private-value", ""):
                path.with_name("cookies.txt").write_text(header)
                with self.assertRaises(RuntimeError) as error:
                    load_cookie_jar(path)
                self.assertNotIn("private-value", str(error.exception))

    def test_load_config(self):
        with patch.object(Path, "read_text", return_value=
                          '{"SECURE_1PSID":"test-id", "SECURE_1PSIDTS":"test-ts"}'):
            self.assertEqual(load_cookies(), ("test-id", "test-ts"))

    def test_reject_invalid_config_without_exposing_contents(self):
        for content in ('{"SECURE_1PSID":"private-value"}', '[]', 'private-value'):
            with self.subTest(content=content):
                with patch.object(Path, "read_text", return_value=content):
                    with self.assertRaises(RuntimeError) as error:
                        load_cookies()
                    self.assertNotIn("private-value", str(error.exception))


class RejectedAccountTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app.state.client = SimpleNamespace(
            account_status=AccountStatus.ACCOUNT_REJECTED, start_chat=Mock()
        )
        app.state.chats = {}
        app.state.lock = asyncio.Lock()

    async def test_generate_matches_working_script_even_when_status_is_rejected(self):
        app.state.client.generate_content = AsyncMock(
            return_value=SimpleNamespace(text="回答")
        )
        self.assertEqual(await generate(GenerateRequest(message="Hello")), {"answer": "回答"})
        app.state.client.generate_content.assert_awaited_once_with("Hello")

    async def test_rejected_account_never_starts_upstream_chat(self):
        with self.assertRaises(HTTPException) as error:
            await send_message(ChatRequest(message="你好"))
        self.assertEqual(error.exception.status_code, 503)
        self.assertEqual(error.exception.detail["code"], "ACCOUNT_REJECTED")
        app.state.client.start_chat.assert_not_called()

    async def test_readiness_reports_rejection_while_http_is_alive(self):
        self.assertEqual(await health(), {"status": "ok"})
        with self.assertRaises(HTTPException) as error:
            await ready()
        self.assertEqual(error.exception.status_code, 503)
        app.state.client.account_status = AccountStatus.AVAILABLE
        self.assertEqual((await ready())["account_status"], "AVAILABLE")

    async def test_unknown_raw_status_is_not_reported_as_account_rejected(self):
        app.state.client.raw_account_status = 1062
        with self.assertRaises(HTTPException) as error:
            await send_message(ChatRequest(message="你好"))
        self.assertEqual(error.exception.detail["code"], "UNKNOWN_ACCOUNT_STATUS")
        self.assertEqual(error.exception.detail["upstream_status"], 1062)
        app.state.client.start_chat.assert_not_called()


if __name__ == "__main__":
    unittest.main()
