"""Offline tests for official_api.py: no API key or network needed (upstream is faked)."""

import http.client
import io
import json
import threading
import unittest
import urllib.error
from http.server import ThreadingHTTPServer
from unittest import mock

import official_api


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class GenerateTests(unittest.TestCase):
    def test_request_shape_and_answer(self) -> None:
        seen = {}

        def fake_urlopen(request, timeout):
            seen["url"], seen["headers"], seen["body"] = request.full_url, dict(request.headers), json.loads(request.data)
            body = {"candidates": [{"content": {"parts": [{"text": '{"sop_id":'}, {"text": '"ASK_USER"}'}]}}],
                    "modelVersion": "gemini-x"}
            return FakeResponse(json.dumps(body).encode())

        with mock.patch.object(official_api.urllib.request, "urlopen", fake_urlopen):
            answer, version = official_api.generate("KEY", "m1", "hi")
        self.assertEqual((answer, version), ('{"sop_id":"ASK_USER"}', "gemini-x"))
        self.assertTrue(seen["url"].endswith("/m1:generateContent"))
        # The key travels only in the header, never in the URL.
        self.assertNotIn("KEY", seen["url"])
        self.assertEqual(seen["headers"]["X-goog-api-key"], "KEY")
        self.assertEqual(seen["body"]["generationConfig"]["temperature"], 0)


class ClaudeAndOllamaTests(unittest.TestCase):
    def capture(self, reply: dict):
        seen = {}

        def fake_urlopen(request, timeout):
            seen["url"], seen["headers"], seen["body"] = request.full_url, dict(request.headers), json.loads(request.data)
            return FakeResponse(json.dumps(reply).encode())

        return seen, mock.patch.object(official_api.urllib.request, "urlopen", fake_urlopen)

    def test_claude_request_and_text_blocks(self) -> None:
        reply = {"model": "claude-opus-5-5", "stop_reason": "end_turn",
                 "content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": '{"a":1}'}]}
        seen, patch = self.capture(reply)
        with patch:
            answer, version = official_api.generate_claude("KEY", "claude-opus-5-5", "hi")
        self.assertEqual((answer, version), ('{"a":1}', "claude-opus-5-5"))
        self.assertEqual(seen["url"], official_api.CLAUDE_URL)
        self.assertEqual(seen["headers"]["X-api-key"], "KEY")
        self.assertEqual(seen["headers"]["Anthropic-version"], official_api.CLAUDE_API_VERSION)
        self.assertEqual(seen["body"]["fallbacks"], "default")
        self.assertEqual(seen["body"]["messages"], [{"role": "user", "content": "hi"}])
        self.assertNotIn("KEY", json.dumps(seen["body"]))

    def test_claude_older_model_gets_no_fallbacks(self) -> None:
        seen, patch = self.capture({"content": [{"type": "text", "text": "{}"}]})
        with patch:
            official_api.generate_claude("KEY", "claude-haiku-5-5", "hi")
        self.assertNotIn("fallbacks", seen["body"])
        self.assertNotIn("Anthropic-beta", seen["headers"])

    def test_claude_refusal_raises(self) -> None:
        _seen, patch = self.capture({"stop_reason": "refusal", "content": []})
        with patch, self.assertRaises(official_api.UpstreamRefused):
            official_api.generate_claude("KEY", "claude-opus-5-5", "hi")

    def test_ollama_is_local_json_chat(self) -> None:
        seen, patch = self.capture({"model": "qwen3.5:9b", "message": {"content": '{"ok":true}'}})
        with patch:
            answer, _ = official_api.generate_ollama("http://127.0.0.1:11434/", "qwen3.5:9b", "hi")
        self.assertEqual(answer, '{"ok":true}')
        self.assertEqual(seen["url"], "http://127.0.0.1:11434/api/chat")
        self.assertEqual((seen["body"]["format"], seen["body"]["stream"]), ("json", False))


class GeneratorTests(unittest.TestCase):
    def test_make_generator_binds_backend_model_and_secret(self) -> None:
        with mock.patch.object(official_api, "generate_ollama", return_value=("{}", "m")) as call:
            self.assertEqual(official_api.make_generator("ollama", "qwen", "http://h:1")("x"), ("{}", "m"))
        call.assert_called_once_with("http://h:1", "qwen", "x")


class ServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), official_api.Handler)
        self.server.backend, self.server.model, self.server.lock = "gemini", "m1", threading.Lock()
        self.server.generate = mock.Mock(side_effect=AssertionError("tests must not call the network"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def request(self, method: str, path: str, body: bytes | None = None) -> tuple[int, dict]:
        connection = http.client.HTTPConnection(*self.server.server_address[:2], timeout=3)
        connection.request(method, path, body=body, headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_health_has_no_key(self) -> None:
        status, body = self.request("GET", "/health")
        self.assertEqual((status, body["backend"]), (200, "gemini"))
        self.assertNotIn("KEY", json.dumps(body))

    def test_generate(self) -> None:
        self.server.generate = mock.Mock(return_value=("ok", "v"))
        status, body = self.request("POST", "/generate", json.dumps({"message": " hi "}).encode())
        self.assertEqual((status, body), (200, {"answer": "ok"}))
        self.server.generate.assert_called_once_with("hi")

    def test_bad_requests(self) -> None:
        self.assertEqual(self.request("POST", "/generate", b'{"msg": 1}')[0], 422)
        self.assertEqual(self.request("POST", "/generate", json.dumps({"message": "  "}).encode())[0], 422)
        self.assertEqual(self.request("POST", "/other", b"{}")[0], 404)
        self.assertEqual(self.request("GET", "/docs")[0], 404)

    def test_upstream_failures_are_502(self) -> None:
        errors = (urllib.error.HTTPError("u", 429, "quota", {}, io.BytesIO()), TimeoutError(), KeyError("candidates"),
                  official_api.UpstreamRefused())
        for error in errors:
            self.server.generate = mock.Mock(side_effect=error)
            status, body = self.request("POST", "/generate", json.dumps({"message": "hi"}).encode())
            self.assertEqual(status, 502, error)
            self.assertNotIn("KEY", json.dumps(body))


if __name__ == "__main__":
    unittest.main()
