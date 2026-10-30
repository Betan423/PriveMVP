package com.example.privagentcollector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
 * Entry point for the three core scenarios. One task runs at a time; cancelling bumps [generation],
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
    private val onCheckMessage: () -> Unit
) {
    companion object {
        const val REQ_SPEECH = 7401
        const val REQ_PHOTO = 7402
        const val REQ_CONTACT = 7403
        private const val LINE_PACKAGE = "jp.naver.line.android"
        private const val STALE_MS = 300_000L
    }

    private val input: EditText
    private val status: TextView
    private var generation = 0
    // Task-scoped mapping (relation -> real name, photo); cleared when the task ends.
    private var recipientName: String? = null
    private var relation: String? = null
    private var selection = "selected"
    private var bindingRelation: String? = null
    private var resumeShareAfterBinding = false
    // The task that launched the photo or contact picker; a result for an older task is dropped.
    private var pickerTask = -1
    private var openDialog: AlertDialog? = null

    init {
        fun label(text: String, size: Float = 16f) = TextView(activity).apply {
            this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)); root.addView(this)
        }
        fun row(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((text, action) in buttons) addView(Button(activity).apply {
                this.text = text; textSize = 17f; setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(this)
        }
        label("AI 金孫助理", 23f)
        label("用說的或打字，例如：「把剛拍的照片傳給女兒」、「手機突然沒聲音」、「這則訊息可信嗎」。")
        input = EditText(activity).apply { hint = "想請我幫什麼忙？"; textSize = 19f; root.addView(this) }
        row("🎤 用說的" to ::listen, "好，開始" to { run(input.text.toString()) })
        row("分享照片" to { run("把照片傳給") }, "手機救援" to { run("手機好像壞掉了") }, "檢查訊息" to { run("這則訊息可信嗎") })
        row("設定家人" to ::editBindings, "取消" to { cancel("已取消，沒有傳送任何東西") })
        status = label("", 19f)
        PhotoSanitizer.clear(activity)  // Leftover copies from an earlier session.
    }

    private fun say(text: String, color: Int = Color.DKGRAY) { status.text = text; status.setTextColor(color); onSay(text) }
    private fun ui(task: Int, action: () -> Unit) = activity.runOnUiThread {
        if (task == generation && !activity.isFinishing && !activity.isDestroyed) action()
    }
    private fun dialog() = AlertDialog.Builder(activity)
    private fun AlertDialog.Builder.showForTask() { openDialog = show().also(onDialog) }

    private fun newTask(): Int {
        generation++
        recipientName = null; relation = null; resumeShareAfterBinding = false; bindingRelation = null
        openDialog?.dismiss(); openDialog = null
        PhotoSanitizer.clear(activity)
        return generation
    }

    fun cancel(message: String) {
        newTask()
        say(message)
    }

    fun run(utterance: String) {
        val interpreted = CoreTasks.interpret(utterance)
        when (interpreted.intent) {
            "CANCEL" -> cancel("好，已取消")
            "SHARE_PHOTO" -> startShare(interpreted)
            "RESCUE_PHONE" -> startRescue(interpreted)
            "CHECK_MESSAGE" -> { newTask(); say("請在下方「可疑訊息檢查」貼上訊息，或從通知選一則。"); onCheckMessage() }
            "UNSUPPORTED" -> say("這件事我還在學。現在可以幫你：分享照片、檢查可疑訊息、手機救援。")
            else -> say("我還聽不懂。可以說：「把照片傳給女兒」、「手機沒聲音」或「這則訊息可信嗎」。")
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
                    if (task == generation) { pickContact(chosen); resumeShareAfterBinding = true }
                }
                .setNegativeButton("取消") { _, _ -> cancel("已取消") }.showForTask()
            // Ambiguity is resolved on the phone; the cloud never sees candidate names.
            names.size > 1 -> dialog().setTitle("要傳給哪一位$label？").setItems(names.toTypedArray()) { _, i ->
                ui(task) { recipientName = names[i]; choosePhoto(task) }
            }.setOnCancelListener { cancel("已取消") }.showForTask()
            else -> { recipientName = names[0]; choosePhoto(task) }
        }
    }

    private fun choosePhoto(task: Int) {
        if (selection == "latest") {
            val latest = latestPhoto()
            if (latest != null) { preparePhoto(task, latest); return }
        }
        say(when (selection) {
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
        say("正在準備照片（移除拍攝地點等資訊）…")
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
        val lineReady = lineInstalled()
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(ImageView(activity).apply { setImageBitmap(photo.thumbnail); adjustViewBounds = true; maxHeight = dp(260) })
            addView(TextView(activity).apply {
                textSize = 18f
                text = buildString {
                    append("要把這張照片傳給$label「$name」嗎？\n")
                    if (photo.hadLocation) append("・${CoreTasks.reasonText["LOCATION_REMOVED"]}\n")
                    append("・${CoreTasks.reasonText["PRIVATE_BACKGROUND_REVIEW"]}\n")
                    append(if (lineReady) "按「確認分享」後會打開 LINE，請在 LINE 選「$name」再按傳送。"
                        else "按「確認分享」後會打開分享選單，請選 App 和「$name」。")
                }
            })
        }
        dialog().setTitle("確認要分享").setView(view)
            .setPositiveButton("確認分享") { _, _ -> if (task == generation) planShare(task, photo, lineReady) }
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
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = recipientName
        try {
            activity.startActivity(if (lineReady) Intent(send).setPackage(LINE_PACKAGE) else Intent.createChooser(send, "分享照片"))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent.createChooser(send, "分享照片"))
        }
        // The copy must outlive the share screen; it is removed when the next task starts or on cancel.
        recipientName = null; relation = null
        say("已打開分享畫面，請選「$name」再按傳送。照片沒有拍攝地點資訊。\n判讀來源：$source", Color.rgb(27, 94, 32))
    }

    private fun lineInstalled() = try { activity.packageManager.getPackageInfo(LINE_PACKAGE, 0); true } catch (_: Exception) { false }

    // ------------------------------------------------------------ relation bindings

    private fun editBindings() {
        val keys = CoreTasks.relationLabel.keys.toList()
        val labels = keys.map { key ->
            val names = RelationBindings.names(activity, key)
            CoreTasks.relationLabel.getValue(key) + "：" + (if (names.isEmpty()) "未設定" else names.joinToString("、"))
        }.toTypedArray()
        dialog().setTitle("家人與常用聯絡人（只存在這支手機）").setItems(labels) { _, i ->
            val key = keys[i]
            dialog().setTitle(CoreTasks.relationLabel.getValue(key))
                .setPositiveButton("從通訊錄加一位") { _, _ -> pickContact(key) }
                .setNeutralButton("清除") { _, _ -> RelationBindings.clear(activity, key); say("已清除") }
                .setNegativeButton("返回", null).show()
        }.show()
    }

    private fun pickContact(key: String) {
        bindingRelation = key
        pickerTask = generation
        // The contact picker grants access to the one chosen contact; no contacts permission is needed.
        try { activity.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), REQ_CONTACT) }
        catch (_: ActivityNotFoundException) { say("找不到通訊錄 App") }
    }

    private fun onContactPicked(resultCode: Int, uri: Uri?) {
        val key = bindingRelation ?: return
        bindingRelation = null
        val name = if (resultCode == Activity.RESULT_OK && uri != null) try {
            activity.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null } else null
        if (name.isNullOrBlank()) { if (resumeShareAfterBinding) cancel("沒有選聯絡人，已取消"); return }
        RelationBindings.add(activity, key, name)
        say("已設定${CoreTasks.relationLabel.getValue(key)}：$name")
        if (resumeShareAfterBinding) { resumeShareAfterBinding = false; resolveRecipient(generation, key) }
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
        say(body, if (action != null) Color.rgb(230, 81, 0) else Color.DKGRAY)
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
                    say("已把鈴聲調到七成。如果還是聽不到，請再按一次「手機救援」。", Color.rgb(27, 94, 32))
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
        say("已打開設定頁，請照畫面調整；我不會自動幫你改。調好後可以再按一次「手機救援」確認。", Color.rgb(27, 94, 32))
    }
}
