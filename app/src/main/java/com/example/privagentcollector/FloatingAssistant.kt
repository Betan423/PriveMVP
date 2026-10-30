package com.example.privagentcollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ServiceCompat
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs

/** The always-on-screen 金孫 button: one tap opens the quick assistant from any app. */
object FloatingAssistant {
    private const val PREFS = "floating_assistant"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)
    /** Pop-ups over other apps need both the user's switch and the system overlay permission. */
    fun canPopup(context: Context) = enabled(context) && canDrawOverlays(context)

    /** Starts or stops the bubble to match the saved switch; call from a foreground Activity. */
    fun sync(context: Context) {
        val intent = Intent(context, FloatingBubbleService::class.java)
        if (canPopup(context)) context.startForegroundService(intent) else context.stopService(intent)
    }

    /** A finger that moved less than [slop] pixels in both directions tapped rather than dragged. */
    fun isTap(dx: Float, dy: Float, slop: Int) = abs(dx) < slop && abs(dy) < slop

    /** Status lines end with "判讀來源：…" for the screen; that part is not read aloud. */
    fun spoken(text: String) = text.lineSequence().map { it.trim().removePrefix("・") }
        .filter { it.isNotEmpty() && !it.startsWith("判讀來源") }.joinToString("。")
}

/** Reads short texts aloud in Taiwanese Mandarin; texts arriving before the engine is ready wait. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.TAIWAN
            tts.setSpeechRate(0.9f)
            pending?.let { say(it) }
        }
        pending = null
    }

    fun say(text: String) {
        val spoken = FloatingAssistant.spoken(text)
        if (spoken.isEmpty()) return
        if (!ready) { pending = spoken; return }
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "jinsun")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}

class FloatingBubbleService : Service() {
    companion object {
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_HIDE = "com.example.privagentcollector.FLOATING_HIDE"
        @Volatile private var instance = WeakReference<FloatingBubbleService>(null)

        /** Our own pop-ups hide the button so it never covers their text or buttons. Main thread only. */
        fun setCovered(covered: Boolean) {
            instance.get()?.bubble?.visibility = if (covered) View.GONE else View.VISIBLE
        }
    }

    private var bubble: View? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) FloatingAssistant.setEnabled(this, false)
        if (!FloatingAssistant.canPopup(this)) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (bubble == null) showBubble()
        return START_STICKY
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            dp(76), dp(76),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = resources.displayMetrics.heightPixels / 2 }
        val view = TextView(this).apply {
            text = "金孫"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(230, 81, 0))
                setStroke(dp(3), Color.WHITE)
            }
            contentDescription = "打開 AI 金孫助理"
        }
        val slop = dp(8)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (FloatingAssistant.isTap(e.rawX - downX, e.rawY - downY, slop)) { v.performClick(); openAssistant() }
                    else {
                        // Snap to the nearer screen edge so the button never covers the middle of an app.
                        val width = resources.displayMetrics.widthPixels
                        params.x = if (params.x + v.width / 2 < width / 2) 0 else width - v.width
                        wm.updateViewLayout(v, params)
                    }
                }
            }
            true
        }
        wm.addView(view, params)
        bubble = view
    }

    private fun openAssistant() {
        startActivity(Intent(this, QuickAssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(QuickAssistActivity.EXTRA_LISTEN, true))
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "浮動金孫按鈕", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "讓金孫按鈕可以一直顯示在畫面上" })
        val hide = PendingIntent.getService(this, 0,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, QuickAssistActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_collector)
            .setContentTitle("金孫按鈕已開啟")
            .setContentText("點畫面上的「金孫」就能找我幫忙")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "隱藏按鈕", hide).build())
            .build()
    }

    override fun onDestroy() {
        bubble?.let { getSystemService(WindowManager::class.java).removeView(it) }
        bubble = null
        if (instance.get() === this) instance.clear()
        super.onDestroy()
    }
}
