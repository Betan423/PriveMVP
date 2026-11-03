package com.example.privagentcollector

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * A package-scoped, fail-closed LINE helper. It never takes screenshots, records UI text, uses
 * coordinates, or lets an AI choose actions. The only allowed state machine is:
 * per-session HMAC recipient match -> verified Send screen -> spoken confirmation -> one Send click.
 */
class LineShareAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var connected = false
            private set
        private var instance = WeakReference<LineShareAccessibilityService>(null)

        /** Called when the voice Activity closes so the already-visible LINE screen is re-checked. */
        fun resumeAfterVoice() {
            instance.get()?.handler?.postDelayed({ instance.get()?.processActiveWindow() }, 500)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessed = 0L
    private var unknownScreenTimeoutFor = -1L
    private var ignoreEventsUntil = 0L

    override fun onServiceConnected() {
        connected = true
        instance = WeakReference(this)
    }

    override fun onDestroy() {
        connected = false
        if (instance.get() === this) instance.clear()
        LineAutomationSession.abort()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        if (instance.get() === this) instance.clear()
        LineAutomationSession.abort()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        stopSession("LINE 免觸控服務中斷，已停止傳送")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != LineShare.PACKAGE_NAME) return
        if (!LineAutomationSettings.consented(this)) {
            LineAutomationSession.abort()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreEventsUntil) return
        if (now - lastProcessed < 180L) return
        lastProcessed = now
        processActiveWindow()
    }

    private fun processActiveWindow() {
        val session = LineAutomationSession.snapshot() ?: return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != LineShare.PACKAGE_NAME) return
            // Never treat a normal LINE chat (which also has a Send button and a person's name)
            // as the ACTION_SEND recipient screen.
            if (!containsShareScreenMarker(root)) {
                scheduleUnknownScreenTimeout(session.id)
                return
            }
            unknownScreenTimeoutFor = -1L
            when (session.phase) {
                LineAutomationSession.Phase.FIND_RECIPIENT -> findAndSelectRecipient(root, session)
                LineAutomationSession.Phase.VERIFY_CONFIRM_SCREEN -> verifyAndAsk(root, session)
                LineAutomationSession.Phase.CONFIRMED -> clickVerifiedSend(root, session)
                LineAutomationSession.Phase.WAIT_VOICE,
                LineAutomationSession.Phase.SENDING -> Unit
            }
        } finally { release(root) }
    }

    private fun findAndSelectRecipient(root: AccessibilityNodeInfo, session: LineAutomationSession.Snapshot) {
        val matches = actionNodes(root) { node ->
            node.isVisibleToUser && !node.isEditable &&
                (LineAutomationSession.matchesRecipient(session.id, node.text) ||
                    LineAutomationSession.matchesRecipient(session.id, node.contentDescription))
        }
        try {
            when {
                matches.size > 1 -> stopSession("找到多個同名 LINE 對象，為避免傳錯人已停止")
                matches.size == 1 -> {
                    if (!LineAutomationSession.beginRecipientClick(session.id)) return
                    if (!matches[0].performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        stopSession("無法安全選取 LINE 對象，已停止")
                    else handler.postDelayed(::processActiveWindow, 350L)
                }
                else -> scrollForRecipient(root, session)
            }
        } finally { matches.forEach(::release) }
    }

    private fun scrollForRecipient(root: AccessibilityNodeInfo, session: LineAutomationSession.Snapshot) {
        val visibleValues = mutableListOf<CharSequence?>()
        walk(root) { node ->
            if (node.isVisibleToUser) {
                visibleValues += node.text
                visibleValues += node.contentDescription
            }
        }
        val pageDigest = LineAutomationSession.pageFingerprint(session.id, visibleValues)
        visibleValues.clear()
        if (pageDigest == null) return
        val decision = try { LineAutomationSession.beginScroll(session.id, pageDigest) }
        finally { pageDigest.fill(0) }
        when (decision) {
            LineAutomationSession.ScrollDecision.SAME_PAGE -> {
                stopSession("LINE 名單已沒有繼續移動，找不到已設定的唯一對象，沒有按下任何人")
                return
            }
            LineAutomationSession.ScrollDecision.LIMIT -> {
                stopSession("已安全搜尋 ${LineAutomationSession.MAX_SCROLLS} 頁仍找不到對象，沒有按下任何人")
                return
            }
            LineAutomationSession.ScrollDecision.INVALID -> return
            LineAutomationSession.ScrollDecision.ALLOW -> Unit
        }

        val containers = matchingNodes(root) { node ->
            node.isVisibleToUser && node.isEnabled && node.isScrollable &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } &&
                isLikelyVerticalList(node)
        }
        try {
            val target = containers.maxByOrNull { node ->
                val rect = Rect().also(node::getBoundsInScreen)
                rect.width().toLong() * rect.height().toLong()
            }
            if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                stopSession("已搜尋目前可讀取的 LINE 名單，但找不到已設定的唯一對象，沒有按下任何人")
                return
            }
            // Some LINE versions emit a content event before the list has settled. Ignore that
            // transient event, then hash and inspect the next stable page.
            ignoreEventsUntil = SystemClock.elapsedRealtime() + 550L
            handler.postDelayed(::processActiveWindow, 700L)
        } finally { containers.forEach(::release) }
    }

    private fun verifyAndAsk(root: AccessibilityNodeInfo, session: LineAutomationSession.Snapshot) {
        if (!containsExactRecipient(root, session.id)) return
        val send = actionNodes(root) { node ->
            LineAutomationRules.isSendControl(node.text) || LineAutomationRules.isSendControl(node.contentDescription)
        }
        try {
            when {
                send.size > 1 -> stopSession("LINE 畫面有多個傳送按鈕，為避免誤按已停止")
                send.size == 1 && LineAutomationSession.beginVoiceConfirmation(session.id) -> {
                    val intent = android.content.Intent(this, LineVoiceConfirmActivity::class.java)
                        .putExtra(LineVoiceConfirmActivity.EXTRA_SESSION_ID, session.id)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    try { startActivity(intent) }
                    catch (_: Exception) { stopSession("無法啟動語音確認，照片尚未傳送") }
                }
            }
        } finally { send.forEach(::release) }
    }

    private fun clickVerifiedSend(root: AccessibilityNodeInfo, session: LineAutomationSession.Snapshot) {
        if (!containsExactRecipient(root, session.id)) {
            stopSession("送出前收件人已改變，已停止")
            return
        }
        val send = actionNodes(root) { node ->
            LineAutomationRules.isSendControl(node.text) || LineAutomationRules.isSendControl(node.contentDescription)
        }
        try {
            if (send.size != 1) {
                stopSession("送出前無法唯一確認 LINE 傳送按鈕，已停止")
                return
            }
            if (!LineAutomationSession.beginSendClick(session.id)) return
            if (!send[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                stopSession("LINE 沒有接受傳送操作，照片尚未送出")
                return
            }
            LineAutomationSession.complete(session.id)
            Toast.makeText(this, "已按下 LINE 傳送；請稍後確認聊天中出現照片", Toast.LENGTH_LONG).show()
            // LINE has already consumed the URI by this point; keep a short grace period for I/O.
            handler.postDelayed({ PhotoSanitizer.clear(applicationContext) }, 60_000L)
        } finally { send.forEach(::release) }
    }

    private fun stopSession(message: String) {
        unknownScreenTimeoutFor = -1L
        if (LineAutomationSession.abort()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun scheduleUnknownScreenTimeout(sessionId: Long) {
        if (unknownScreenTimeoutFor == sessionId) return
        unknownScreenTimeoutFor = sessionId
        handler.postDelayed({
            if (unknownScreenTimeoutFor != sessionId || LineAutomationSession.snapshot()?.id != sessionId)
                return@postDelayed
            val root = rootInActiveWindow
            if (root == null) {
                stopSession("無法讀取 LINE 分享畫面，已停止且沒有按下任何按鈕")
                return@postDelayed
            }
            try {
                if (root.packageName?.toString() != LineShare.PACKAGE_NAME || !containsShareScreenMarker(root))
                    stopSession("無法確認這是 LINE 分享對象畫面，已停止且沒有按下任何按鈕")
                else processActiveWindow()
            } finally { release(root) }
        }, 5_000L)
    }

    private fun containsExactRecipient(root: AccessibilityNodeInfo, sessionId: Long): Boolean {
        var found = false
        walk(root) { node ->
            if (node.isVisibleToUser &&
                (LineAutomationSession.matchesRecipient(sessionId, node.text) ||
                    LineAutomationSession.matchesRecipient(sessionId, node.contentDescription))) found = true
        }
        return found
    }

    private fun containsShareScreenMarker(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { node ->
            if (node.isVisibleToUser && (LineAutomationRules.isShareScreenMarker(node.text) ||
                LineAutomationRules.isShareScreenMarker(node.contentDescription))) found = true
        }
        return found
    }

    private fun isLikelyVerticalList(node: AccessibilityNodeInfo): Boolean {
        val type = node.className?.toString()?.lowercase().orEmpty()
        if (type.contains("recyclerview") || type.contains("listview") || type.contains("scrollview")) return true
        val rect = Rect().also(node::getBoundsInScreen)
        return rect.height() > rect.width()
    }

    /** Returns one copied clickable ancestor per screen rectangle, never raw screen text. */
    private fun actionNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = linkedMapOf<String, AccessibilityNodeInfo>()
        walk(root) { node ->
            if (!predicate(node)) return@walk
            val actionable = clickableAncestor(node) ?: return@walk
            val rect = Rect().also(actionable::getBoundsInScreen)
            val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}:${actionable.viewIdResourceName}"
            val prior = result.putIfAbsent(key, actionable)
            if (prior != null) release(actionable)
        }
        return result.values.toList()
    }

    private fun matchingNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { if (predicate(it)) result += copy(it) }
        return result
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = copy(node)
        repeat(6) {
            val value = current ?: return null
            if (value.isClickable && value.isEnabled) return value
            val parent = value.parent
            release(value)
            current = parent
        }
        current?.let(::release)
        return null
    }

    private fun walk(root: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        var budget = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (budget++ >= 400 || depth > 12) return
            visitor(node)
            for (i in 0 until minOf(node.childCount, 100)) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { release(child) }
            }
        }
        visit(root, 0)
    }

    @Suppress("DEPRECATION")
    private fun copy(node: AccessibilityNodeInfo): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain(node)

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }
}
