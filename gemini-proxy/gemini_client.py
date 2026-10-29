"""Preserve raw account status before the upstream enum fallback loses it."""

from gemini_webapi import GeminiClient
from gemini_webapi.constants import GRPC
from gemini_webapi.utils import get_nested_value


class StatusAwareClient(GeminiClient):
    raw_account_status = None

    def _parse_rpc_results(self, response_text, target_id):
        for body in super()._parse_rpc_results(response_text, target_id):
            if target_id == GRPC.GET_USER_STATUS:
                code = get_nested_value(body, [14])
                self.raw_account_status = code if type(code) is int else None
            yield body
