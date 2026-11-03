package com.example.privagentcollector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.lang.ref.WeakReference

enum class LineChatForwardStart {
    STARTED, CANCELLED, NOT_LINE, NOT_LINE_CHAT, NOT_READY, NEED_DAUGHTER, NEED_LINE_NAME
}

/**
 * A package-scoped, fail-closed LINE helper. It never takes screenshots, records UI text, or lets
 * an AI choose actions. One coordinate long-press is allowed only after the full-screen overlay
 * consumes the user's explicit target tap. All later controls are exact semantic-node matches.
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

        /** Called by the floating bubble; no Activity or broad screen capture is involved. */
        fun beginChatImageForward(): LineChatForwardStart =
            instance.get()?.beginChatImageForwardInternal() ?: LineChatForwardStart.NOT_READY

        /** The overlay has already consumed this tap, so LINE itself has not received it. */
        fun submitChatImagePoint(x: Int, y: Int): Boolean {
            val service = instance.get() ?: return false
            if (LineChatForwardSession.snapshot()?.phase !=
                LineChatForwardSession.Phase.WAIT_TARGET_POINT) return false
            service.handler.post { service.handleOverlayPoint(x, y) }
            return true
        }

        fun cancelChatImageForward() {
            instance.get()?.handler?.post {
                instance.get()?.stopForwardSession("已取消 LINE 圖片選取，沒有轉傳")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessed = 0L
    private var unknownScreenTimeoutFor = -1L
    private var ignoreEventsUntil = 0L
    private var forwardControlDeadline = 0L
    private var lastForwardProcessed = 0L
    private var forwardUiSessionId = -1L

    private enum class ShareAttempt { CLICKED, NONE, AMBIGUOUS, FAILED }

    override fun onServiceConnected() {
        connected = true
        instance = WeakReference(this)
    }

    override fun onDestroy() {
        connected = false
        if (instance.get() === this) instance.clear()
        LineAutomationSession.abort()
        LineChatForwardSession.abort()
        FloatingBubbleService.setImageSelectionMode(false)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        if (instance.get() === this) instance.clear()
        LineAutomationSession.abort()
        LineChatForwardSession.abort()
        FloatingBubbleService.setImageSelectionMode(false)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        stopForwardSession("LINE 圖片選取服務中斷，已停止")
        stopSession("LINE 免觸控服務中斷，已停止傳送")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != LineShare.PACKAGE_NAME) return
        if (!LineAutomationSettings.consented(this)) {
            LineAutomationSession.abort()
            LineChatForwardSession.abort()
            return
        }
        val forward = LineChatForwardSession.snapshot()
        if (forward != null) {
            if (forward.phase == LineChatForwardSession.Phase.WAIT_TARGET_POINT) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastForwardProcessed < 120L) return
            lastForwardProcessed = now
            processForwardWindow(forward)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreEventsUntil) return
        if (now - lastProcessed < 180L) return
        lastProcessed = now
        processActiveWindow()
    }

    private fun beginChatImageForwardInternal(): LineChatForwardStart {
        if (!LineAutomationSettings.consented(this)) return LineChatForwardStart.NOT_READY
        if (LineChatForwardSession.snapshot() != null) {
            stopForwardSession("已取消目前的 LINE 圖片選取")
            return LineChatForwardStart.CANCELLED
        }
        if (LineAutomationSession.snapshot() != null) return LineChatForwardStart.NOT_READY
        val daughters = RelationBindings.names(this, "daughter")
        if (daughters.size != 1) return LineChatForwardStart.NEED_DAUGHTER
        val lineName = RelationBindings.lineName(this, "daughter", daughters[0])
            ?: return LineChatForwardStart.NEED_LINE_NAME
        val root = rootInActiveWindow ?: return LineChatForwardStart.NOT_LINE
        try {
            if (root.packageName?.toString() != LineShare.PACKAGE_NAME) return LineChatForwardStart.NOT_LINE
            if (containsShareScreenMarker(root) || !containsChatComposer(root))
                return LineChatForwardStart.NOT_LINE_CHAT
        } finally { release(root) }
        val id = LineChatForwardSession.start(lineName)
        forwardUiSessionId = id
        LineAutomationSession.abort()
        FloatingBubbleService.setImageSelectionMode(true)
        Toast.makeText(this, "請點一下要轉傳給女兒的圖片", Toast.LENGTH_LONG).show()
        handler.postDelayed({
            if (forwardUiSessionId == id) {
                stopForwardSession("等候選擇圖片逾時，已停止且沒有轉傳")
            }
        }, LineChatForwardSession.TTL_MS + 200L)
        return LineChatForwardStart.STARTED
    }

    private fun handleOverlayPoint(x: Int, y: Int) {
        val session = LineChatForwardSession.snapshot() ?: return
        if (session.phase != LineChatForwardSession.Phase.WAIT_TARGET_POINT) return
        val root = rootInActiveWindow
        try {
            if (root == null || root.packageName?.toString() != LineShare.PACKAGE_NAME ||
                containsShareScreenMarker(root) || !containsChatComposer(root)) {
                stopForwardSession("LINE 聊天畫面已改變，沒有執行長按")
                return
            }
            if (!containsPlausibleImageAtPoint(root, x, y)) {
                FloatingBubbleService.setImageSelectionMode(true)
                Toast.makeText(this, "沒有點到可確認的圖片，請再點一次", Toast.LENGTH_LONG).show()
                return
            }
        } finally { root?.let(::release) }

        val metrics = resources.displayMetrics
        if (!LineChatForwardSession.selectPoint(
                session.id, x, y, metrics.widthPixels, metrics.heightPixels)) {
            stopForwardSession("點擊位置不在可操作的畫面範圍，已停止")
            return
        }
        FloatingBubbleService.setImageSelectionMode(false)
        armForwardDeadline()

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            // A tiny non-empty stroke remains at the user-selected point and never becomes a swipe.
            lineTo(x + 0.1f, y + 0.1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 750L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({
                    LineChatForwardSession.snapshot()?.takeIf { it.id == session.id }
                        ?.let(::processForwardWindow)
                }, 300L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                stopForwardSession("LINE 圖片長按被系統取消，沒有轉傳")
            }
        }, handler)
        if (!accepted) stopForwardSession("系統無法執行 LINE 圖片長按，沒有轉傳")
    }

    private fun processForwardWindow(session: LineChatForwardSession.Snapshot) {
        if (session.phase == LineChatForwardSession.Phase.WAIT_TARGET_POINT) return
        val roots = currentLineWindowRoots()
        if (roots.isEmpty()) return continueForwardOrTimeout(session)
        try {
            if (session.phase == LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN) {
                if (roots.any(::containsShareScreenMarker)) startRecipientAutomation(session.id)
                else continueForwardOrTimeout(session)
                return
            }

            val attempt = when (session.phase) {
                LineChatForwardSession.Phase.FIND_CONTEXT_SHARE -> clickForwardControl(
                    roots, session.id,
                    LineChatForwardSession.Phase.FIND_CONTEXT_SHARE,
                    LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE,
                    LineAutomationRules::isMessageShareControl
                )
                LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE -> {
                    if (!roots.any(::containsMessageSelectionMarker)) ShareAttempt.NONE
                    else clickForwardControl(
                        roots, session.id,
                        LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE,
                        LineChatForwardSession.Phase.FIND_MORE,
                        LineAutomationRules::isSelectedMessageShareControl
                    )
                }
                LineChatForwardSession.Phase.FIND_MORE -> {
                    if (roots.any(::containsShareScreenMarker)) {
                        if (LineChatForwardSession.advance(
                                session.id,
                                LineChatForwardSession.Phase.FIND_MORE,
                                LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN))
                            startRecipientAutomation(session.id)
                        ShareAttempt.CLICKED
                    } else clickForwardControl(
                        roots, session.id,
                        LineChatForwardSession.Phase.FIND_MORE,
                        LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN,
                        LineAutomationRules::isMoreControl
                    )
                }
                LineChatForwardSession.Phase.WAIT_TARGET_POINT,
                LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN -> ShareAttempt.NONE
            }
            when (attempt) {
                ShareAttempt.CLICKED -> Unit
                ShareAttempt.AMBIGUOUS ->
                    stopForwardSession("目前 LINE 階段有多個相同按鈕，已停止且沒有繼續點擊")
                ShareAttempt.FAILED -> stopForwardSession("LINE 沒有接受目前的分享操作，已停止")
                ShareAttempt.NONE -> continueForwardOrTimeout(session)
            }
        } finally { roots.forEach(::release) }
    }

    private fun clickForwardControl(
        roots: List<AccessibilityNodeInfo>,
        sessionId: Long,
        from: LineChatForwardSession.Phase,
        to: LineChatForwardSession.Phase,
        predicate: (CharSequence?) -> Boolean
    ): ShareAttempt {
        val textControls = distinctActionNodes(roots.flatMap { root ->
            actionNodes(root) { node -> node.isVisibleToUser && predicate(node.text) }
        })
        val controls = if (textControls.isNotEmpty()) textControls else distinctActionNodes(roots.flatMap { root ->
            actionNodes(root) { node -> node.isVisibleToUser && predicate(node.contentDescription) }
        })
        try {
            if (controls.isEmpty()) return ShareAttempt.NONE
            if (controls.size != 1) return ShareAttempt.AMBIGUOUS
            if (!LineChatForwardSession.advance(sessionId, from, to)) return ShareAttempt.FAILED
            if (!controls[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ShareAttempt.FAILED
            armForwardDeadline()
            handler.postDelayed({
                LineChatForwardSession.snapshot()?.takeIf { it.id == sessionId }
                    ?.let(::processForwardWindow)
            }, 350L)
            return ShareAttempt.CLICKED
        } finally {
            controls.forEach(::release)
            if (controls !== textControls) textControls.forEach(::release)
        }
    }

    /** Deduplicates the same actionable rectangle if LINE exposes it through two window roots. */
    private fun distinctActionNodes(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val unique = linkedMapOf<String, AccessibilityNodeInfo>()
        nodes.forEach { node ->
            val rect = Rect().also(node::getBoundsInScreen)
            val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}:${node.viewIdResourceName}"
            if (unique.putIfAbsent(key, node) != null) release(node)
        }
        return unique.values.toList()
    }

    /**
     * LINE renders the black long-press menu and quick-share sheet as independent interactive
     * windows on some versions. Searching only rootInActiveWindow sees the chat but misses those
     * controls, so every LINE-owned window root is inspected and released after each pass.
     */
    private fun currentLineWindowRoots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seenWindowIds = mutableSetOf<Int>()
        windows.forEach { window ->
            if (!seenWindowIds.add(window.id)) return@forEach
            val root = window.root ?: return@forEach
            if (root.packageName?.toString() == LineShare.PACKAGE_NAME) result += root
            else release(root)
        }
        if (result.isEmpty()) {
            val active = rootInActiveWindow
            if (active != null) {
                if (active.packageName?.toString() == LineShare.PACKAGE_NAME) result += active
                else release(active)
            }
        }
        return result
    }

    private fun containsMessageSelectionMarker(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { node ->
            if (node.isVisibleToUser &&
                (LineAutomationRules.isMessageSelectionScreenMarker(node.text) ||
                    LineAutomationRules.isMessageSelectionScreenMarker(node.contentDescription))) found = true
        }
        return found
    }

    private fun armForwardDeadline() {
        forwardControlDeadline = SystemClock.elapsedRealtime() + 5_000L
    }

    private fun continueForwardOrTimeout(session: LineChatForwardSession.Snapshot) {
        if (SystemClock.elapsedRealtime() >= forwardControlDeadline) {
            val message = when (session.phase) {
                LineChatForwardSession.Phase.FIND_CONTEXT_SHARE -> "找不到長按選單中的唯一「分享」按鈕，已停止"
                LineChatForwardSession.Phase.FIND_SELECTED_MESSAGE_SHARE -> "找不到「選擇訊息」底部的「分享(1)」，已停止"
                LineChatForwardSession.Phase.FIND_MORE -> "找不到快速分享面板中的唯一「更多」，已停止"
                LineChatForwardSession.Phase.WAIT_RECIPIENT_SCREEN -> "LINE 沒有開啟「選擇傳送對象」畫面，已停止"
                LineChatForwardSession.Phase.WAIT_TARGET_POINT -> "等候圖片位置逾時，已停止"
            }
            stopForwardSession(message)
            return
        }
        handler.postDelayed({
            LineChatForwardSession.snapshot()?.takeIf { it.id == session.id }
                ?.let(::processForwardWindow)
        }, 250L)
    }

    private fun startRecipientAutomation(forwardId: Long) {
        val recipient = LineChatForwardSession.consumeRecipient(forwardId) ?: return
        forwardUiSessionId = -1L
        FloatingBubbleService.setImageSelectionMode(false)
        LineAutomationSession.start(recipient)
        handler.postDelayed(::processActiveWindow, 500L)
        handler.postDelayed({
            val active = LineAutomationSession.snapshot() ?: return@postDelayed
            if (active.phase != LineAutomationSession.Phase.FIND_RECIPIENT) return@postDelayed
            val root = rootInActiveWindow
            try {
                if (root == null || root.packageName?.toString() != LineShare.PACKAGE_NAME)
                    stopSession("LINE 沒有開啟傳送對象畫面，已停止")
            } finally { root?.let(::release) }
        }, 2_000L)
    }

    private fun stopForwardSession(message: String) {
        forwardControlDeadline = 0L
        val hadUi = forwardUiSessionId >= 0L
        forwardUiSessionId = -1L
        if (LineChatForwardSession.abort() || hadUi) {
            FloatingBubbleService.setImageSelectionMode(false)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun containsChatComposer(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { node -> if (node.isVisibleToUser && node.isEditable) found = true }
        return found
    }

    private fun containsPlausibleImageAtPoint(root: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        var found = false
        walk(root) { node ->
            if (found || !node.isVisibleToUser || !node.isEnabled) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            val metrics = resources.displayMetrics
            if (rect.contains(x, y) && LineAutomationRules.isPlausibleChatImage(
                    node.className, node.text, node.contentDescription,
                    rect.width(), rect.height(), metrics.widthPixels, metrics.heightPixels)) found = true
        }
        return found
    }

    private fun processActiveWindow() {
        val session = LineAutomationSession.snapshot() ?: return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != LineShare.PACKAGE_NAME) return
            // Never treat a normal LINE chat (which also has a Send button and a person's name)
            // as the ACTION_SEND recipient screen.
            if (!containsTrustedShareScreen(root, session.phase)) {
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

        val containers = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        walk(root) { node ->
            if (!node.isVisibleToUser || !node.isEnabled || !node.isScrollable) return@walk
            val actionIds = node.actionList.mapTo(mutableSetOf()) { it.id }
            val collection = node.collectionInfo
            val choice = LineAutomationRules.verticalScrollChoice(
                hasDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in actionIds,
                hasLeft = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actionIds,
                hasRight = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actionIds,
                hasForward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds,
                rowCount = collection?.rowCount,
                columnCount = collection?.columnCount
            )
            val action = when (choice) {
                LineAutomationRules.ScrollChoice.DOWN ->
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                LineAutomationRules.ScrollChoice.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                LineAutomationRules.ScrollChoice.NONE -> return@walk
            }
            containers += copy(node) to action
        }
        try {
            // A LINE ViewPager can contain the actual vertical chat list. Prefer the deeper visual
            // region (larger top edge) instead of the larger outer pager rectangle.
            val target = containers.maxByOrNull { (node, _) ->
                Rect().also(node::getBoundsInScreen).top
            }
            if (target == null || !target.first.performAction(target.second)) {
                stopSession("已搜尋目前可讀取的 LINE 名單，但找不到已設定的唯一對象，沒有按下任何人")
                return
            }
            // Some LINE versions emit a content event before the list has settled. Ignore that
            // transient event, then hash and inspect the next stable page.
            ignoreEventsUntil = SystemClock.elapsedRealtime() + 550L
            handler.postDelayed(::processActiveWindow, 700L)
        } finally { containers.forEach { (node, _) -> release(node) } }
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
            val session = LineAutomationSession.snapshot()
            if (unknownScreenTimeoutFor != sessionId || session?.id != sessionId)
                return@postDelayed
            val root = rootInActiveWindow
            if (root == null) {
                stopSession("無法讀取 LINE 分享畫面，已停止且沒有按下任何按鈕")
                return@postDelayed
            }
            try {
                if (root.packageName?.toString() != LineShare.PACKAGE_NAME ||
                    !containsTrustedShareScreen(root, session.phase))
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

    private fun containsSelectedShareScreenMarker(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { node ->
            if (node.isVisibleToUser && (LineAutomationRules.isSelectedShareScreenMarker(node.text) ||
                LineAutomationRules.isSelectedShareScreenMarker(node.contentDescription))) found = true
        }
        return found
    }

    /**
     * The selected-recipient title is trusted only after the one-shot state machine has already
     * clicked a unique HMAC-matched row on the original, verified recipient screen.
     */
    private fun containsTrustedShareScreen(
        root: AccessibilityNodeInfo,
        phase: LineAutomationSession.Phase
    ): Boolean {
        if (containsShareScreenMarker(root)) return true
        return phase != LineAutomationSession.Phase.FIND_RECIPIENT &&
            containsSelectedShareScreenMarker(root)
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
            if (value.isEnabled && (value.isClickable ||
                    value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) return value
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
