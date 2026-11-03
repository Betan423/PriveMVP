package com.example.privagentcollector

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Spoken, no-touch final confirmation. Unclear, cancelled, or unavailable speech always aborts. */
class LineVoiceConfirmActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_SESSION_ID = "line_session_id"
        private const val REQ_CONFIRM = 7601
        private const val UTTERANCE = "line-confirm-prompt"
    }

    private var sessionId = -1L
    private var tts: TextToSpeech? = null
    private var recognizerStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val session = LineAutomationSession.snapshot()
        if (session == null || session.id != sessionId || session.phase != LineAutomationSession.Phase.WAIT_VOICE) {
            finish()
            return
        }
        val dp = resources.displayMetrics.density
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            addView(TextView(this@LineVoiceConfirmActivity).apply {
                text = getString(R.string.line_voice_confirm_screen)
                textSize = 25f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            })
            addView(Button(this@LineVoiceConfirmActivity).apply {
                text = "取消，不要傳"
                textSize = 20f
                setOnClickListener { abort("已取消，照片沒有傳送") }
            })
        })
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            startRecognizer()
            return
        }
        tts?.language = Locale.TAIWAN
        tts?.setSpeechRate(0.85f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) { runOnUiThread(::startRecognizer) }
            override fun onDone(utteranceId: String?) { runOnUiThread(::startRecognizer) }
        })
        tts?.speak("要把照片傳給這位家人嗎？請說，確認傳送。", TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    private fun startRecognizer() {
        if (recognizerStarted || isFinishing || isDestroyed) return
        recognizerStarted = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "請說「確認傳送」，或說「取消」")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        try { startActivityForResult(intent, REQ_CONFIRM) }
        catch (_: ActivityNotFoundException) { abort("這支手機沒有語音辨識，照片尚未傳送") }
    }

    @Deprecated("RecognizerIntent still returns through the Activity result API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIRM) return
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val decision = results.asSequence().map(LineAutomationRules::voiceDecision)
            .firstOrNull { it != LineAutomationRules.VoiceDecision.UNKNOWN }
            ?: LineAutomationRules.VoiceDecision.UNKNOWN
        when {
            resultCode == RESULT_OK && decision == LineAutomationRules.VoiceDecision.CONFIRM &&
                LineAutomationSession.confirm(sessionId) -> {
                Toast.makeText(this, "已確認，正在由 LINE 傳送", Toast.LENGTH_LONG).show()
                finish()
                LineShareAccessibilityService.resumeAfterVoice()
            }
            decision == LineAutomationRules.VoiceDecision.CANCEL -> abort("已取消，照片沒有傳送")
            else -> abort("沒有聽到明確的「確認傳送」，為避免傳錯已停止")
        }
    }

    private fun abort(message: String) {
        LineAutomationSession.abort(sessionId)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        val session = LineAutomationSession.snapshot()
        if (session?.id == sessionId && session.phase == LineAutomationSession.Phase.WAIT_VOICE)
            LineAutomationSession.abort(sessionId)
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
