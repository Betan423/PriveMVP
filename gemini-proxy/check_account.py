"""Check configured cookies without loading the existing cookie cache."""

import asyncio
import os
from tempfile import TemporaryDirectory

from api import create_client


async def check_account():
    client = create_client()
    try:
        await client.init(timeout=30, auto_close=False, auto_refresh=False)
        print("Raw account status code:", getattr(client, "raw_account_status", "not captured by this client"))
        print(f"Account status (fresh cache): {client.account_status.name}")
        print("這只檢查帳號狀態，尚未驗證訊息生成。")
    finally:
        await client.close()


def main():
    # The upstream library only exposes its cache location via this setting.
    # Set it internally; the user supplies credentials only through config.json.
    previous = os.environ.get("GEMINI_COOKIE_PATH")
    with TemporaryDirectory(prefix="gemini-account-check-") as cache_dir:
        os.environ["GEMINI_COOKIE_PATH"] = cache_dir
        try:
            asyncio.run(check_account())
        finally:
            if previous is None:
                os.environ.pop("GEMINI_COOKIE_PATH", None)
            else:
                os.environ["GEMINI_COOKIE_PATH"] = previous


if __name__ == "__main__":
    main()
