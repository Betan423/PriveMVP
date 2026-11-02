package com.example.privagentcollector

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit

/** Explicit local consent is independent from Android's system-level Accessibility switch. */
object LineAutomationSettings {
    private const val PREFS = "line_assistive_share"
    private const val CONSENT = "consented_v1"

    fun consented(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)

    fun setConsented(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(CONSENT, value) }
        if (!value) LineAutomationSession.abort()
    }

    fun ready(context: Context): Boolean = consented(context) && LineShareAccessibilityService.connected
}

/**
 * Volatile, one-shot capability for one recipient. Nothing is persisted or sent to the network.
 * The phase transitions enforce at most one recipient click and one Send click.
 */
object LineAutomationSession {
    const val TTL_MS = 120_000L

    enum class Phase { FIND_RECIPIENT, VERIFY_CONFIRM_SCREEN, WAIT_VOICE, CONFIRMED, SENDING }
    data class Snapshot(
        val id: Long,
        val recipient: String,
        val phase: Phase,
        val searched: Boolean,
        val startedElapsed: Long
    )

    private var nextId = 1L
    private var active: Snapshot? = null

    @Synchronized fun start(recipient: String): Long {
        val clean = recipient.trim()
        require(clean.isNotEmpty())
        val id = nextId++
        active = Snapshot(id, clean, Phase.FIND_RECIPIENT, searched = false, SystemClock.elapsedRealtime())
        return id
    }

    @Synchronized fun snapshot(): Snapshot? {
        val value = active ?: return null
        if (SystemClock.elapsedRealtime() - value.startedElapsed > TTL_MS) {
            active = null
            return null
        }
        return value
    }

    @Synchronized fun markSearched(id: Long): Boolean = update(id, Phase.FIND_RECIPIENT) {
        if (it.searched) null else it.copy(searched = true)
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
        if (active?.id != id || active?.phase != Phase.SENDING) return false
        active = null
        return true
    }

    @Synchronized fun abort(id: Long? = null): Boolean {
        if (active == null || (id != null && active?.id != id)) return false
        active = null
        return true
    }

    private fun transition(id: Long, from: Phase, to: Phase): Boolean = update(id, from) { it.copy(phase = to) }

    private fun update(id: Long, phase: Phase, change: (Snapshot) -> Snapshot?): Boolean {
        val value = active ?: return false
        if (value.id != id || value.phase != phase) return false
        val changed = change(value) ?: return false
        active = changed
        return true
    }
}
