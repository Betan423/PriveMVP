package com.example.privagentcollector

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class ResearchAccessibilityService : AccessibilityService() {
    companion object { @Volatile var connected = false; private set }
    private var lastCaptured = -2000L
    private var lastHash = 0
    private var lastSession: String? = null
    override fun onServiceConnected() { connected = true }
    override fun onDestroy() { connected = false; super.onDestroy() }
    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val session = CollectionControl.session() ?: return
        if (!CollectionControl.allowed(this, DataSource.ACCESSIBILITY, session) || event.isPassword ||
            PrivacyFilter.blocked(event.packageName?.toString(), packageName, CollectionControl.exclusions(this))) return
        if (lastSession != session) { lastSession = session; lastHash = 0; lastCaptured = -2000L }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptured < 2000) return
        lastCaptured = now
        val p = JSONObject().put("eventType", AccessibilityEvent.eventTypeToString(event.eventType))
            .put("package", event.packageName?.toString()).put("className", event.className?.toString())
            .put("text", JSONArray(event.text.take(10).map { PrivacyFilter.text(it) }))
            .put("contentDescription", PrivacyFilter.text(event.contentDescription))
            .put("eventElapsedRealtimeMs", event.eventTime)
        val root = rootInActiveWindow
        if (root != null) {
            try {
                // Active window may have changed since the event was queued.
                if (PrivacyFilter.blocked(root.packageName?.toString(), packageName, CollectionControl.exclusions(this))) return
                val budget = Budget()
                p.put("activeWindowTree", nodeToJson(root, 0, budget))
                    .put("nodeCount", budget.count).put("treeTruncated", budget.truncated)
            } catch (_: Exception) {
                p.put("treeStatus", "unavailable")
            } finally { release(root) }
        } else p.put("treeStatus", "unavailable")
        p.remove("eventElapsedRealtimeMs")
        val hash = p.toString().hashCode()
        if (hash == lastHash) return
        lastHash = hash
        p.put("eventElapsedRealtimeMs", event.eventTime)
        CollectorRuntime.event(applicationContext, DataSource.ACCESSIBILITY, session) {
            ResearchLog.append(applicationContext, DataSource.ACCESSIBILITY, session, "accessibility_event", p)
        }
    }

    private class Budget(var count: Int = 0, var truncated: Boolean = false)
    private fun nodeToJson(node: AccessibilityNodeInfo, depth: Int, budget: Budget): JSONObject {
        budget.count++
        if (node.isPassword) return JSONObject().put("redacted", "password")
        val r = Rect()
        node.getBoundsInScreen(r)
        val o = JSONObject().put("className", node.className?.toString()).put("viewId", node.viewIdResourceName)
            .put("text", PrivacyFilter.text(node.text)).put("contentDescription", PrivacyFilter.text(node.contentDescription))
            .put("clickable", node.isClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
            .put("selected", node.isSelected).put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
        if (depth >= 6 || budget.count >= 200) {
            if (node.childCount > 0) budget.truncated = true
            return o
        }
        val children = JSONArray()
        val limit = minOf(node.childCount, 80)
        if (node.childCount > limit) budget.truncated = true
        for (i in 0 until limit) {
            if (budget.count >= 200) { budget.truncated = true; break }
            val child = node.getChild(i) ?: continue
            try { children.put(nodeToJson(child, depth + 1, budget)) }
            finally { release(child) }
        }
        return o.put("children", children)
    }
    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }
}
