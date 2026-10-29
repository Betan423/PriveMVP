import json
from pathlib import Path

import google.auth
import requests
from google.auth.transport.requests import Request


# =========================
# Gemini Enterprise 設定
# =========================

PROJECT_ID = "你的 Google Cloud Project ID"
LOCATION = "global"
DATA_STORE_ID = "你的 Data Store ID"

# Gemini Enterprise 官方 API 版本
API_VERSION = "v1"

# 保存目前 conversation name 的檔案
CONVERSATION_FILE = Path("enterprise_conversation.json")


# =========================
# Gemini Enterprise Client
# =========================

class GeminiEnterpriseClient:
    def __init__(
        self,
        project_id: str,
        location: str,
        data_store_id: str,
        api_version: str = "v1",
    ):
        self.project_id = project_id
        self.location = location
        self.data_store_id = data_store_id
        self.api_version = api_version

        self.credentials = None
        self.conversation_name = None

        self.load_conversation()

    def authenticate(self):
        """取得 Google Cloud OAuth access token"""
        scopes = [
            "https://www.googleapis.com/auth/cloud-platform",
        ]

        self.credentials, _ = google.auth.default(
            scopes=scopes,
        )

        self.credentials.refresh(Request())

    @property
    def parent_resource(self) -> str:
        return (
            f"projects/{self.project_id}"
            f"/locations/{self.location}"
            f"/dataStores/{self.data_store_id}"
        )

    @property
    def conversation_endpoint(self) -> str:
        conversation_name = (
            self.conversation_name
            if self.conversation_name
            else f"{self.parent_resource}/conversations/-"
        )

        return (
            "https://discoveryengine.googleapis.com/"
            f"{self.api_version}/{conversation_name}:converse"
        )

    def load_conversation(self):
        """載入上次保存的 Enterprise conversation"""
        if not CONVERSATION_FILE.exists():
            return

        try:
            data = json.loads(
                CONVERSATION_FILE.read_text(encoding="utf-8")
            )

            self.conversation_name = data.get("conversation_name")

            if self.conversation_name:
                print("已載入 Enterprise 對話：")
                print(self.conversation_name)

        except Exception as error:
            print("載入對話狀態失敗：", error)

    def save_conversation(self):
        """保存 Enterprise conversation name"""
        if not self.conversation_name:
            return

        CONVERSATION_FILE.write_text(
            json.dumps(
                {
                    "conversation_name": self.conversation_name,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    def new_conversation(self):
        """建立新的 Enterprise conversation"""
        self.conversation_name = None

        if CONVERSATION_FILE.exists():
            CONVERSATION_FILE.unlink()

    def send_message(self, message: str) -> str:
        """送出訊息到 Gemini Enterprise"""
        if not self.credentials:
            self.authenticate()

        headers = {
            "Authorization": f"Bearer {self.credentials.token}",
            "Content-Type": "application/json",
        }

        body = {
            "query": {
                "input": message,
            },
            "safeSearch": True,
        }

        response = requests.post(
            self.conversation_endpoint,
            headers=headers,
            json=body,
            timeout=120,
        )

        if response.status_code >= 400:
            raise RuntimeError(
                f"Gemini Enterprise API 錯誤 "
                f"({response.status_code}):\n"
                f"{response.text}"
            )

        data = response.json()

        # API 回傳更新後的 conversation
        conversation = data.get("conversation", {})
        new_conversation_name = conversation.get("name")

        if new_conversation_name:
            self.conversation_name = new_conversation_name
            self.save_conversation()

        # 讀取 Enterprise 回覆
        reply = data.get("reply", {})
        summary = reply.get("summary", {})

        answer = summary.get("summaryText")

        if not answer:
            summary_with_metadata = summary.get(
                "summaryWithMetadata",
                {},
            )

            answer = summary_with_metadata.get("summary")

        if not answer:
            return json.dumps(
                data,
                ensure_ascii=False,
                indent=2,
            )

        return answer


# =========================
# Interactive Proxy
# =========================

def main():
    if PROJECT_ID == "你的 Google Cloud Project ID":
        print("請先修改 PROJECT_ID。")
        return

    if DATA_STORE_ID == "你的 Data Store ID":
        print("請先修改 DATA_STORE_ID。")
        return

    client = GeminiEnterpriseClient(
        project_id=PROJECT_ID,
        location=LOCATION,
        data_store_id=DATA_STORE_ID,
        api_version=API_VERSION,
    )

    print()
    print("Gemini Enterprise Proxy 已啟動")
    print()
    print("指令：")
    print("  /new  建立新的 Enterprise 對話")
    print("  /exit 離開")
    print()

    while True:
        try:
            message = input("You > ").strip()

        except (KeyboardInterrupt, EOFError):
            print("\n離開。")
            break

        if not message:
            continue

        if message == "/exit":
            print("離開。")
            break

        if message == "/new":
            client.new_conversation()
            print("已建立新的 Enterprise 對話。")
            continue

        try:
            answer = client.send_message(message)

            print()
            print("Gemini Enterprise >")
            print(answer)
            print()

            if client.conversation_name:
                print("Conversation:")
                print(client.conversation_name)
                print()

        except Exception as error:
            print()
            print("錯誤：")
            print(error)
            print()


if __name__ == "__main__":
    main()
