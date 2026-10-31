package com.example.privagentcollector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-attention warning for a high-risk message found by the automatic notification check.
 * It shows only the fixed advice and reason wording; the message text itself is never put here.
 */
class ScamAlertActivity : Activity() {
    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REASONS = "reasons"

        fun title(appLabel: String, sender: String) =
            "小心！$appLabel" + (if (sender.isNotEmpty()) "「$sender」" else "") + "的訊息可能是詐騙"

        fun reasons(codes: List<String>) = codes.mapNotNull { MessageGuard.reasonText[it] }

        fun show(context: Context, appLabel: String, sender: String, outcome: AssistOutcome) {
            context.startActivity(Intent(context, ScamAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TITLE, title(appLabel, sender))
                .putStringArrayListExtra(EXTRA_REASONS, ArrayList(reasons(outcome.reasonCodes))))
        }
    }

    private var speaker: Speaker? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // A stray tap must not dismiss a fraud warning; only「我知道了」closes it.
        setFinishOnTouchOutside(false)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val heading = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val reasons = intent.getStringArrayListExtra(EXTRA_REASONS).orEmpty()
        val advice = MessageGuard.advice.getValue(MessageGuard.Level.HIGH)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
        }
        fun text(value: String, size: Float, color: Int = Color.DKGRAY) = root.addView(TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        })
        text("⚠️ $heading", 26f, Color.rgb(183, 28, 28))
        text(advice, 22f, Color.BLACK)
        if (reasons.isNotEmpty()) text(reasons.joinToString("\n") { "・$it" }, 20f)
        root.addView(Button(this).apply {
            text = "我知道了"
            textSize = 24f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(183, 28, 28)); cornerRadius = dp(16).toFloat() }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(16) })
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        speaker = Speaker(this).also { it.say("小心，這則訊息可能是詐騙。$advice") }
    }

    override fun onResume() {
        super.onResume()
        FloatingBubbleService.setCovered(true)
    }

    override fun onPause() {
        FloatingBubbleService.setCovered(false)
        super.onPause()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
