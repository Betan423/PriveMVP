package com.example.privagentcollector

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small design system for a formal, high-contrast, light interface without extra UI libraries. */
object AppUi {
    val BACKGROUND = Color.rgb(246, 249, 252)
    val SURFACE = Color.WHITE
    val PRIMARY = Color.rgb(30, 90, 120)
    val PRIMARY_DARK = Color.rgb(22, 67, 91)
    val PRIMARY_SOFT = Color.rgb(232, 243, 248)
    val ACCENT = Color.rgb(30, 117, 109)
    val TEXT = Color.rgb(23, 50, 77)
    val MUTED = Color.rgb(98, 125, 152)
    val BORDER = Color.rgb(215, 227, 237)
    val SUCCESS_SOFT = Color.rgb(233, 247, 243)

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radius: Int = 14, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            strokeColor?.let { setStroke(dp(context, 1), it) }
        }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
        background = rounded(context, SURFACE, 20, BORDER)
        elevation = dp(context, 2).toFloat()
    }

    fun title(view: TextView, size: Float = 22f) = view.apply {
        textSize = size
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(view: TextView, size: Float = 15f) = view.apply {
        textSize = size
        setTextColor(MUTED)
        setLineSpacing(0f, 1.16f)
    }

    fun primary(button: Button) = button.apply {
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(PRIMARY)
        minHeight = dp(context, 52)
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
    }

    fun secondary(button: Button) = button.apply {
        isAllCaps = false
        textSize = 15f
        setTextColor(PRIMARY_DARK)
        backgroundTintList = ColorStateList.valueOf(PRIMARY_SOFT)
        minHeight = dp(context, 48)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
    }

    fun field(field: EditText) = field.apply {
        textSize = 17f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        background = rounded(context, Color.WHITE, 12, BORDER)
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
    }

    fun checkbox(box: CheckBox) = box.apply {
        textSize = 15f
        setTextColor(TEXT)
        buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ACCENT, MUTED)
        )
        setPadding(0, dp(context, 5), 0, dp(context, 5))
    }

    fun tag(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        textSize = 12f
        setTextColor(PRIMARY_DARK)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        background = rounded(context, PRIMARY_SOFT, 20)
    }

    fun spacer(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, height))
    }
}
