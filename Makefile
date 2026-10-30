# PrivAgent 常用指令。`make` 或 `make help` 列出全部目標。
# 可覆寫的變數：make receiver-lan TOKEN=abc123、make proxy PROXY_DIR=~/gemini-proxymeow

PYTHON      ?= python3
PROXY_DIR   ?= gemini-proxy
PROXY_PY    := $(PROXY_DIR)/.venv/bin/python
PROXY_VENV  := $(PROXY_DIR)/.venv/.installed
PROXY_PORT  ?= 8000
PORT        ?= 8080
DATA_DIR    ?= receiver/data
TOKEN       ?= $(PRIVAGENT_API_TOKEN)
MESSAGE     ?= 我是家人，臨時換號碼，請轉帳到 SYN_ACCOUNT_PRIVATE_0001_18
ENV         ?= 1
DATASET     := dataset/AI金孫_合成原始資料集_v5_廣度擴充/dataset/source_raw_v5.json
APK         := app/build/outputs/apk/debug/app-debug.apk
EVAL_OUT    ?= receiver/data/core_eval

# 有本機離線工具鏈（JDK 17 + SDK + Gradle 快取）就用它；否則沿用系統的 JAVA_HOME / ANDROID_HOME。
TOOLCHAIN   ?= $(HOME)/.cache/privagent-toolchain
ifneq ($(wildcard $(TOOLCHAIN)/jdk),)
GRADLE_ENV   := JAVA_HOME=$(TOOLCHAIN)/jdk ANDROID_HOME=$(TOOLCHAIN)/sdk GRADLE_USER_HOME=$(TOOLCHAIN)/gradle-home
GRADLE_FLAGS ?= --offline
ADB          ?= $(TOOLCHAIN)/sdk/platform-tools/adb
else
GRADLE_ENV   :=
GRADLE_FLAGS ?=
ADB          ?= adb
endif
GRADLE := $(GRADLE_ENV) bash ./gradlew $(GRADLE_FLAGS)

.DEFAULT_GOAL := help
.PHONY: help setup test test-receiver test-proxy test-android apk install \
        proxy receiver receiver-lan receiver-rules demo demo-dataset scan core-eval golden check-services ip

help: ## 列出所有指令
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---- 安裝 ----

setup: $(PROXY_VENV) ## 建立 Gemini proxy 的 venv 並安裝套件
	@test -f $(PROXY_DIR)/config.json -o -f $(PROXY_DIR)/cookies.txt || \
		echo "提醒：還沒有 $(PROXY_DIR)/config.json 或 cookies.txt，請依 $(PROXY_DIR)/README.md 填入自己的 Gemini Cookie"

$(PROXY_VENV): $(PROXY_DIR)/requirements.txt
	$(PYTHON) -m venv $(PROXY_DIR)/.venv
	$(PROXY_PY) -m pip install -q -r $(PROXY_DIR)/requirements.txt
	@touch $@

# ---- 測試 ----

test: test-receiver test-proxy test-android ## 跑全部自動化測試

test-receiver: ## Receiver 單元測試（只需 Python 標準函式庫）
	$(PYTHON) -m unittest discover -s receiver/tests

test-proxy: $(PROXY_VENV) ## Gemini proxy 單元測試（不需 Cookie）
	cd $(PROXY_DIR) && .venv/bin/python -m unittest test_api

test-android: ## Android 建置、lint 與單元測試
	$(GRADLE) assembleDebug lintDebug testDebugUnitTest

apk: ## 只建置 debug APK
	$(GRADLE) assembleDebug
	@echo "APK：$(APK)"

install: apk ## 用 adb 安裝 APK 到已連線的手機
	$(ADB) install -r $(APK)

# ---- 啟動服務（各開一個終端機） ----

proxy: $(PROXY_VENV) ## 啟動 Gemini proxy（127.0.0.1:8000）
	@test -f $(PROXY_DIR)/config.json -o -f $(PROXY_DIR)/cookies.txt || \
		{ echo "缺少 $(PROXY_DIR)/config.json 或 cookies.txt，請依 $(PROXY_DIR)/README.md 設定 Cookie"; exit 1; }
	cd $(PROXY_DIR) && .venv/bin/python -m uvicorn api:app --host 127.0.0.1 --port $(PROXY_PORT)

receiver: ## 啟動 Receiver，只接受本機連線（給 phone_sim 或 adb reverse）
	$(PYTHON) -m receiver.server --port $(PORT) --data-dir $(DATA_DIR) --planner-url http://127.0.0.1:$(PROXY_PORT)

receiver-lan: ## 對區網開放的 Receiver，必須給存取碼：make receiver-lan TOKEN=xxx
	@test -n "$(TOKEN)" || { echo "區網模式請設定存取碼：make receiver-lan TOKEN=xxx"; exit 1; }
	@echo "手機 App 填：http://$$(hostname -I | awk '{print $$1}'):$(PORT)，存取碼同 TOKEN"
	PRIVAGENT_API_TOKEN=$(TOKEN) $(PYTHON) -m receiver.server --host 0.0.0.0 --port $(PORT) \
		--data-dir $(DATA_DIR) --planner-url http://127.0.0.1:$(PROXY_PORT)

receiver-rules: ## 不接 AI、只用規則判斷的 Receiver
	$(PYTHON) -m receiver.server --port $(PORT) --data-dir $(DATA_DIR) --planner-url off

# ---- 用電腦模擬手機 ----

demo: ## 模擬手機送一則訊息：make demo MESSAGE="..."
	PRIVAGENT_API_TOKEN=$(TOKEN) $(PYTHON) -m receiver.phone_sim --receiver http://127.0.0.1:$(PORT) \
		--message "$(MESSAGE)" --show-packet

demo-dataset: ## 送資料集某個環境的 32 則訊息：make demo-dataset ENV=1
	PRIVAGENT_API_TOKEN=$(TOKEN) $(PYTHON) -m receiver.phone_sim --receiver http://127.0.0.1:$(PORT) \
		--dataset "$(DATASET)" --environment $(ENV)

scan: ## 檢查 6,144 則訊息遮蔽後是否仍含個資（不送出）
	$(PYTHON) -m receiver.phone_sim --dataset "$(DATASET)" --scan-only

# ---- 核心三情境 ----

core-eval: ## 1,728 筆核心任務跑政策引擎並輸出證據檔（隱私指標任一不為 0 會失敗）
	$(PYTHON) -m receiver.core_eval --dataset "$(DATASET)" --out $(EVAL_OUT)
	@echo "證據檔：$(EVAL_OUT)/（summary.json、derived_tasks.csv、packets.jsonl、failures.json、manifest.json）"

golden: ## 改了 receiver/core_tasks.py 後，重新產生 Android 的對照測試資料
	$(PYTHON) -m receiver.gen_core_golden --dataset "$(DATASET)"

# ---- 雜項 ----

check-services: ## 檢查 proxy 與 Receiver 是否在跑
	@curl -s -m 3 http://127.0.0.1:$(PROXY_PORT)/health >/dev/null && echo "proxy    :$(PROXY_PORT) OK" || echo "proxy    :$(PROXY_PORT) 沒有回應"
	@curl -s -m 3 -o /dev/null http://127.0.0.1:$(PORT)/ && echo "receiver :$(PORT) OK" || echo "receiver :$(PORT) 沒有回應"

ip: ## 顯示這台電腦的區網 IP（手機要填的位址）
	@hostname -I | awk '{print $$1}'
