package com.example.privagentcollector

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Explicit local consent is independent from Android's system-level Accessibility switch. */
object LineAutomationSettings {
    private const val PREFS = "line_assistive_share"
    private const val CONSENT = "consented_v1"

    fun consented(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)

    fun setConsented(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(CONSENT, value) }
        if (!value) {
            LineAutomationSession.abort()
            LineChatForwardSession.abort()
        }
    }

    fun ready(context: Context): Boolean = consented(context) && LineShareAccessibilityService.connected
}

/**
 * Short-lived capability used before LINE opens its recipient screen. The user must first tap the
 * floating button and then one image; only that image's semantic node may lead to a Share action.
 */
object LineChatForwardSession {
    const val TTL_MS = 60_000L

    enum class Phase { WAIT_IMAGE_TAP, FIND_SHARE_CONTROL }
    data class Snapshot(val id: Long, val phase: Phase, val imageBounds: Rect?, val startedElapsed: Long)
    private data class Active(val snapshot: Snapshot, val recipient: String)

    private var nextId = 1L
    private var active: Active? = null
    private val expiryHandler = Handler(Looper.getMainLooper())

    @Synchronized fun start(recipient: String): Long {
        require(recipient.isNotBlank())
        active = null
        val id = nextId++
        active = Active(Snapshot(id, Phase.WAIT_IMAGE_TAP, null, SystemClock.elapsedRealtime()), recipient.trim())
        expiryHandler.postDelayed({ expire(id) }, TTL_MS + 100L)
        return id
    }

    @Synchronized fun snapshot(): Snapshot? {
        val value = active ?: return null
        if (SystemClock.elapsedRealtime() - value.snapshot.startedElapsed > TTL_MS) {
            active = null
            return null
        }
        return value.snapshot.copy(imageBounds = value.snapshot.imageBounds?.let(::Rect))
    }

    @Synchronized fun selectImage(id: Long, bounds: Rect): Boolean {
        val value = active ?: return false
        if (value.snapshot.id != id || value.snapshot.phase != Phase.WAIT_IMAGE_TAP || bounds.isEmpty) return false
        active = value.copy(snapshot = value.snapshot.copy(phase = Phase.FIND_SHARE_CONTROL, imageBounds = Rect(bounds)))
        return true
    }

    @Synchronized fun recipient(id: Long): String? =
        active?.takeIf { it.snapshot.id == id }?.recipient

    @Synchronized fun consumeRecipient(id: Long): String? {
        val value = active ?: return null
        if (value.snapshot.id != id || value.snapshot.phase != Phase.FIND_SHARE_CONTROL) return null
        active = null
        return value.recipient
    }

    @Synchronized fun abort(id: Long? = null): Boolean {
        if (active == null || (id != null && active?.snapshot?.id != id)) return false
        active = null
        return true
    }

    @Synchronized private fun expire(id: Long) {
        val value = active ?: return
        if (value.snapshot.id == id &&
            SystemClock.elapsedRealtime() - value.snapshot.startedElapsed >= TTL_MS) active = null
    }
}

/**
 * Volatile, one-shot capability for one recipient. Nothing is persisted or sent to the network.
 * The phase transitions enforce at most one recipient click and one Send click.
 */
object LineAutomationSession {
    const val TTL_MS = 120_000L
    const val MAX_SCROLLS = 24

    enum class Phase { FIND_RECIPIENT, VERIFY_CONFIRM_SCREEN, WAIT_VOICE, CONFIRMED, SENDING }
    enum class ScrollDecision { ALLOW, SAME_PAGE, LIMIT, INVALID }

    /** Public state deliberately contains neither the LINE name nor the HMAC key/digest. */
    data class Snapshot(
        val id: Long,
        val phase: Phase,
        val scrollCount: Int,
        val startedElapsed: Long
    )

    private data class Active(
        val snapshot: Snapshot,
        val hmacKey: ByteArray,
        val recipientDigest: ByteArray,
        val lastPageDigest: ByteArray? = null
    )

    private var nextId = 1L
    private var active: Active? = null
    private val expiryHandler = Handler(Looper.getMainLooper())

    @Synchronized fun start(recipient: String): Long {
        val normalized = LineAutomationRules.normalize(recipient)
        require(normalized.isNotEmpty())
        clearActive()
        val id = nextId++
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val target = keyedDigest(key, "recipient", normalized)
        active = Active(
            Snapshot(id, Phase.FIND_RECIPIENT, scrollCount = 0, SystemClock.elapsedRealtime()),
            key,
            target
        )
        expiryHandler.postDelayed({ expire(id) }, TTL_MS + 100L)
        return id
    }

