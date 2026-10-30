package com.example.privagentcollector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Large-print pop-up over whatever app is open. It hosts the same [AssistantPanel] and
 * [MessageCheckPanel] as the main screen, so every privacy rule and packet stays identical.
 */
class QuickAssistActivity : Activity() {
    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val TEXT_SCALE = 1.2f
        // Platform dialogs start from smaller type than the panels.
        private const val DIALOG_TEXT_SCALE = 1.5f
    }

    private var assistant: AssistantPanel? = null
    private var speaker: Speaker? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun endpoint(): String? =
        runCatching { TransferProtocol.normalizeBaseUrl(AssistConfig.baseUrl(this)) }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.4f)
        speaker = Speaker(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = FloatArray(8) { i -> if (i < 4) dp(24).toFloat() else 0f }
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@QuickAssistActivity).apply {
                text = "金孫在這裡，請說要做什麼"
                textSize = 20f
                setTextColor(Color.rgb(230, 81, 0))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@QuickAssistActivity).apply {
                text = "關閉"
                setOnClickListener { finish() }
            })
        })
        val assistBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val messageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(assistBox)
        root.addView(messageBox)
        var messagePanel: MessageCheckPanel? = null
        assistant = AssistantPanel(this, assistBox, ::dp, ::endpoint, onSay = { speaker?.say(it) },
            // List rows are laid out after show(), so enlarge on the next frame.
            onDialog = { d -> d.window?.decorView?.let { v -> v.post { enlarge(v, DIALOG_TEXT_SCALE) } } }) {
            // The message check is long; it only appears when the user asks for it.
            if (messagePanel == null) {
                messagePanel = MessageCheckPanel(this, messageBox, ::dp, ::endpoint)
                enlarge(messageBox)
            }
            messageBox.visibility = View.VISIBLE
            messagePanel?.pickNotification()
        }
        enlarge(assistBox)
        setContentView(ScrollView(this).apply { addView(root); isFillViewport = true })

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_LISTEN, false)) assistant?.listen()
    }

    /** Older eyes: every label and button in the shared panels gets bigger type. */
    private fun enlarge(view: View, scale: Float = TEXT_SCALE) {
        if (view is TextView) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * scale)
        if (view is ViewGroup) for (i in 0 until view.childCount) enlarge(view.getChildAt(i), scale)
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleService.setCovered(true)
    }

    override fun onPause() {
        FloatingBubbleService.setCovered(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) assistant?.listen()
    }

    @Deprecated("The shared panels still use startActivityForResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        assistant?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
