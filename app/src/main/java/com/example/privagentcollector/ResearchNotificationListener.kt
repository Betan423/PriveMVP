package com.example.privagentcollector

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.lang.ref.WeakReference

class ResearchNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile private var service = WeakReference<ResearchNotificationListener>(null)
        fun connected() = service.get() != null
        fun snapshot(session: String): String {
            val instance = service.get() ?: return "listener_not_connected"
            return instance.snapshotCurrent(session)
        }
    }
    private val lastContent = object : LinkedHashMap<String, Int>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }
    private var lastSession: String? = null
    override fun onListenerConnected() {
        super.onListenerConnected()
        service = WeakReference(this)
    }
    override fun onListenerDisconnected() {
        if (service.get() === this) service.clear()
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        if (service.get() === this) service.clear()
        super.onDestroy()
    }
    @Synchronized private fun payload(sbn: StatusBarNotification, deduplicate: Boolean): JSONObject? {
        if (PrivacyFilter.blocked(sbn.packageName, packageName, CollectionControl.exclusions(this))) return null
        val session = CollectionControl.session()
        if (session != lastSession) { lastContent.clear(); lastSession = session }
        val n = sbn.notification
        val extras = n.extras
        val values = listOf("android.title", "android.text", "android.subText", "android.bigText")
            .map { extras?.getCharSequence(it)?.toString() }
        // OTP-like notifications retain metadata only, without any text fields.
        val secret = values.any(PrivacyFilter::containsSecret)
        val p = JSONObject().put("package", sbn.packageName).put("id", sbn.id)
            .put("key", sbn.key).put("postTime", sbn.postTime).put("category", n.category)
            .put("contentRedacted", secret)
        if (!secret) {
            listOf("title", "text", "subText", "bigText").forEachIndexed { i, name ->
                p.put(name, values[i]?.take(512) ?: JSONObject.NULL)
            }
        }
        p.remove("postTime")
        val hash = p.toString().hashCode()
        p.put("postTime", sbn.postTime)
        if (deduplicate && lastContent[sbn.key] == hash) return null
        lastContent[sbn.key] = hash
        return p
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val session = CollectionControl.session() ?: return
        if (!CollectionControl.allowed(this, DataSource.NOTIFICATIONS, session)) return
        val p = payload(sbn, true) ?: return
        CollectorRuntime.event(applicationContext, DataSource.NOTIFICATIONS, session) {
            ResearchLog.append(applicationContext, DataSource.NOTIFICATIONS, session, "notification_posted", p)
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val session = CollectionControl.session() ?: return
        if (!CollectionControl.allowed(this, DataSource.NOTIFICATIONS, session) ||
            PrivacyFilter.blocked(sbn.packageName, packageName, CollectionControl.exclusions(this))) return
        synchronized(this) { lastContent.remove(sbn.key) }
        val p = JSONObject().put("package", sbn.packageName).put("id", sbn.id).put("key", sbn.key)
        CollectorRuntime.event(applicationContext, DataSource.NOTIFICATIONS, session) {
            ResearchLog.append(applicationContext, DataSource.NOTIFICATIONS, session, "notification_removed", p)
        }
    }
    private fun snapshotCurrent(session: String): String {
        if (!CollectionControl.allowed(this, DataSource.NOTIFICATIONS, session)) return "disabled"
        val current = activeNotifications.orEmpty()
        var captured = 0
        for (notification in current.take(200)) {
            if (!CollectionControl.allowed(this, DataSource.NOTIFICATIONS, session)) return "stopped"
            val p = payload(notification, false) ?: continue
            if (ResearchLog.append(this, DataSource.NOTIFICATIONS, session, "notification_snapshot", p)) captured++
        }
        ResearchLog.append(this, DataSource.NOTIFICATIONS, session, "notification_snapshot_summary",
            JSONObject().put("status", if (captured == 0) "empty_or_filtered" else "ok")
                .put("captured", captured).put("truncated", current.size > 200))
        return if (captured == 0) "empty_or_filtered" else "ok"
    }
}
