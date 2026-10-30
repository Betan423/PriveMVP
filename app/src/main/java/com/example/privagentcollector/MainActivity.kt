package com.example.privagentcollector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_EXPORT = 101
        @Volatile private var busy = false
        @Volatile private var clearing = false
        @Volatile private var latestReport = "尚未蒐集"
    }
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var startButton: Button
    private lateinit var snapshotButton: Button
    private lateinit var endpointInput: EditText
    private lateinit var healthButton: Button
    private lateinit var sendButton: Button
    private lateinit var baselineInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var immediateBox: CheckBox
    private var messagePanel: MessageCheckPanel? = null
    private var assistantPanel: AssistantPanel? = null
    private lateinit var realtimeButton: Button
    private lateinit var realtimeStatus: TextView
    private lateinit var clearButton: Button
    private val boxes = mutableMapOf<DataSource, CheckBox>()
    private val handler = Handler(Looper.getMainLooper())
    private var storageBytes = 0L
    private var legacy = false
    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            if (!isFinishing && !isDestroyed) handler.postDelayed(this, 3000)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun ui(action: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) action()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)); root.addView(this)
        }
        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text; setOnClickListener { action() }; root.addView(this)
        }
        // The Receiver URL comes from the field further down; it is read only when a request is sent.
        assistantPanel = AssistantPanel(this, root, ::dp, ::assistEndpoint) { messagePanel?.pickNotification() }
        messagePanel = MessageCheckPanel(this, root, ::dp, ::assistEndpoint)
        label("PrivAgent 資料蒐集 MVP 0.4", 21f)
        label("研究資料蒐集", 21f)
        label("研究手機使用。可選擇手動蒐集，或啟動即時模式：先建立一次基礎快照，再定期更新裝置狀態，通知與畫面事件可在產生後送出。即時模式使用前景服務，必須從本頁手動啟動與停止。")
        label("聯絡人、位置、通知、畫面可能含你與他人的個人資料。請只在獲得授權的研究手機使用。畫面與通知會嘗試排除密碼、驗證碼及敏感 App，但無法保證識別所有敏感內容。")
        label("每檔最多約 5 MB、總量最多 25 MB；超過容量或 7 天保留期限會刪除最舊資料。匯出為未加密 NDJSON，由你選擇存放位置。")
        val consent = CheckBox(this).apply {
            text = "我已閱讀，並同意依勾選項目蒐集研究資料"
            isChecked = CollectionControl.consented(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!checked && RealtimeRuntime.active) RealtimeTransmissionService.stop(this@MainActivity)
                CollectionControl.setConsent(this@MainActivity, checked)
                refresh()
            }
        }
        root.addView(consent)
        status = label("", 16f)
        label("資料類別（可分別開關）", 19f)
        for (source in DataSource.entries) {
            val box = CheckBox(this).apply {
                text = source.title
                isChecked = CollectionControl.selected(this@MainActivity, source)
                setOnCheckedChangeListener { _, checked ->
                    if (source == DataSource.ACCESSIBILITY && checked &&
                        !CollectionControl.accessibilityConsented(this@MainActivity)) {
                        accessibilityDisclosure(
                            { CollectionControl.select(this@MainActivity, source, true); refresh() },
                            { isChecked = false })
                    } else {
                        CollectionControl.select(this@MainActivity, source, checked)
                        refresh()
                    }
                }
            }
            boxes[source] = box
            root.addView(box)
        }
        label("Wi-Fi 名稱可能需要位置權限與系統定位開啟。位置只讀最後已知位置，可能為空或過期。App 清單受系統可見範圍限制。")
        button("要求所選類別的一般權限") { requestSelectedPermissions() }
        button("開啟通知存取設定") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        button("開啟 App 使用存取設定") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        button("開啟無障礙服務設定") {
            if (CollectionControl.accessibilityConsented(this)) openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else accessibilityDisclosure({ openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, {})
        }
        startButton = button("開始蒐集") {
            if (CollectionControl.active()) {
                CollectionControl.stop()
                latestReport = "已停止，等待中的舊事件不會加入新的蒐集工作階段"
                refresh()
            } else {
                try {
                    CollectionControl.start(this)
                    collectSnapshot()
                    refresh()
                } catch (e: IllegalStateException) { message(e.message ?: "無法開始") }
            }
        }
        snapshotButton = button("蒐集一次快照") { collectSnapshot() }
        button("排除畫面／通知的 App") {
            val input = EditText(this).apply {
                hint = "以逗號分隔 App package name"
                setText(CollectionControl.exclusions(this@MainActivity).joinToString(","))
            }
            AlertDialog.Builder(this).setTitle("額外排除清單").setMessage("內建排除銀行、錢包、驗證器與密碼管理器的常見名稱。可在此加入其他 App 的完整 package name。")
                .setView(input).setPositiveButton("儲存") { _, _ -> CollectionControl.setExclusions(this, input.text.toString()) }
                .setNegativeButton("取消", null).show()
        }
        button("匯出資料 NDJSON") {
            AlertDialog.Builder(this).setTitle("匯出未加密原始資料")
                .setMessage("匯出的檔案包含勾選類別的原始資料。請選擇你信任的位置；若選雲端文件供應者，檔案可能會上傳至該服務。")
                .setPositiveButton("選擇位置") { _, _ -> beginExport() }.setNegativeButton("取消", null).show()
        }
        label("原始資料傳遞（開發版）", 19f)
        label("此初版會把完整、未匿名化的 NDJSON 原始資料傳到你指定的開發接收端。請先停止蒐集；傳送成功後手機內原始資料仍會保留。HTTP 僅供可信任區網開發測試。")
        endpointInput = EditText(this).apply {
            hint = "例如 http://10.0.2.2:8080"
            setText(TransferConfig.baseUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(endpointInput)
        healthButton = button("測試接收端連線") { beginHealthCheck() }
        sendButton = button("手動傳送全部原始資料") { confirmTransfer() }
        label("即時情境資料管線", 19f)
        label("啟動後先做一次完整基礎快照；等待時間內累積通知與畫面事件，之後送出 baseline。後續週期只更新裝置、電池、網路與位置；通知／畫面採事件觸發。尚未確認的批次會原樣重試，不會把新事件混入舊批次。")
        val realtimeSettings = RealtimeConfig.load(this)
        label("基礎資料等待秒數（5–600）", 14f)
        baselineInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(realtimeSettings.baselineSeconds.toString())
            setSelectAllOnFocus(true)
        }
        root.addView(baselineInput)
        label("定期更新秒數（10–3600）", 14f)
        intervalInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(realtimeSettings.intervalSeconds.toString())
            setSelectAllOnFocus(true)
        }
        root.addView(intervalInput)
        immediateBox = CheckBox(this).apply {
            text = "通知與畫面事件產生後立即傳送（2 秒合併）"
            isChecked = realtimeSettings.immediateEvents
        }
        root.addView(immediateBox)
        realtimeStatus = label(RealtimeRuntime.statusText(), 14f)
        realtimeButton = button("啟動即時蒐集與傳送") { toggleRealtime() }
        clearButton = button("停止並清除所有本機資料") {
            if (RealtimeRuntime.active || RealtimeRuntime.phase == "stopping") {
                message("請先停止即時模式，等待狀態顯示已停止後再清除")
                return@button
            }
            AlertDialog.Builder(this).setTitle("清除本機資料？")
                .setMessage("會停止蒐集、刪除加密資料與舊版明文資料。已匯出的檔案不會被刪除。")
                .setPositiveButton("清除") { _, _ ->
                    if (clearing) return@setPositiveButton
                    clearing = true
                    CollectionControl.stop()
                    val accepted = CollectorRuntime.submit {
                        try {
                            ResearchLog.clear(applicationContext)
                            latestReport = "本機資料已清除"
                        } catch (_: Exception) { latestReport = "清除失敗，請檢查手機儲存空間後重試" }
                        finally { clearing = false }
                        refreshStorage()
                        ui { refresh() }
                    }
                    if (!accepted) {
                        clearing = false
                        message("事件佇列忙碌；已停止蒐集，請稍後再清除")
                    }
                    refresh()
                }.setNegativeButton("取消", null).show()
        }
        report = label(latestReport, 14f)
        val scroll = ScrollView(this).apply {
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        messagePanel?.renderAuto()
        handler.post(refreshTick)
        refreshStorage()
    }
    override fun onPause() {
        handler.removeCallbacks(refreshTick)
        super.onPause()
    }

    private fun accessibilityDisclosure(accepted: () -> Unit, declined: () -> Unit) {
        AlertDialog.Builder(this).setTitle("畫面與互動資料蒐集同意")
            .setMessage("啟用後，此研究 App 可讀取其他 App 當前畫面的文字、元件結構與互動事件；即使切換離開此 App，開始蒐集期間仍會記錄。資料會加密存於本機；若你另外啟動即時模式，也會自動傳到你設定的電腦接收端。\n\n用途是研究手機助理可取得的畫面資訊。內建敏感畫面排除與文字遮蔽並非完整保護。可隨時停止、關閉此類別、清除資料，或在系統設定停用服務。")
            .setPositiveButton("同意") { _, _ -> CollectionControl.consentAccessibility(this); accepted() }
            .setNegativeButton("不同意") { _, _ -> declined() }
            .setOnCancelListener { declined() }.show()
    }
    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: Exception) { message("這台手機無法直接開啟此設定，請從系統設定手動開啟") }
    }
    private fun requestSelectedPermissions() {
        if (!CollectionControl.consented(this)) { message("請先閱讀並同意蒐集說明"); return }
        val permissions = mutableListOf<String>()
        fun selected(s: DataSource) = CollectionControl.selected(this, s)
        if (selected(DataSource.CONTACTS)) permissions += Manifest.permission.READ_CONTACTS
        if (selected(DataSource.CALENDAR)) permissions += Manifest.permission.READ_CALENDAR
        if (selected(DataSource.LOCATION)) permissions += listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            if (selected(DataSource.IMAGES)) permissions += Manifest.permission.READ_MEDIA_IMAGES
            if (selected(DataSource.VIDEOS)) permissions += Manifest.permission.READ_MEDIA_VIDEO
            if (selected(DataSource.AUDIO)) permissions += Manifest.permission.READ_MEDIA_AUDIO
            if (Build.VERSION.SDK_INT >= 34 && (selected(DataSource.IMAGES) || selected(DataSource.VIDEOS)))
                permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else if (selected(DataSource.IMAGES) || selected(DataSource.VIDEOS) || selected(DataSource.AUDIO)) {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // Keep granted visual permissions in the request to support photo re-selection.
        if (permissions.isEmpty()) message("所選資料不需要一般權限")
        else requestPermissions(permissions.distinct().toTypedArray(), REQ_PERMS)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            message("權限狀態已更新；拒絕的資料會在報告標記為不可用")
            refresh()
        }
    }
    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun permissionStatus(source: DataSource): String = when (source) {
        DataSource.CONTACTS -> if (granted(Manifest.permission.READ_CONTACTS)) "已授權" else "未授權"
        DataSource.CALENDAR -> if (granted(Manifest.permission.READ_CALENDAR)) "已授權" else "未授權"
        DataSource.LOCATION -> when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "精確位置"
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "概略位置"
            else -> "未授權"
        }
        DataSource.IMAGES, DataSource.VIDEOS, DataSource.AUDIO -> {
            val perm = if (Build.VERSION.SDK_INT < 33) Manifest.permission.READ_EXTERNAL_STORAGE else when (source) {
                DataSource.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
                DataSource.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                else -> Manifest.permission.READ_MEDIA_AUDIO
            }
            when {
                granted(perm) -> "已授權"
                Build.VERSION.SDK_INT >= 34 && source != DataSource.AUDIO &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "僅所選照片／影片"
                else -> "未授權"
            }
        }
        DataSource.USAGE -> if (Collectors.usageGranted(this)) "已授權" else "需系統設定"
        DataSource.NOTIFICATIONS -> if (getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(this, ResearchNotificationListener::class.java))) {
            if (ResearchNotificationListener.connected()) "服務已連線" else "等待服務連線"
        } else "需系統設定"
        DataSource.ACCESSIBILITY -> if (ResearchAccessibilityService.connected) "服務已連線" else "需系統設定"
        else -> "無需一般權限"
    }
    private fun refresh() {
        if (!::status.isInitialized) return
        val realtimeRunning = RealtimeRuntime.active || RealtimeRuntime.phase == "stopping"
        status.text = (when {
            realtimeRunning -> "● 即時模式運行中"
            CollectionControl.active() -> "● 手動蒐集中"
            else -> "○ 已停止"
        }) +
            "\n本機加密資料：${String.format(Locale.US, "%.2f", storageBytes / 1048576.0)} MB" +
            "\n佇列略過事件：${CollectorRuntime.dropped.get()}" +
            (CollectorRuntime.lastError?.let { "\n$it" } ?: "") +
            (if (legacy) "\n偵測到 v0.1 明文資料，可先匯出後用「清除」刪除。" else "")
        boxes.forEach { (source, box) ->
            box.text = "${source.title}（${permissionStatus(source)}）"
            box.isEnabled = !realtimeRunning
        }
        if (::startButton.isInitialized) {
            startButton.text = when {
                realtimeRunning -> "即時模式控制中"
                CollectionControl.active() -> "停止蒐集"
                else -> "開始蒐集"
            }
            startButton.isEnabled = !realtimeRunning && !clearing && (CollectionControl.active() || !busy)
        }
        if (::snapshotButton.isInitialized) snapshotButton.isEnabled =
            CollectionControl.active() && !realtimeRunning && !busy && !clearing
        if (::healthButton.isInitialized) healthButton.isEnabled = !busy && !clearing
        if (::sendButton.isInitialized) sendButton.isEnabled =
            !CollectionControl.active() && !realtimeRunning && !busy && !clearing
        if (::endpointInput.isInitialized) endpointInput.isEnabled = !realtimeRunning && !busy && !clearing
        if (::baselineInput.isInitialized) baselineInput.isEnabled = !realtimeRunning
        if (::intervalInput.isInitialized) intervalInput.isEnabled = !realtimeRunning
        if (::immediateBox.isInitialized) immediateBox.isEnabled = !realtimeRunning
        if (::realtimeButton.isInitialized) {
            realtimeButton.text = if (RealtimeRuntime.active) "停止即時蒐集與傳送" else "啟動即時蒐集與傳送"
            realtimeButton.isEnabled = !busy && !clearing && RealtimeRuntime.phase != "stopping"
        }
        if (::realtimeStatus.isInitialized) realtimeStatus.text = RealtimeRuntime.statusText()
        if (::clearButton.isInitialized) clearButton.isEnabled = !realtimeRunning && !busy && !clearing
        if (::report.isInitialized) report.text = latestReport
    }
    private fun refreshStorage() {
        CollectorRuntime.submit {
            ResearchLog.maintain(applicationContext)
            storageBytes = ResearchLog.size(applicationContext)
            legacy = ResearchLog.legacyExists(applicationContext)
            ui { refresh() }
        }
    }
    private fun collectSnapshot() {
        val session = CollectionControl.session() ?: run { message("請先按開始蒐集"); return }
        if (busy || clearing) { message("資料處理中，請稍候"); return }
        busy = true
        latestReport = "正在蒐集…"
        val accepted = CollectorRuntime.submit {
            try {
                val results = Collectors.collectAll(applicationContext, session) { title ->
                    latestReport = "正在蒐集：$title"; ui { refresh() }
                }.toMutableList()
                if (CollectionControl.allowed(applicationContext, DataSource.NOTIFICATIONS, session)) {
                    val value = try { ResearchNotificationListener.snapshot(session) }
                        catch (_: Exception) { "error" }
                    results += DataSource.NOTIFICATIONS to value
                }
                latestReport = (if (CollectionControl.session() == session) "快照完成" else "蒐集已停止") +
                    "\n" + results.joinToString("\n") { "${it.first.title}：${it.second}" } +
                    "\n畫面事件需在其他 App 操作時產生。"
            } catch (e: Exception) {
                latestReport = "快照失敗：${e.javaClass.simpleName}"
            } finally {
                busy = false
                refreshStorage()
                ui { refresh() }
            }
        }
        if (!accepted) { busy = false; latestReport = "事件佇列忙碌，請稍後重試" }
        refresh()
    }
    private fun beginExport() {
        if (busy || clearing) { message("請等目前資料處理完成再匯出"); return }
        val name = "raw_events_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.ndjson"
        busy = true
        val accepted = CollectorRuntime.submit {
            try {
                val hasData = ResearchLog.hasData(applicationContext)
                ui {
                    if (!hasData) message("目前沒有資料，請先開始蒐集")
                    else try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-ndjson"
                            putExtra(Intent.EXTRA_TITLE, name)
                        }, REQ_EXPORT)
                    } catch (_: Exception) { message("沒有可處理匯出的文件 App") }
                }
            } finally { busy = false; ui { refresh() } }
        }
        if (!accepted) { busy = false; message("事件佇列忙碌，請稍後重試") }
    }
    private fun assistEndpoint(): String? {
        if (!AssistConfig.builtIn) return validatedEndpoint()
        return try {
            TransferProtocol.normalizeBaseUrl(AssistConfig.baseUrl(this))
        } catch (e: IllegalArgumentException) {
            message("APK 內建的 AI 端點格式錯誤，請重新產生 APK")
            null
        }
    }

    private fun validatedEndpoint(): String? = try {
        val value = TransferConfig.setBaseUrl(this, endpointInput.text.toString())
        endpointInput.setText(value)
        value
    } catch (e: IllegalArgumentException) {
        message(e.message ?: "接收端網址格式錯誤")
        null
    }
    private fun beginHealthCheck() {
        if (busy || clearing) { message("資料處理中，請稍候"); return }
        val endpoint = validatedEndpoint() ?: return
        busy = true
        latestReport = "正在測試接收端連線…"
        val accepted = CollectorRuntime.submit {
            try {
                val result = RawDataSender.checkHealth(endpoint)
                latestReport = "接收端連線正常（HTTP ${result.statusCode}）\n$endpoint"
                ui { message("接收端連線正常") }
            } catch (e: Exception) {
                latestReport = "接收端連線失敗：${e.message ?: e.javaClass.simpleName}"
                ui { message(latestReport) }
            } finally {
                busy = false
                ui { refresh() }
            }
        }
        if (!accepted) { busy = false; latestReport = "事件佇列忙碌，連線測試未執行" }
        refresh()
    }
    private fun toggleRealtime() {
        if (RealtimeRuntime.active) {
            RealtimeTransmissionService.stop(this)
            latestReport = "正在停止即時模式…"
            refresh()
            return
        }
        if (busy || clearing) { message("資料處理中，請稍候"); return }
        if (CollectionControl.active()) { message("請先停止目前的手動蒐集"); return }
        if (!CollectionControl.consented(this)) { message("請先閱讀並同意蒐集說明"); return }
        if (DataSource.entries.none { CollectionControl.selected(this, it) }) {
            message("請至少選擇一個資料類別")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
            message("請允許通知後，再按一次啟動即時模式")
            return
        }
        val endpoint = validatedEndpoint() ?: return
        val settings = try {
            RealtimeSettingRules.parse(
                baselineInput.text.toString(),
                intervalInput.text.toString(),
                immediateBox.isChecked
            )
        } catch (e: IllegalArgumentException) {
            message(e.message ?: "即時設定錯誤")
            return
        }
        try {
            RealtimeConfig.save(this, settings)
            RealtimeTransmissionService.start(this, endpoint, settings)
            latestReport = "即時模式已送出啟動要求；可切換到其他 App，前景通知會顯示運行狀態"
        } catch (e: Exception) {
            latestReport = "即時模式啟動失敗：${e.message ?: e.javaClass.simpleName}"
            message(latestReport)
        }
        refresh()
    }
    private fun confirmTransfer() {
        if (CollectionControl.active()) { message("請先停止蒐集再傳送"); return }
        if (busy || clearing) { message("資料處理中，請稍候"); return }
        val endpoint = validatedEndpoint() ?: return
        AlertDialog.Builder(this).setTitle("傳送完整原始資料？")
            .setMessage("這是未加入匿名化與隱私轉換的開發功能，會把目前全部 NDJSON 原始資料傳到：\n\n$endpoint\n\n接收成功後，本機資料仍會保留。")
            .setPositiveButton("確認傳送") { _, _ -> beginTransfer(endpoint) }
            .setNegativeButton("取消", null).show()
    }
    private fun beginTransfer(endpoint: String) {
        if (CollectionControl.active()) { message("請先停止蒐集再傳送"); return }
        busy = true
        latestReport = "正在傳送原始資料…"
        val accepted = CollectorRuntime.submit {
            try {
                val result = RawDataSender.sendAll(applicationContext, endpoint)
                val acceptedText = result.acceptedRecords?.let { "，新增 $it 筆" }.orEmpty()
                val duplicateText = result.duplicateRecords?.let { "，重複 $it 筆" }.orEmpty()
                val retryText = if (result.duplicateTransmission) "，安全重送未重複落盤" else ""
                latestReport = "傳送完成：接收 ${result.receivedRecords} 筆$acceptedText$duplicateText$retryText" +
                    "\nTransfer ID：${result.acknowledgedTransferId}" +
                    "\nSHA-256：${result.acknowledgedSha256}" +
                    "\n本機資料仍保留"
                ui { message("傳送完成，共 ${result.receivedRecords} 筆") }
            } catch (e: Exception) {
                latestReport = "傳送失敗：${e.message ?: e.javaClass.simpleName}；本機資料未刪除"
                ui { message(latestReport) }
            } finally {
                busy = false
                ui { refresh() }
            }
        }
        if (!accepted) { busy = false; latestReport = "事件佇列忙碌，傳送未執行" }
        refresh()
    }
    @Deprecated("Uses framework Activity result API for this minimal app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (assistantPanel?.onActivityResult(requestCode, resultCode, data) == true) return
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        busy = true
        latestReport = "正在匯出…"
        val accepted = CollectorRuntime.submit {
            try {
                check(ResearchLog.hasData(applicationContext)) { "No data" }
                val out = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                val count = out.use { ResearchLog.export(applicationContext, it) }
                latestReport = "已匯出 $count 筆資料（未加密）"
                ui { message("匯出完成，共 $count 筆") }
            } catch (e: Exception) {
                latestReport = "匯出失敗：${e.javaClass.simpleName}；目的地可能含不完整檔案，請刪除後重試"
                ui { message(latestReport) }
            } finally { busy = false; ui { refresh() } }
        }
        if (!accepted) { busy = false; latestReport = "事件佇列忙碌，匯出未執行，請稍後重試" }
        refresh()
    }
}
