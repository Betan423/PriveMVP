package com.example.privagentcollector

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small design system without extra UI libraries. Palette, radii and type sizes follow the
 * AccompanyYou (伴伴) design: navy text, one bright blue action color, large rounded cards.
 */
object AppUi {
    val BACKGROUND = Color.rgb(248, 250, 253)
    val SURFACE = Color.WHITE
    val PRIMARY = Color.rgb(47, 125, 225)
    val PRIMARY_DARK = Color.rgb(23, 50, 92)
    val PRIMARY_SOFT = Color.rgb(232, 242, 255)
    val ON_PRIMARY_MUTED = Color.rgb(221, 235, 255)
    val ACCENT = Color.rgb(45, 155, 100)
    val TEXT = Color.rgb(23, 50, 92)
    val MUTED = Color.rgb(95, 111, 134)
    val BORDER = Color.rgb(227, 234, 243)
    val SUCCESS_SOFT = Color.rgb(234, 248, 239)
    val SUCCESS_TEXT = Color.rgb(39, 98, 71)
    val PURPLE = Color.rgb(129, 86, 216)
    val ORANGE = Color.rgb(255, 138, 52)
    val ROSE = Color.rgb(226, 89, 104)
    val SLATE = Color.rgb(97, 116, 139)

    // Result levels: strong enough to read as text on a white or soft-blue card.
    val DANGER = Color.rgb(183, 28, 28)
    val WARNING = Color.rgb(207, 92, 0)
    val SUCCESS = Color.rgb(31, 122, 77)
    val NEUTRAL = Color.rgb(83, 100, 123)

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radius: Int = 16, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            strokeColor?.let { setStroke(dp(context, 1), it) }
        }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** [color] at 12% over white, the soft icon background of the help tiles. */
    fun soft(color: Int): Int = Color.argb(31, Color.red(color), Color.green(color), Color.blue(color))

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
        background = rounded(context, SURFACE, 20, BORDER)
    }

    fun title(view: TextView, size: Float = 22f) = view.apply {
        textSize = size
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(view: TextView, size: Float = 16f) = view.apply {
        textSize = size
        setTextColor(MUTED)
        setLineSpacing(0f, 1.3f)
    }

    fun primary(button: Button, color: Int = PRIMARY) = button.apply {
        isAllCaps = false
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(context, color, 18)
        backgroundTintList = null
        stateListAnimator = null
        minHeight = dp(context, 60)
        setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
    }

    fun secondary(button: Button) = button.apply {
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(PRIMARY_DARK)
        background = rounded(context, PRIMARY_SOFT, 16)
        backgroundTintList = null
        stateListAnimator = null
        minHeight = dp(context, 52)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
    }

    fun field(field: EditText) = field.apply {
        textSize = 18f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        background = rounded(context, Color.WHITE, 16, BORDER)
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
    }

    fun checkbox(box: CheckBox) = box.apply {
        textSize = 16f
        setTextColor(TEXT)
        setLineSpacing(0f, 1.2f)
        buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(PRIMARY, MUTED)
        )
        setPadding(0, dp(context, 6), 0, dp(context, 6))
    }

    fun tag(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        textSize = 13f
        setTextColor(PRIMARY)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
        background = rounded(context, PRIMARY_SOFT, 20)
    }

    fun icon(context: Context, res: Int, color: Int, sizeDp: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** An icon centred on a filled circle, e.g. the microphone on the voice card. */
    fun iconBadge(context: Context, res: Int, iconColor: Int, fill: Int, sizeDp: Int, iconDp: Int): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = circle(fill)
            addView(icon(context, res, iconColor, iconDp))
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Section heading such as「常用協助」. */
    fun section(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        title(this, 21f)
        setPadding(0, 0, 0, dp(context, 12))
    }

    /** Square-ish grid tile: a soft coloured circle with an icon over a bold label. */
    fun helpTile(context: Context, res: Int, label: String, color: Int, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(context, 136)
            setPadding(dp(context, 12), dp(context, 16), dp(context, 12), dp(context, 16))
            background = rounded(context, SURFACE, 20, BORDER)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(iconBadge(context, res, color, soft(color), 58, 31))
            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(TEXT)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(context, 12), 0, 0)
            })
        }

    /** Lays [tiles] out [columns] per row with equal widths and 14dp gutters. */
    fun grid(context: Context, columns: Int, tiles: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val gap = dp(context, 14)
        tiles.chunked(columns).forEachIndexed { rowIndex, rowTiles ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (column in 0 until columns) {
                    val tile = rowTiles.getOrNull(column) ?: View(context)
                    addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (column > 0) marginStart = gap
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = gap
            })
        }
    }

    fun spacer(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, height))
    }
}
