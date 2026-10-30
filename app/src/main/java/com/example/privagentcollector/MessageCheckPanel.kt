package com.example.privagentcollector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CHECK_MESSAGE demo: the user picks or pastes ONE message, the phone redacts it and extracts
 * features, shows exactly what would be sent, and only then asks the Receiver (and its cloud AI).
 */
class MessageCheckPanel(
    private val activity: Activity,
    root: LinearLayout,
    private val dp: (Int) -> Int,
    /** Validated Receiver base URL from the main screen, or null if the field is invalid. */
    private val endpoint: () -> String?
) {
    companion object {
        // A request outlives the Activity (e.g. recreation); its state and result live here.
        @Volatile private var sending = false
        @Volatile private var resultText: CharSequence = ""
        @Volatile private var resultColor = Color.DKGRAY
        @Volatile private var sourceText: CharSequence = ""
        private var current = WeakReference<MessageCheckPanel>(null)

        private fun publish(text: CharSequence, color: Int, source: CharSequence, done: Boolean) {
            resultText = text; resultColor = color; sourceText = source
            if (done) sending = false
            current.get()?.let { panel -> panel.ui { panel.render() } }
        }
    }

    private val autoBox: CheckBox
    private val autoStatus: TextView
    // Held here so the watcher's weak reference lives exactly as long as this panel.
    private val onAutoResult: () -> Unit = { ui { renderAuto() } }
    private val input: EditText
    private val pickButton: Button
    private val cloudBox: CheckBox
    private val excerptBox: CheckBox
    private val tokenInput: EditText
    private val checkButton: Button
    private val result: TextView
    private val source: TextView
    private var preparing = false
    private var fromNotification = false
    private var settingText = false

    init {
        fun label(text: String, size: Float = 16f) = TextView(activity).apply {
            this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)); root.addView(this)
        }
        label("可疑訊息檢查", 21f)
        label("選一則通知或貼上訊息。手機會先遮蔽電話、帳號、驗證碼、網址與姓名，只把風險特徵交給電腦與雲端 AI；原文不會離開手機。")
        autoBox = CheckBox(activity).apply {
            text = "自動檢查新進來的通知（高風險時跳出提醒；只送特徵，不送摘錄）"
            isChecked = AssistConfig.autoCheck(activity)
            textSize = 16f
            setOnCheckedChangeListener { _, checked -> setAuto(checked) }
            root.addView(this)
        }
        autoStatus = label("", 14f)
        input = EditText(activity).apply {
            hint = "貼上要檢查的訊息"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            textSize = 18f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!settingText) fromNotification = false }
            })
        }
        root.addView(input)
        pickButton = Button(activity).apply {
            text = "從目前的通知選一則"
            setOnClickListener { pickNotification() }
            root.addView(this)
        }
        cloudBox = CheckBox(activity).apply {
            text = "同意使用雲端 AI 判讀（只送遮蔽後的特徵）"
            isChecked = AssistConfig.cloudConsent(activity)
            textSize = 16f
            // Background checks read the saved choice, so withdrawing consent takes effect at once.
            setOnCheckedChangeListener { _, _ -> saveOptions() }
            root.addView(this)
        }
        excerptBox = CheckBox(activity).apply {
            text = "也傳送遮蔽後的訊息摘錄（可提高判讀品質，但無法保證所有姓名都被遮蔽；高風險訊息一律不送）"
            isChecked = AssistConfig.allowExcerpt(activity)
            root.addView(this)
        }
        tokenInput = EditText(activity).apply {
            hint = "電腦接收端存取碼（沒有設定可留空）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(AssistConfig.token(activity))
            root.addView(this)
        }
        checkButton = Button(activity).apply {
            text = "檢查這則訊息"
            textSize = 18f
            setOnClickListener { begin() }
            root.addView(this)
        }
        result = label("", 20f)
        source = label("", 14f)
        current = WeakReference(this)
        LiveMessageWatch.observe(onAutoResult)
        render()
        renderAuto()
    }

    private fun saveOptions() =
        AssistConfig.save(activity, tokenInput.text.toString(), cloudBox.isChecked, excerptBox.isChecked)

    private fun setAuto(enabled: Boolean) {
        if (enabled && cloudBox.isChecked && endpoint() == null) { autoBox.isChecked = false; return }
        saveOptions()
        AssistConfig.setAutoCheck(activity, enabled)
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301)
        }
        renderAuto()
    }

    fun renderAuto() {
        val format = SimpleDateFormat("HH:mm", Locale.TAIWAN)
        val levelText = mapOf(MessageGuard.Level.HIGH to "高風險", MessageGuard.Level.VERIFY to "需要核實",
            MessageGuard.Level.INSUFFICIENT to "資訊不足")
        autoStatus.text = when {
            !autoBox.isChecked -> ""
            !ResearchNotificationListener.connected() -> "請先在系統設定開啟本 App 的通知存取，才能自動檢查"
            else -> LiveMessageWatch.recentResults().ifEmpty { null }?.joinToString("\n", prefix = "最近自動檢查：\n") {
                "${format.format(Date(it.time))} ${it.appLabel}${if (it.sender.isEmpty()) "" else "「${it.sender}」"}：${levelText[it.level]}"
            } ?: "最近自動檢查：還沒有新通知"
        }
    }

    private fun render() {
        val locked = sending || preparing
        // The message cannot change while it is being checked, so the result always matches it.
        input.isEnabled = !locked
        pickButton.isEnabled = !locked
        checkButton.isEnabled = !locked
        result.text = resultText
        result.setTextColor(resultColor)
        source.text = sourceText
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun ui(action: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed) action()
    }

    fun pickNotification() {
        val messages = ResearchNotificationListener.currentMessages()
        if (messages == null) { toast("請先在系統設定開啟本 App 的通知存取"); return }
        if (messages.isEmpty()) { toast("目前沒有可檢查的通知"); return }
        val labels = messages.map { "${it.appLabel}｜${it.title}\n${it.body.take(40)}" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("選一則要檢查的通知")
            .setItems(labels) { _, index ->
                settingText = true
                input.setText(messages[index].body)
                settingText = false
                fromNotification = true
            }
            .setNegativeButton("取消", null).show()
    }

    private fun begin() {
        if (sending || preparing) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast("請先貼上或選一則訊息"); return }
        val cloud = cloudBox.isChecked
        val baseUrl = if (cloud) endpoint() ?: return else ""
        AssistConfig.save(activity, tokenInput.text.toString(), cloud, excerptBox.isChecked)
        preparing = true
        publish("正在遮蔽與分析…", Color.DKGRAY, "", done = false)
        val sourceKind = if (fromNotification) "notification" else "pasted"
        val allowExcerpt = excerptBox.isChecked
        Thread({
            val packet = MessageGuard.buildPacket(text, sourceKind, allowExcerpt, contactNames())
            ui {
                if (!cloud) { send("", packet, false); return@ui }
                AlertDialog.Builder(activity).setTitle("確認要傳送的內容")
                    .setMessage("只會把下列內容傳到 $baseUrl，再交給雲端 AI：\n\n${MessageGuard.describe(packet)}")
                    .setPositiveButton("傳送") { _, _ -> send(baseUrl, packet, true) }
                    .setNegativeButton("改用手機本機判讀") { _, _ -> send("", packet, false) }
                    .setOnCancelListener { preparing = false; publish("已取消，沒有傳送任何資料", Color.DKGRAY, "", done = true) }
                    .show()
            }
        }, "message-check").start()
    }

    private fun send(baseUrl: String, packet: MessageGuard.Packet, cloud: Boolean) {
        preparing = false
        sending = true
        publish(if (cloud) "已送出，等待判讀（約 5–15 秒）…" else "手機本機判讀中…", Color.DKGRAY, "", done = false)
        val token = tokenInput.text.toString().trim()
        Thread({
            val outcome = AssistClient.check(baseUrl, token, packet, cloud)
            val text = buildString {
                append(MessageGuard.advice.getValue(outcome.level))
                for (code in outcome.reasonCodes) MessageGuard.reasonText[code]?.let { append("\n・").append(it) }
            }
            val color = when (outcome.level) {
                MessageGuard.Level.HIGH -> Color.rgb(183, 28, 28)
                MessageGuard.Level.VERIFY -> Color.rgb(230, 81, 0)
                MessageGuard.Level.INSUFFICIENT -> Color.DKGRAY
            }
            publish(text, color, "判讀來源：${outcome.source}", done = true)
        }, "message-check").start()
    }

    /** Contact names are read only to redact them on the phone; requires READ_CONTACTS already granted. */
    private fun contactNames(): List<String> {
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            activity.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext() && names.size < 5000) {
                    cursor.getString(0)?.trim()?.takeIf { it.length >= 2 }?.let(names::add)
                }
                names
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}