    @Synchronized fun snapshot(): Snapshot? {
        val value = active ?: return null
        if (SystemClock.elapsedRealtime() - value.snapshot.startedElapsed > TTL_MS) {
            clearActive()
            return null
        }
        return value.snapshot
    }

    /** Exact local equality through per-session HMAC; raw names and raw hashes are never retained. */
    @Synchronized fun matchesRecipient(id: Long, value: CharSequence?): Boolean {
        val current = active ?: return false
        if (current.snapshot.id != id || current.snapshot.phase !in setOf(
                Phase.FIND_RECIPIENT, Phase.VERIFY_CONFIRM_SCREEN, Phase.CONFIRMED
            )) return false
        val normalized = LineAutomationRules.normalize(value)
        if (normalized.isEmpty()) return false
        val candidate = keyedDigest(current.hmacKey, "recipient", normalized)
        return try { MessageDigest.isEqual(current.recipientDigest, candidate) }
        finally { candidate.fill(0) }
    }

    /** HMACs a transient visible-page description for loop/end detection without retaining UI text. */
    @Synchronized fun pageFingerprint(id: Long, values: List<CharSequence?>): ByteArray? {
        val current = active ?: return null
        if (current.snapshot.id != id || current.snapshot.phase != Phase.FIND_RECIPIENT) return null
        val mac = newMac(current.hmacKey)
        mac.update("page\u0000".toByteArray(StandardCharsets.UTF_8))
        values.forEach { value ->
            val normalized = LineAutomationRules.normalize(value)
            if (normalized.isNotEmpty()) {
                mac.update(normalized.toByteArray(StandardCharsets.UTF_8))
                mac.update(0)
            }
        }
        return mac.doFinal()
    }

    @Synchronized fun beginScroll(id: Long, pageDigest: ByteArray): ScrollDecision {
        val current = active ?: return ScrollDecision.INVALID
        val state = current.snapshot
        if (state.id != id || state.phase != Phase.FIND_RECIPIENT) return ScrollDecision.INVALID
        if (current.lastPageDigest?.let { MessageDigest.isEqual(it, pageDigest) } == true)
            return ScrollDecision.SAME_PAGE
        if (state.scrollCount >= MAX_SCROLLS) return ScrollDecision.LIMIT
        current.lastPageDigest?.fill(0)
        active = current.copy(
            snapshot = state.copy(scrollCount = state.scrollCount + 1),
            lastPageDigest = pageDigest.copyOf()
        )
        return ScrollDecision.ALLOW
    }

    @Synchronized fun beginRecipientClick(id: Long): Boolean = transition(
        id, Phase.FIND_RECIPIENT, Phase.VERIFY_CONFIRM_SCREEN
    )

    @Synchronized fun beginVoiceConfirmation(id: Long): Boolean = transition(
        id, Phase.VERIFY_CONFIRM_SCREEN, Phase.WAIT_VOICE
    )

    @Synchronized fun confirm(id: Long): Boolean = transition(id, Phase.WAIT_VOICE, Phase.CONFIRMED)

    @Synchronized fun beginSendClick(id: Long): Boolean = transition(id, Phase.CONFIRMED, Phase.SENDING)

    @Synchronized fun complete(id: Long): Boolean {
        if (active?.snapshot?.id != id || active?.snapshot?.phase != Phase.SENDING) return false
        clearActive()
        return true
    }

    @Synchronized fun abort(id: Long? = null): Boolean {
        if (active == null || (id != null && active?.snapshot?.id != id)) return false
        clearActive()
        return true
    }

    @Synchronized private fun expire(id: Long) {
        val current = active ?: return
        if (current.snapshot.id == id &&
            SystemClock.elapsedRealtime() - current.snapshot.startedElapsed >= TTL_MS) clearActive()
    }

    private fun transition(id: Long, from: Phase, to: Phase): Boolean = update(id, from) { it.copy(phase = to) }

    private fun update(id: Long, phase: Phase, change: (Snapshot) -> Snapshot?): Boolean {
        val current = active ?: return false
        val value = current.snapshot
        if (value.id != id || value.phase != phase) return false
        val changed = change(value) ?: return false
        active = current.copy(snapshot = changed)
        return true
    }

    private fun keyedDigest(key: ByteArray, purpose: String, normalized: String): ByteArray {
        val mac = newMac(key)
        mac.update(purpose.toByteArray(StandardCharsets.UTF_8))
        mac.update(0)
        mac.update(normalized.toByteArray(StandardCharsets.UTF_8))
        return mac.doFinal()
    }

    private fun newMac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }

    private fun clearActive() {
        active?.let {
            it.hmacKey.fill(0)
            it.recipientDigest.fill(0)
            it.lastPageDigest?.fill(0)
        }
        active = null
    }
}
