package com.example.privagentcollector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Product-facing home: only SHARE_PHOTO and CHECK_MESSAGE are presented. */
class MainActivity : Activity() {
    private var assistantPanel: AssistantPanel? = null
    private var messagePanel: MessageCheckPanel? = null

    private fun dp(value: Int) = AppUi.dp(this, value)
    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The product UI no longer exposes the legacy raw research collector. Never leave an old
        // collection session running after upgrading from a research build.
        CollectionControl.stop()
        if (RealtimeRuntime.active || RealtimeRuntime.phase == "stopping") RealtimeTransmissionService.stop(this)

        @Suppress("DEPRECATION")
        window.statusBarColor = AppUi.BACKGROUND
        @Suppress("DEPRECATION")
        window.navigationBarColor = AppUi.BACKGROUND
        // Accessing Window.insetsController before setContentView crashes on some Android builds.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
            setBackgroundColor(AppUi.BACKGROUND)
        }
        val scroll = ScrollView(this)
        root.addView(header())
        root.addView(AppUi.spacer(this, 30))
        root.addView(TextView(this).apply {
            text = "${greeting()} 👋"
            AppUi.title(this, 30f)
        })
        root.addView(TextView(this).apply {
            text = "今天想做什麼？我會陪您一步一步完成。"
            setPadding(0, dp(8), 0, 0)
            AppUi.body(this, 18f)
        })
        root.addView(AppUi.spacer(this, 28))
        root.addView(voiceCard(), cardParams())
        // Every spoken or tapped task reports its progress here, right under the voice card.
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(statusBox, cardParams())
        root.addView(AppUi.spacer(this, 28))
        root.addView(AppUi.section(this, "常用協助"))
        val messageSection = messageCard()
        root.addView(helpGrid { scroll.post { scroll.smoothScrollTo(0, messageSection.top) } }, cardParams())
        root.addView(AppUi.spacer(this, 28))
        root.addView(messageSection, cardParams())
        root.addView(AppUi.spacer(this, 18))
        root.addView(supportCard(), cardParams())
        root.addView(AppUi.spacer(this, 18))
        root.addView(privacyNote(), cardParams())
        root.addView(TextView(this).apply {
            text = getString(R.string.app_footer, BuildConfig.VERSION_NAME)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            AppUi.body(this, 13f)
        })
        assistantPanel = AssistantPanel(this, statusBox, ::dp, ::assistEndpoint,
            showControls = false) { messagePanel?.pickNotification() }

        scroll.apply {
            setBackgroundColor(AppUi.BACKGROUND)
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left, 0, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "早安"
        in 11..17 -> "午安"
        else -> "晚安"
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            background = AppUi.rounded(this@MainActivity, AppUi.PRIMARY, 14)
            addView(AppUi.icon(this@MainActivity, R.drawable.ic_favorite, Color.WHITE, 27))
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
        addView(TextView(this@MainActivity).apply {
            text = "PrivAgent"
            textSize = 25f
            setTextColor(AppUi.TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            background = AppUi.circle(AppUi.PRIMARY_SOFT)
            isClickable = true
            isFocusable = true
            contentDescription = "AI 連線設定"
            setOnClickListener { showConnectionSettings() }
            addView(AppUi.icon(this@MainActivity, R.drawable.ic_settings, AppUi.PRIMARY_DARK, 27))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
    }

    /** The big blue「按一下，直接告訴我」card: starts speech input for either task. */
    private fun voiceCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(24), dp(18), dp(24))
        background = AppUi.rounded(this@MainActivity, AppUi.PRIMARY, 26)
        elevation = dp(3).toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = "按一下，直接用說的告訴我"
        setOnClickListener { assistantPanel?.listen() }
        addView(AppUi.iconBadge(this@MainActivity, R.drawable.ic_mic, AppUi.PRIMARY, Color.WHITE, 70, 39))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "按一下，直接告訴我"
                textSize = 22f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "例如：「把照片傳給女兒」"
                textSize = 16f
                setTextColor(AppUi.ON_PRIMARY_MUTED)
                setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(18) })
        addView(AppUi.icon(this@MainActivity, R.drawable.ic_chevron_right, Color.WHITE, 32))
    }

    private fun helpGrid(showMessageCheck: () -> Unit): View = AppUi.grid(this, columns = 2, tiles = listOf(
        AppUi.helpTile(this, R.drawable.ic_photo_library, "傳送照片", AppUi.PURPLE) { assistantPanel?.sharePhoto() },
        AppUi.helpTile(this, R.drawable.ic_image, "傳最新照片", AppUi.PRIMARY) { assistantPanel?.shareLatestPhoto() },
        AppUi.helpTile(this, R.drawable.ic_sms_warning, "檢查可疑訊息", AppUi.ROSE, showMessageCheck),
        AppUi.helpTile(this, R.drawable.ic_notifications, "從通知檢查", AppUi.ORANGE) {
            showMessageCheck()
            messagePanel?.pickNotification()
        },
        AppUi.helpTile(this, R.drawable.ic_family, "家人設定", AppUi.ACCENT) { assistantPanel?.openFamilySettings() },
        AppUi.helpTile(this, R.drawable.ic_touch_app, "LINE 免觸控", AppUi.SLATE) {
            assistantPanel?.openLineAutomationSettings()
        }
    ))

    private fun privacyNote(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = AppUi.rounded(this@MainActivity, AppUi.SUCCESS_SOFT, 16)
        addView(AppUi.icon(this@MainActivity, R.drawable.ic_shield, AppUi.ACCENT, 29))
        addView(TextView(this@MainActivity).apply {
            text = "您的資料會先在手機上處理，只有必要的資訊才會離開手機。"
            textSize = 15f
            setLineSpacing(0f, 1.4f)
            setTextColor(AppUi.SUCCESS_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    private fun messageCard(): LinearLayout = AppUi.card(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(AppUi.iconBadge(this@MainActivity, R.drawable.ic_sms_warning, AppUi.ROSE,
                AppUi.soft(AppUi.ROSE), 48, 26))
            addView(TextView(this@MainActivity).apply {
                text = "詐騙訊息檢查"
                AppUi.title(this)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        })
        addView(TextView(this@MainActivity).apply {
            text = "貼上訊息或從通知選一則。手機會先遮蔽個資、抽出風險特徵，再交給 AI 判讀。"
            setPadding(0, dp(12), 0, 0)
            AppUi.body(this)
        })
        addView(AppUi.spacer(this@MainActivity, 10))
        messagePanel = MessageCheckPanel(this@MainActivity, this, ::dp, ::assistEndpoint,
            showHeader = false, formalStyle = true)
    }

    private fun supportCard(): LinearLayout = AppUi.card(this).apply {
        addView(TextView(this@MainActivity).apply {
            text = "輔助與權限"
            AppUi.title(this, 19f)
        })
        addView(TextView(this@MainActivity).apply {
            text = "這些設定只支援照片分享與訊息檢查，不會啟動研究資料蒐集。"
            setPadding(0, dp(4), 0, dp(8))
            AppUi.body(this, 15f)
        })
        addView(CheckBox(this@MainActivity).apply {
            text = "顯示 PrivAgent 浮動按鈕"
            isChecked = FloatingAssistant.enabled(this@MainActivity)
            AppUi.checkbox(this)
            setOnCheckedChangeListener { _, checked ->
                FloatingAssistant.setEnabled(this@MainActivity, checked)
                if (checked && !FloatingAssistant.canDrawOverlays(this@MainActivity)) {
                    message("請允許顯示在其他應用程式上層")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                } else FloatingAssistant.sync(this@MainActivity)
            }
        })
        addView(Button(this@MainActivity).apply {
            text = "開啟通知存取設定"
            AppUi.secondary(this)
            setOnClickListener { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun showConnectionSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            text = if (AssistConfig.builtIn) "此 APK 已內建 AI 服務位置。" else "AI Receiver 網址"
            AppUi.body(this, 14f)
        })
        val endpoint = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(AssistConfig.baseUrl(this@MainActivity))
            isEnabled = !AssistConfig.builtIn
            AppUi.field(this)
        }
        content.addView(endpoint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        content.addView(TextView(this).apply {
            text = "存取碼（若服務未要求可留空）"
            setPadding(0, dp(14), 0, 0)
            AppUi.body(this, 14f)
        })
        val token = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(AssistConfig.prefs(this@MainActivity).getString("token", "").orEmpty())
            hint = if (BuildConfig.ASSIST_TOKEN.isNotBlank()) "已內建存取碼，可留空" else "輸入存取碼"
            AppUi.field(this)
        }
        content.addView(token, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        AlertDialog.Builder(this).setTitle("AI 連線設定").setView(content)
            .setPositiveButton("儲存") { _, _ -> saveConnection(endpoint.text.toString(), token.text.toString()) }
            .setNeutralButton("測試連線") { _, _ ->
                val url = saveConnection(endpoint.text.toString(), token.text.toString()) ?: return@setNeutralButton
                testConnection(url)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun saveConnection(endpoint: String, token: String): String? = try {
        val normalized = if (AssistConfig.builtIn) TransferProtocol.normalizeBaseUrl(AssistConfig.baseUrl(this))
            else TransferConfig.setBaseUrl(this, endpoint)
        AssistConfig.save(this, token, AssistConfig.cloudConsent(this), AssistConfig.allowExcerpt(this))
        messagePanel?.refreshConnectionSettings()
        message("連線設定已儲存")
        normalized
    } catch (error: IllegalArgumentException) {
        message(error.message ?: "AI 服務網址格式錯誤")
        null
    }

    private fun testConnection(endpoint: String) {
        message("正在測試連線…")
        Thread({
            val result = try {
                RawDataSender.checkHealth(endpoint)
                "AI Receiver 連線正常"
            } catch (_: Exception) { "目前連不到 AI Receiver；本機判讀仍可使用" }
            runOnUiThread { message(result) }
        }, "assist-health").start()
    }

    private fun assistEndpoint(): String? = try {
        TransferProtocol.normalizeBaseUrl(AssistConfig.baseUrl(this))
    } catch (_: IllegalArgumentException) {
        message("請先從右上角設定 AI 連線")
        null
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: Exception) { message("這台手機無法直接開啟此設定") }
    }

    override fun onResume() {
        super.onResume()
        FloatingAssistant.sync(this)
        messagePanel?.renderAuto()
    }

    @Deprecated("The photo/contact/speech pickers still use the framework result API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        assistantPanel?.onActivityResult(requestCode, resultCode, data)
    }
}
