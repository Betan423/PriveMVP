package com.example.privagentcollector

import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
            instance.get()?.setCoveredInternal(covered)
        }

        fun setImageSelectionMode(active: Boolean) {
            instance.get()?.setImageSelectionModeInternal(active)
        }
    }

    private var bubble: View? = null
    private var selectionOverlay: View? = null
    private var speaker: Speaker? = null
    private var covered = false

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        speaker = Speaker(this)
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
            text = "助理"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AppUi.PRIMARY)
                setStroke(dp(3), Color.WHITE)
            }
            contentDescription = "打開 PrivAgent 助理"
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
        when (LineShareAccessibilityService.beginChatImageForward()) {
            LineChatForwardStart.STARTED,
            LineChatForwardStart.CANCELLED -> Unit
            LineChatForwardStart.NOT_LINE -> openQuickAssistant(listen = true)
            LineChatForwardStart.NOT_LINE_CHAT ->
                Toast.makeText(this, "請停留在有圖片且輸入框可見的 LINE 聊天室，再按浮動按鈕", Toast.LENGTH_LONG).show()
            LineChatForwardStart.NOT_READY -> {
                Toast.makeText(this, "請先啟用 LINE 免觸控服務", Toast.LENGTH_LONG).show()
                openQuickAssistant(listen = false)
            }
            LineChatForwardStart.NEED_DAUGHTER -> {
                Toast.makeText(this, "請先在家人設定中設定唯一一位女兒", Toast.LENGTH_LONG).show()
                openQuickAssistant(listen = false)
            }
            LineChatForwardStart.NEED_LINE_NAME -> {
                Toast.makeText(this, "請先完成女兒的 LINE 顯示名稱設定", Toast.LENGTH_LONG).show()
                openQuickAssistant(listen = false)
            }
        }
    }

    private fun openQuickAssistant(listen: Boolean) {
        startActivity(Intent(this, QuickAssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(QuickAssistActivity.EXTRA_LISTEN, listen))
    }

    private fun setImageSelectionModeInternal(active: Boolean) {
        if (active) showImageSelectionOverlay() else removeImageSelectionOverlay()
    }

    private fun setCoveredInternal(value: Boolean) {
        covered = value
        updateBubbleVisibility()
    }

    /**
     * A touch-consuming overlay gives the user a frozen virtual target surface. The original LINE
     * screen remains visible underneath, but the selection tap never reaches LINE and cannot open
     * the image. Only the single raw screen coordinate is handed to the accessibility service.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showImageSelectionOverlay() {
        if (selectionOverlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(64, 230, 242, 245))
            isClickable = true
            contentDescription = "圖片位置選取遮罩"
        }
        val instruction = TextView(this).apply {
            text = "請點一下要轉傳給女兒的圖片"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(230, 23, 50, 92))
            }
            isClickable = true
            setOnClickListener { speaker?.say("請點一下畫面中的目標圖片；若不傳送，請按下方取消") }
        }
        root.addView(instruction, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.TOP
        ).apply { setMargins(dp(16), dp(44), dp(16), 0) })

        val cancel = TextView(this).apply {
            text = "取消"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.argb(235, 95, 99, 104))
            }
            setOnClickListener {
                removeImageSelectionOverlay()
                LineShareAccessibilityService.cancelChatImageForward()
            }
        }
        root.addView(cancel, FrameLayout.LayoutParams(dp(112), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(48) })

        val slop = dp(12)
        var downX = 0f
        var downY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    if (FloatingAssistant.isTap(event.rawX - downX, event.rawY - downY, slop)) {
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        removeImageSelectionOverlay()
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!LineShareAccessibilityService.submitChatImagePoint(x, y)) {
                                Toast.makeText(this, "無法開始 LINE 圖片長按，已停止", Toast.LENGTH_LONG).show()
                            }
                        }, 180L)
                    }
                }
            }
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        wm.addView(root, params)
        selectionOverlay = root
        updateBubbleVisibility()
        speaker?.say("請點一下要轉傳給女兒的圖片")
    }

    private fun removeImageSelectionOverlay() {
        val view = selectionOverlay ?: run {
            updateBubbleVisibility()
            return
        }
        selectionOverlay = null
        try { getSystemService(WindowManager::class.java).removeView(view) }
        catch (_: IllegalArgumentException) { Unit }
        updateBubbleVisibility()
    }

    private fun updateBubbleVisibility() {
        bubble?.visibility = if (covered || selectionOverlay != null) View.GONE else View.VISIBLE
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
            .setSmallIcon(R.drawable.ic_favorite)
            .setContentTitle("PrivAgent 快速按鈕已開啟")
            .setContentText("點畫面上的「助理」即可使用照片分享或訊息檢查")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "隱藏按鈕", hide).build())
            .build()
    }

    override fun onDestroy() {
        removeImageSelectionOverlay()
        bubble?.let { getSystemService(WindowManager::class.java).removeView(it) }
        bubble = null
        speaker?.shutdown()
        speaker = null
        if (instance.get() === this) instance.clear()
        super.onDestroy()
    }
}
