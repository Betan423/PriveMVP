package com.example.privagentcollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * CHECK_MESSAGE on live notifications: every new message notification is checked in the background
 * and only a high-risk result interrupts the user. There is no per-message preview here, so the
 * redacted excerpt is never sent; the cloud (if consented) only receives the feature packet.
 */
object LiveMessageWatch {
    data class Result(val time: Long, val appLabel: String, val sender: String, val level: MessageGuard.Level, val source: String)

    private const val CHANNEL_ID = "message_alerts"
    private const val MAX_BODY_CHARS = 20_000
    private const val MAX_RECENT = 10
    private val seen = object : LinkedHashMap<Int, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>?) = size > 256
    }
    private val recent = ArrayDeque<Result>()
    // One check at a time (the cloud takes 5–15 s); a burst beyond the queue is rejected, not stalled.
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { r -> Thread(r, "live-message-check").apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }
    @Volatile private var observer = WeakReference<() -> Unit>(null)

    fun observe(callback: () -> Unit) { observer = WeakReference(callback) }
    @Synchronized fun recentResults(): List<Result> = recent.toList()

    /**
     * Picks the text of the newest message: the last MessagingStyle entry (chat apps keep earlier
     * lines there), else the expanded text, else the short text.
     */
    fun messageText(lastMessage: CharSequence?, bigText: CharSequence?, text: CharSequence?): String? =
        (lastMessage ?: bigText ?: text)?.toString()?.trim()?.take(MAX_BODY_CHARS)?.ifEmpty { null }

    /** True the first time this app/body pair is seen; chat apps re-post the same message on updates. */
    @Synchronized fun firstTime(packageName: String, body: String): Boolean =
        seen.put((packageName + "\u0000" + body).hashCode(), Unit) == null

    /** Undoes [firstTime] for a message that was never checked, so a later repost is checked. */
    @Synchronized fun forget(packageName: String, body: String) { seen.remove((packageName + "\u0000" + body).hashCode()) }

    fun shouldAlert(level: MessageGuard.Level) = level == MessageGuard.Level.HIGH

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (!AssistConfig.autoCheck(context)) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_CALL)) return
        if (PrivacyFilter.blocked(sbn.packageName, context.packageName, CollectionControl.exclusions(context))) return
        val extras = n.extras ?: return
        @Suppress("DEPRECATION")
        val last = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()
            ?.let { (it as? Bundle)?.getCharSequence("text") }
        val body = messageText(last, extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT)) ?: return
        if (!firstTime(sbn.packageName, body)) return
        val app = context.applicationContext
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(40)
        try { worker.execute { check(app, sbn.packageName, sbn.key, sender, body) } }
        catch (_: RejectedExecutionException) { forget(sbn.packageName, body) }
    }

    private fun check(context: Context, packageName: String, key: String, sender: String, body: String) {
        // The switch may have been turned off while this job waited in the queue.
        if (!AssistConfig.autoCheck(context)) return
        val cloud = AssistConfig.cloudConsent(context)
        val packet = MessageGuard.buildPacket(body, "notification", allowExcerpt = false)
        val outcome = AssistClient.check(AssistConfig.baseUrl(context), AssistConfig.token(context), packet, cloud)
        val appLabel = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) { packageName }
        synchronized(this) {
            recent.addFirst(Result(System.currentTimeMillis(), appLabel, sender, outcome.level, outcome.source))
            while (recent.size > MAX_RECENT) recent.removeLast()
        }
        observer.get()?.invoke()
        if (shouldAlert(outcome.level) && AssistConfig.autoCheck(context)) {
            alert(context, key, appLabel, sender, outcome)
            // With the floating button on, the overlay permission also lets the warning take the whole screen.
            if (FloatingAssistant.canPopup(context)) runCatching { ScamAlertActivity.show(context, appLabel, sender, outcome) }
        }
    }

    private fun alert(context: Context, key: String, appLabel: String, sender: String, outcome: AssistOutcome) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "可疑訊息提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "新進訊息被判定為高風險時提醒" })
        val text = buildString {
            append(MessageGuard.advice.getValue(outcome.level))
            for (code in outcome.reasonCodes) MessageGuard.reasonText[code]?.let { append("\n・").append(it) }
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_collector)
            .setContentTitle("小心！$appLabel" + (if (sender.isNotEmpty()) "「$sender」" else "") + "的訊息可能是詐騙")
            .setContentText("不要匯款、不要給驗證碼、不要點連結")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One alert per source notification: an updated copy of the same message replaces it.
        manager.notify(CHANNEL_ID, key.hashCode(), notification)
    }
}
