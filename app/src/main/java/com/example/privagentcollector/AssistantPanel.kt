package com.example.privagentcollector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import kotlin.math.ceil

/**
 * Entry point for the product-facing photo and message scenarios. One task runs at a time;
 * cancelling bumps [generation],
 * so callbacks from an older task are ignored and its photo copy and recipient mapping are dropped.
 */
class AssistantPanel(
    private val activity: Activity,
    root: LinearLayout,
    private val dp: (Int) -> Int,
    private val endpoint: () -> String?,
    /** Every status line shown to the user, e.g. for reading it aloud in the quick assistant. */
    private val onSay: (String) -> Unit = {},
    /** Every task dialog right after it is shown, e.g. to enlarge its text. */
    private val onDialog: (AlertDialog) -> Unit = {},
    private val showControls: Boolean = true,
    private val onCheckMessage: () -> Unit
) {
    companion object {
        const val REQ_SPEECH = 7401
        const val REQ_PHOTO = 7402
        const val REQ_CONTACT = 7403
        private const val STALE_MS = 300_000L
    }

    private val input: EditText
    private val status: TextView
    private var generation = 0
    // Task-scoped mapping (relation -> real name, photo); cleared when the task ends.
    private var recipientName: String? = null
    private var lineRecipientName: String? = null
    private var relation: String? = null
    private var selection = "selected"
    private var bindingRelation: String? = null
    private var resumeShareAfterBinding = false
    private var directToLine = true
    // The task that launched the photo or contact picker; a result for an older task is dropped.
    private var pickerTask = -1
    private var openDialog: AlertDialog? = null

    init {
        fun label(text: String, size: Float = 16f) = TextView(activity).apply {
            this.text = text; textSize = size; setTextColor(AppUi.TEXT)
            setPadding(0, dp(8), 0, dp(8)); root.addView(this)
        }
        fun row(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((text, action) in buttons) addView(Button(activity).apply {
                this.text = text; AppUi.secondary(this); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
            root.addView(this)
        }
        input = EditText(activity).apply {
            hint = "例如：把最新照片傳給女兒"
            AppUi.field(this)
        }
        if (showControls) {
            label("PrivAgent 快速助理", 23f).let { AppUi.title(it, 23f) }
            label("用說的或打字，快速使用照片分享與詐騙訊息檢查。", 15f).let { AppUi.body(it, 15f) }
            root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(6) })
            row("用說的" to ::listen, "開始" to { run(input.text.toString()) })
            row("傳照片到 LINE" to ::sharePhoto, "檢查訊息" to { run("這則訊息可信嗎") })
            row("設定家人" to ::editBindings, "LINE 免觸控" to ::configureLineAutomation)
            row("取消目前操作" to { cancel("已取消，沒有傳送任何東西") })
        }
        status = label("", 19f)
        if (!showControls) status.visibility = android.view.View.GONE
        PhotoSanitizer.clear(activity)  // Leftover copies from an earlier session.
    }

    private fun say(text: String, color: Int = AppUi.TEXT) {
        status.visibility = android.view.View.VISIBLE
        status.text = text
        status.setTextColor(color)
        status.background = AppUi.rounded(activity, AppUi.PRIMARY_SOFT, 16)
        status.setPadding(dp(14), dp(12), dp(14), dp(12))
        onSay(text)
    }
    private fun ui(task: Int, action: () -> Unit) = activity.runOnUiThread {
        if (task == generation && !activity.isFinishing && !activity.isDestroyed) action()
    }
    private fun dialog() = AlertDialog.Builder(activity)
    private fun AlertDialog.Builder.showForTask() { openDialog = show().also(onDialog) }

    private fun newTask(): Int {
        generation++
        recipientName = null; lineRecipientName = null; relation = null
        resumeShareAfterBinding = false; bindingRelation = null; directToLine = true
        LineAutomationSession.abort()
        openDialog?.dismiss(); openDialog = null
        PhotoSanitizer.clear(activity)
        return generation
    }

    fun cancel(message: String) {
        newTask()
        say(message)
    }

    fun sharePhoto() = run("把照片傳給")
    fun shareLatestPhoto() = run("把最新照片傳給")

    fun openFamilySettings() = editBindings()
    fun openLineAutomationSettings() = configureLineAutomation()

    fun run(utterance: String) {
        val interpreted = CoreTasks.interpret(utterance)
        when (interpreted.intent) {
            "CANCEL" -> cancel("好，已取消")
            "SHARE_PHOTO" -> startShare(interpreted)
            "RESCUE_PHONE" -> say("目前版本只保留照片分享與詐騙訊息檢查。")
            "CHECK_MESSAGE" -> { newTask(); say("請在下方「可疑訊息檢查」貼上訊息，或從通知選一則。"); onCheckMessage() }
            "UNSUPPORTED" -> say("目前可以幫你分享照片，或檢查可疑訊息。")
            else -> say("我還聽不懂。可以說：「把照片傳給女兒」或「這則訊息可信嗎」。")
        }
    }

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "請說想做的事")
        try { activity.startActivityForResult(intent, REQ_SPEECH) } catch (_: ActivityNotFoundException) {
            say("這支手機沒有語音輸入，請用打字。")
        }
    }

    /** Returns true when the result belonged to this panel. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQ_SPEECH -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (resultCode == Activity.RESULT_OK && text != null) { input.setText(text); run(text) }
            }
            REQ_PHOTO -> {
                val uri = data?.data
                if (pickerTask != generation || relation == null) return true  // That share was cancelled.
                if (resultCode == Activity.RESULT_OK && uri != null) preparePhoto(generation, uri)
                else cancel("沒有選照片，已取消")
            }
            REQ_CONTACT -> if (pickerTask == generation) onContactPicked(resultCode, data?.data)
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------ SHARE_PHOTO

    private fun startShare(interpreted: CoreTasks.Interpretation) {
        val task = newTask()
        selection = interpreted.selection ?: "selected"
        say("分享照片到 LINE｜步驟 1/4：確認要傳給誰")
        val chosen = interpreted.relation
        if (chosen == null) {
            val labels = CoreTasks.relationLabel.values.toTypedArray()
            dialog().setTitle("要傳給誰？").setItems(labels) { _, i ->
                ui(task) { resolveRecipient(task, CoreTasks.relationLabel.keys.elementAt(i)) }
            }.setOnCancelListener { cancel("已取消") }.showForTask()
        } else resolveRecipient(task, chosen)
    }

    private fun resolveRecipient(task: Int, chosen: String) {
        relation = chosen
        val label = CoreTasks.relationLabel.getValue(chosen)
        val names = RelationBindings.names(activity, chosen)
        when {
            names.isEmpty() -> dialog().setTitle("還沒設定$label")
                .setMessage("我還不知道誰是你的$label。要現在從通訊錄選嗎？（只存在這支手機）")
                .setPositiveButton("從通訊錄選") { _, _ ->
                    if (task == generation) { resumeShareAfterBinding = true; pickContact(chosen) }
                }
                .setNegativeButton("取消") { _, _ -> cancel("已取消") }.showForTask()
            // Ambiguity is resolved on the phone; the cloud never sees candidate names.
            names.size > 1 -> dialog().setTitle("要傳給哪一位$label？").setItems(names.toTypedArray()) { _, i ->
                ui(task) { recipientName = names[i]; prepareLineRecipient(task) }
            }.setOnCancelListener { cancel("已取消") }.showForTask()
            else -> { recipientName = names[0]; prepareLineRecipient(task) }
        }
    }

    private fun prepareLineRecipient(task: Int) {
        val contact = recipientName ?: return
        val key = relation ?: return
        if (!LineAutomationSettings.consented(activity)) {
            lineRecipientName = null
            ensureShareDestination(task)
            return
        }
        RelationBindings.lineName(activity, key, contact)?.let {
            lineRecipientName = it
            ensureShareDestination(task)
            return
        }
        val input = EditText(activity).apply {
            setText(contact)
            selectAll()
            textSize = 20f
            hint = "LINE 顯示名稱"
        }
        dialog().setTitle("設定 LINE 中的顯示名稱")
            .setMessage("免觸控分享只會在手機上以單次 HMAC 精確比對唯一名稱。請確認這與 LINE 分享畫面顯示的名稱完全相同；畫面找不到時會有限度往下搜尋，資料不會送到雲端。")
            .setView(input)
            .setPositiveButton("儲存並繼續") { _, _ ->
                val value = input.text.toString().trim()
                if (task != generation) return@setPositiveButton
                if (value.isEmpty()) { cancel("LINE 顯示名稱不能空白，已停止") }
                else {
                    RelationBindings.setLineName(activity, key, contact, value)
                    lineRecipientName = value
                    ensureShareDestination(task)
                }
            }
            .setNegativeButton("取消") { _, _ -> cancel("已取消") }
            .setOnCancelListener { cancel("已取消") }.showForTask()
    }

    private fun ensureShareDestination(task: Int) {
        if (lineInstalled()) {
            directToLine = true
            choosePhoto(task)
            return
        }
        dialog().setTitle("這支手機尚未安裝 LINE")
            .setMessage("要完成『傳照片到 LINE』需要先安裝 LINE。也可以改用 Android 分享選單傳到其他 App。")
            .setPositiveButton("安裝 LINE") { _, _ ->
                if (task == generation) { cancel("請安裝 LINE，完成後再按「傳照片到 LINE」"); openLineStore() }
            }
            .setNeutralButton("改用其他 App") { _, _ ->
                ui(task) { directToLine = false; choosePhoto(task) }
            }
            .setNegativeButton("取消") { _, _ -> cancel("已取消") }
            .setOnCancelListener { cancel("已取消") }.showForTask()
    }

    private fun choosePhoto(task: Int) {
        if (selection == "latest") {
            val latest = latestPhoto()
            if (latest != null) { preparePhoto(task, latest); return }
        }
        say("分享照片到 LINE｜步驟 2/4：" + when (selection) {
            "family_album" -> "請在相簿選家庭相簿裡的那一張"
            "outing_album" -> "請在相簿選出遊的那一張"
            "latest" -> "請選剛拍的那一張"
            else -> "請選要分享的那一張照片"
        })
        // The system photo picker hands over only the chosen photo; no media permission is needed.
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        pickerTask = task
        try { activity.startActivityForResult(intent, REQ_PHOTO) } catch (_: ActivityNotFoundException) {
            cancel("找不到可以選照片的 App")
        }
    }

    private fun latestPhoto(): Uri? {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            activity.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }

    private fun preparePhoto(task: Int, uri: Uri) {
        say("分享照片到 LINE｜步驟 3/4：正在建立不含 EXIF／GPS 的照片副本…")
        Thread({
            val prepared = try { PhotoSanitizer.prepare(activity, uri, "PHOTO_$task") }
                catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            ui(task) {
                if (prepared == null) cancel("這張照片讀不到，請換一張") else confirmShare(task, prepared)
            }
        }, "photo-prepare").start()
    }

    private fun confirmShare(task: Int, photo: PhotoSanitizer.Result) {
        val label = CoreTasks.relationLabel.getValue(relation!!)
        val name = recipientName!!
        val lineReady = directToLine && lineInstalled()
        val noTouch = lineReady && lineRecipientName != null && LineAutomationSettings.ready(activity)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(ImageView(activity).apply { setImageBitmap(photo.thumbnail); adjustViewBounds = true; maxHeight = dp(260) })
            addView(TextView(activity).apply {
                textSize = 18f
                text = buildString {
                    append("步驟 4/4：要把這張照片傳給$label「$name」嗎？\n")
                    if (photo.hadLocation) append("・${CoreTasks.reasonText["LOCATION_REMOVED"]}\n")
                    append("・${CoreTasks.reasonText["PRIVATE_BACKGROUND_REVIEW"]}\n")
                    append(if (noTouch) "免觸控模式已就緒：畫面將自動進入 LINE、精確選取「$name」，最後請說「確認傳送」。不確定時會停止。"
                        else if (lineReady) "按「開啟 LINE」後會直接進入 LINE 的分享對象畫面，請選「$name」再按傳送。"
                        else "按「確認分享」後會打開分享選單，請選 App 和「$name」。")
                }
            })
        }
        val builder = dialog().setTitle(if (noTouch) "免觸控分享即將開始" else "確認要分享").setView(view)
        if (noTouch) {
            builder.setNegativeButton("取消") { _, _ -> cancel("已取消，照片沒有傳出去") }
                .setOnCancelListener { cancel("已取消，照片沒有傳出去") }.showForTask()
            // The original spoken request is the initial authorization; the irreversible Send
            // action still requires a second, explicit spoken confirmation inside LINE.
            status.postDelayed({
                ui(task) {
                    openDialog?.dismiss()
                    openDialog = null
                    planShare(task, photo, lineReady)
                }
            }, 1800L)
        } else builder.setPositiveButton(if (lineReady) "開啟 LINE" else "確認分享") { _, _ ->
                if (task == generation) planShare(task, photo, lineReady)
            }
            .setNegativeButton("取消") { _, _ -> cancel("已取消，照片沒有傳出去") }
            .setOnCancelListener { cancel("已取消，照片沒有傳出去") }.showForTask()
    }

    private fun planShare(task: Int, photo: PhotoSanitizer.Result, lineReady: Boolean) {
        val local = "SHARE_PHOTO_GUIDED" to listOfNotNull("CONFIRM_RECIPIENT", "LOCATION_REMOVED".takeIf { photo.hadLocation })
        val cloud = AssistConfig.cloudConsent(activity)
        val baseUrl = if (cloud) endpoint() else null
        if (!cloud || baseUrl == null) { openShare(task, photo, lineReady, "手機本機規則"); return }
        say("正在確認分享步驟…")
        val packet = CoreTasks.sharePacket(CoreTasks.ShareFacts(photo.hadLocation, false, selection))
        Thread({
            val outcome = AssistClient.plan(baseUrl, AssistConfig.token(activity), packet, local)
            ui(task) {
                when (outcome.sopId) {
                    "SHARE_PHOTO_GUIDED" -> openShare(task, photo, lineReady, outcome.source)
                    "STOP" -> cancel("AI 建議先不要分享這張照片，已停止。（${outcome.source}）")
                    else -> dialog().setTitle("再確認一次")
                        .setMessage(outcome.reasonCodes.mapNotNull { CoreTasks.reasonText[it] }.joinToString("\n・", "・"))
                        .setPositiveButton("確定要分享") { _, _ -> openShare(task, photo, lineReady, outcome.source) }
                        .setNegativeButton("不要了") { _, _ -> cancel("已取消，照片沒有傳出去") }.showForTask()
                }
            }
        }, "share-plan").start()
    }

    private fun openShare(task: Int, photo: PhotoSanitizer.Result, lineReady: Boolean, source: String) {
        if (task != generation) return
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".share", photo.file)
        val send = LineShare.imageIntent(uri, lineReady)
        val name = recipientName
        val automatic = lineReady && lineRecipientName != null && LineAutomationSettings.ready(activity)
        if (automatic) LineAutomationSession.start(lineRecipientName!!)
        try {
            activity.startActivity(if (lineReady) send else Intent.createChooser(send, "分享照片"))
        } catch (_: ActivityNotFoundException) {
            LineAutomationSession.abort()
            val fallback = LineShare.imageIntent(uri, false)
            activity.startActivity(Intent.createChooser(fallback, "分享照片"))
        }
        // The copy must outlive the share screen; it is removed when the next task starts or on cancel.
        recipientName = null; lineRecipientName = null; relation = null
        say(if (lineReady)
            if (automatic) "照片已安全交給 LINE。系統只會精確選取「$name」；請在語音詢問時說「確認傳送」。任何畫面不確定都會停止。\n判讀來源：$source"
            else "照片已安全交給 LINE。請在 LINE 選「$name」並按傳送；回到這裡不代表 LINE 已送出。\n判讀來源：$source"
        else "已打開分享畫面，請選 App 和「$name」再按傳送。照片沒有拍攝地點資訊。\n判讀來源：$source",
            AppUi.SUCCESS)
    }

    private fun lineInstalled() = try {
        activity.packageManager.getPackageInfo(LineShare.PACKAGE_NAME, 0); true
    } catch (_: Exception) { false }

    private fun openLineStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${LineShare.PACKAGE_NAME}"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${LineShare.PACKAGE_NAME}"))
        try { activity.startActivity(market) }
        catch (_: ActivityNotFoundException) {
            try { activity.startActivity(web) } catch (_: ActivityNotFoundException) { say("無法開啟 LINE 安裝頁") }
        }
    }

    private fun configureLineAutomation() {
        newTask()
        val connected = LineShareAccessibilityService.connected
        val consented = LineAutomationSettings.consented(activity)
        val status = when {
            connected && consented -> "目前已就緒。"
            consented -> "App 內已同意，但仍需在下一頁手動開啟「PrivAgent LINE 免觸控分享」。"
            else -> "目前未啟用。"
        }
        val builder = dialog().setTitle("LINE 免觸控分享")
            .setMessage("$status\n\n啟用後，只有在你主動要求分享照片後的 2 分鐘內，服務才會讀取 LINE 的可見畫面元件。點浮動按鈕後，App 會用一層半透明遮罩接住你親自點圖片的位置，該次點擊不會傳給 LINE；遮罩移除後只在同一位置執行一次長按。後續只會依序點唯一的『分享』、『分享(1)』、『更多』，再以每次任務不同金鑰的 HMAC 精確比對唯一收件人；找不到時最多往下搜尋 24 頁。它不截圖、不保存或上傳畫面，也不會自行猜測其他座標；真正送出前一定會再問。若同名、頁面不動、畫面改版、彈窗或按鈕不唯一，就停止。第一次語音確認會即時詢問麥克風權限。\n\nHMAC 是降低明文留存風險，不代表服務比對當下完全看不到文字。首次啟用是 Android 安全限制，必須由本人或家屬在系統設定手動開啟一次。")
            .setPositiveButton(if (consented) "開啟系統設定" else "同意並前往設定") { _, _ ->
                LineAutomationSettings.setConsented(activity, true)
                try { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { say("無法直接開啟設定，請從系統設定的無障礙功能手動開啟") }
            }
            .setNegativeButton("取消", null)
        if (consented) builder.setNeutralButton("停用免觸控") { _, _ ->
                LineAutomationSettings.setConsented(activity, false)
                say("已停用 LINE 免觸控分享；系統服務即使仍開啟也不會執行操作")
            }
        builder.showForTask()
    }

    // ------------------------------------------------------------ relation bindings

    private fun editBindings() {
        newTask() // Opening settings intentionally cancels any older photo/share callbacks.
        say("設定家人：選擇一個關係。設定完成後可以直接接續傳照片到 LINE。")
        val keys = CoreTasks.relationLabel.keys.toList()
        val labels = keys.map { key ->
            val names = RelationBindings.names(activity, key)
            CoreTasks.relationLabel.getValue(key) + "：" + (if (names.isEmpty()) "未設定" else names.joinToString("、"))
        }.toTypedArray()
        dialog().setTitle("家人與常用聯絡人（只存在這支手機）").setItems(labels) { _, i ->
            val key = keys[i]
            manageBinding(key)
        }.showForTask()
    }

    private fun manageBinding(key: String) {
        val label = CoreTasks.relationLabel.getValue(key)
        val names = RelationBindings.names(activity, key)
        val options = if (names.isEmpty()) arrayOf("設定並立即分享照片", "只設定聯絡人")
        else arrayOf("立即分享照片", "從通訊錄再加一位", "清除設定")
        dialog().setTitle("$label：${if (names.isEmpty()) "未設定" else names.joinToString("、")}")
            .setItems(options) { _, which ->
                if (names.isEmpty()) when (which) {
                    0 -> beginBindingAndShare(key)
                    1 -> { resumeShareAfterBinding = false; pickContact(key) }
                } else when (which) {
                    0 -> startShare(CoreTasks.Interpretation("SHARE_PHOTO", relation = key, selection = "selected"))
                    1 -> { resumeShareAfterBinding = false; pickContact(key) }
                    2 -> { RelationBindings.clear(activity, key); say("已清除${label}設定") }
                }
            }.setNegativeButton("返回", null).showForTask()
    }

    private fun beginBindingAndShare(key: String) {
        newTask()
        relation = key
        selection = "selected"
        resumeShareAfterBinding = true
        say("分享照片到 LINE｜步驟 1/4：先從通訊錄設定${CoreTasks.relationLabel.getValue(key)}")
        pickContact(key)
    }

    private fun pickContact(key: String) {
        bindingRelation = key
        pickerTask = generation
        // The contact picker grants access to the one chosen contact; no contacts permission is needed.
        try { activity.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), REQ_CONTACT) }
        catch (_: ActivityNotFoundException) {
            bindingRelation = null; resumeShareAfterBinding = false; say("找不到通訊錄 App")
        }
    }

    private fun onContactPicked(resultCode: Int, uri: Uri?) {
        val key = bindingRelation ?: return
        bindingRelation = null
        val name = if (resultCode == Activity.RESULT_OK && uri != null) try {
            activity.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null } else null
        val continueShare = resumeShareAfterBinding
        resumeShareAfterBinding = false
        if (name.isNullOrBlank()) {
            if (continueShare) cancel("沒有選聯絡人，已取消") else say("沒有選聯絡人，原本設定沒有變更")
            return
        }
        RelationBindings.add(activity, key, name)
        say("已設定${CoreTasks.relationLabel.getValue(key)}：$name")
        if (continueShare) resolveRecipient(generation, key)
        else dialog().setTitle("設定完成")
            .setMessage("已把「$name」設為${CoreTasks.relationLabel.getValue(key)}。要現在直接選照片傳到 LINE 嗎？")
            .setPositiveButton("現在傳照片") { _, _ ->
                startShare(CoreTasks.Interpretation("SHARE_PHOTO", relation = key, selection = "selected"))
            }.setNegativeButton("完成", null).showForTask()
    }

    // ------------------------------------------------------------ RESCUE_PHONE

    private fun startRescue(interpreted: CoreTasks.Interpretation) {
        val task = newTask()
        val category = interpreted.symptomCategory!!
        val detail = interpreted.symptomDetail!!
        val scope = interpreted.requestedScope!!
        val readAt = System.currentTimeMillis()
        val state = DeviceState.read(activity)
        val local = CoreTasks.rescueRule(state, category, detail, scope)
        val baseUrl = if (AssistConfig.cloudConsent(activity) && local.first == "RESCUE_ASK_DESCRIBE" && scope == "guided_help")
            endpoint() else null
        if (baseUrl == null) { showRescue(task, local.first, local.second, "手機本機規則", category, detail, scope, state, readAt); return }
        say("手機沒有找到明確原因，正在請 AI 看看狀態分級…")
        Thread({
            val outcome = AssistClient.plan(baseUrl, AssistConfig.token(activity), CoreTasks.rescuePacket(state, category, detail), local)
            ui(task) { showRescue(task, outcome.sopId, outcome.reasonCodes, outcome.source, category, detail, scope, state, readAt) }
        }, "rescue-plan").start()
    }

    private fun showRescue(task: Int, sop: String, codes: List<String>, source: String, category: String, detail: String,
                           scope: String, state: CoreTasks.RescueState, readAt: Long) {
        val reasons = codes.mapNotNull { CoreTasks.reasonText[it] }
        val body = buildString {
            CoreTasks.sopTitle[sop]?.let { append(it).append("\n") }
            append(reasons.joinToString("\n") { "・$it" })
            append("\n\n判讀來源：").append(source)
        }
        val action = CoreTasks.sopAction[sop]
        say(body, if (action != null) AppUi.WARNING else AppUi.NEUTRAL)
        val builder = dialog().setTitle(if (action != null) "找到可能的原因" else "手機檢查結果").setMessage(body)
        if (action != null && scope == "guided_help") {
            builder.setPositiveButton(action) { _, _ ->
                if (task != generation) return@setPositiveButton
                // State may have changed while the dialog was open: re-read before acting.
                if (System.currentTimeMillis() - readAt > STALE_MS || DeviceState.read(activity) != state) {
                    say("手機狀態已經改變，重新檢查一次…"); startRescue(CoreTasks.Interpretation("RESCUE_PHONE",
                        symptomCategory = category, symptomDetail = detail, requestedScope = scope)); return@setPositiveButton
                }
                executeRescue(task, sop)
            }.setNegativeButton("先不要", null)
        } else builder.setPositiveButton("好", null)
        builder.showForTask()
    }

    private fun executeRescue(task: Int, sop: String) {
        if (task != generation) return
        if (sop == "RESCUE_RAISE_VOLUME") {
            val am = activity.getSystemService(AudioManager::class.java)
            try {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                val target = ceil(max * 0.7).toInt()
                am.setStreamVolume(AudioManager.STREAM_RING, target, AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)
                // Some phones ignore the change silently; only report success when it actually took effect.
                if (am.getStreamVolume(AudioManager.STREAM_RING) >= target) {
                    say("已把鈴聲調到七成。如果還是聽不到，請再按一次「手機救援」。", AppUi.SUCCESS)
                    return
                }
            } catch (_: SecurityException) {
                // Some states (e.g. Do Not Disturb) block volume changes without extra access; guide instead.
            }
        }
        val intent = when (sop) {
            "RESCUE_AIRPLANE_SETTINGS" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "RESCUE_WIFI_SETTINGS" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "RESCUE_INTERNET_SETTINGS" -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                else Settings.ACTION_WIRELESS_SETTINGS)
            "RESCUE_DND_SETTINGS" -> Intent("android.settings.ZEN_MODE_SETTINGS")
            "RESCUE_RAISE_VOLUME" -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME
                else Settings.ACTION_SOUND_SETTINGS)
            else -> Intent(Settings.ACTION_SOUND_SETTINGS)
        }
        try { activity.startActivity(intent) } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        say("已打開設定頁，請照畫面調整；我不會自動幫你改。調好後可以再按一次「手機救援」確認。", AppUi.SUCCESS)
    }
}
