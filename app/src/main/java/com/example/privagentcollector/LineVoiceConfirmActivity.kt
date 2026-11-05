package com.example.privagentcollector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Spoken, no-touch final confirmation with an explicit manual fail-safe when ASR is unavailable. */
class LineVoiceConfirmActivity : Activity(), TextToSpeech.OnInitListener, RecognitionListener {
    companion object {
        const val EXTRA_SESSION_ID = "line_session_id"
        private const val REQ_AUDIO = 7601
        private const val UTTERANCE = "line-confirm-prompt"
    }

    private var sessionId = -1L
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognizerStarted = false
    private lateinit var voiceStatus: TextView
    private lateinit var retryButton: Button

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
            voiceStatus = TextView(this@LineVoiceConfirmActivity).apply {
                text = "正在準備語音辨識…"
                textSize = 18f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            }
            addView(voiceStatus)
            retryButton = Button(this@LineVoiceConfirmActivity).apply {
                text = "再試一次語音辨識"
                textSize = 19f
                visibility = Button.GONE
                setOnClickListener { startRecognizer() }
            }
            addView(retryButton)
            addView(Button(this@LineVoiceConfirmActivity).apply {
                text = "確認傳送"
                textSize = 21f
                setOnClickListener { confirmAndResume("已手動確認，正在由 LINE 傳送") }
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
        val active = LineAutomationSession.snapshot()
        if (active?.id != sessionId || active.phase != LineAutomationSession.Phase.WAIT_VOICE) {
            Toast.makeText(this, "確認工作階段已逾時，照片沒有傳送", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus.text = "第一次使用需要允許麥克風，才能辨識「確認傳送」"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }

        destroyRecognizer()
        val onDevice = Build.VERSION.SDK_INT >= 31 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        recognizer = try {
            when {
                onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                SpeechRecognizer.isRecognitionAvailable(this) -> SpeechRecognizer.createSpeechRecognizer(this)
                else -> null
            }
        } catch (_: Exception) { null }
        val service = recognizer
        if (service == null) {
            showManualFallback("手機目前沒有可用的語音辨識服務")
            return
        }
        service.setRecognitionListener(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        recognizerStarted = true
        retryButton.visibility = Button.GONE
        voiceStatus.text = if (onDevice) "正在使用手機內建離線辨識，請說「確認傳送」"
            else "正在聆聽，請說「確認傳送」或「取消」"
        try { service.startListening(intent) }
        catch (_: Exception) {
            recognizerStarted = false
            showManualFallback("語音辨識無法啟動")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_AUDIO) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecognizer()
        else showManualFallback("未允許麥克風；可以使用下方按鈕確認")
    }

    private fun handleResults(results: List<String>) {
        val decision = results.asSequence().map(LineAutomationRules::voiceDecision)
            .firstOrNull { it != LineAutomationRules.VoiceDecision.UNKNOWN }
            ?: LineAutomationRules.VoiceDecision.UNKNOWN
        when {
            decision == LineAutomationRules.VoiceDecision.CONFIRM ->
                confirmAndResume("已聽到確認傳送，正在由 LINE 傳送")
            decision == LineAutomationRules.VoiceDecision.CANCEL -> abort("已取消，照片沒有傳送")
            else -> showManualFallback("沒有聽到明確的「確認傳送」；尚未送出")
        }
    }

    private fun confirmAndResume(message: String) {
        if (!LineAutomationSession.confirm(sessionId)) {
            Toast.makeText(this, "確認工作階段已逾時，照片沒有傳送", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
        LineShareAccessibilityService.resumeAfterVoice()
    }

    private fun showManualFallback(reason: String) {
        recognizerStarted = false
        voiceStatus.text = getString(R.string.line_voice_manual_fallback, reason)
        retryButton.visibility = Button.VISIBLE
    }

    private fun abort(message: String) {
        LineAutomationSession.abort(sessionId)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) { voiceStatus.text = "請說「確認傳送」或「取消」" }
    override fun onBeginningOfSpeech() { voiceStatus.text = "正在聆聽…" }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { voiceStatus.text = "正在辨識…" }
    override fun onError(error: Int) {
        recognizerStarted = false
        val reason = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "沒有辨識到明確句子"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "沒有聽到語音"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麥克風權限不足"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "語音服務目前無法連線"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音服務忙碌中"
            else -> "語音辨識暫時無法使用（錯誤 $error）"
        }
        showManualFallback(reason)
    }
    override fun onResults(results: Bundle?) {
        recognizerStarted = false
        handleResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
    }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        val session = LineAutomationSession.snapshot()
        if (!isChangingConfigurations && session?.id == sessionId &&
            session.phase == LineAutomationSession.Phase.WAIT_VOICE)
            LineAutomationSession.abort(sessionId)
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
