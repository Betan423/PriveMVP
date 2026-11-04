package com.example.privagentcollector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.ImageView
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
            setPadding(dp(20), dp(18), dp(20), dp(32))
            setBackgroundColor(AppUi.BACKGROUND)
        }
        root.addView(header())
        root.addView(AppUi.spacer(this, 18))
        root.addView(privacyBanner())
        root.addView(AppUi.spacer(this, 18))
        root.addView(photoCard(), cardParams())
        root.addView(AppUi.spacer(this, 18))
        root.addView(messageCard(), cardParams())
        root.addView(AppUi.spacer(this, 18))
        root.addView(supportCard(), cardParams())
        root.addView(AppUi.spacer(this, 12))
        root.addView(TextView(this).apply {
            text = getString(R.string.app_footer, BuildConfig.VERSION_NAME)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            AppUi.body(this, 13f)
        })

        val scroll = ScrollView(this).apply {
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

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_collector)
            contentDescription = "PrivAgent"
        }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(14) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "PrivAgent"
                AppUi.title(this, 27f)
            })
            addView(TextView(this@MainActivity).apply {
                text = "隱私優先的生活助理"
                AppUi.body(this, 14f)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@MainActivity).apply {
            text = "連線設定"
            AppUi.secondary(this)
            setOnClickListener { showConnectionSettings() }
        })
    }

    private fun privacyBanner(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = AppUi.rounded(this@MainActivity, AppUi.SUCCESS_SOFT, 16)
        addView(TextView(this@MainActivity).apply {
            text = "✓"
            textSize = 22f
            setTextColor(AppUi.ACCENT)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "隱私保護已啟用\n敏感資料會先在手機完成清理與判斷"
            AppUi.body(this, 14f)
            setTextColor(AppUi.TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun photoCard(): LinearLayout = AppUi.card(this).apply {
        addView(AppUi.tag(this@MainActivity, "情境 01"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "安心傳送照片"
            setPadding(0, dp(12), 0, dp(6))
            AppUi.title(this)
        })
        addView(TextView(this@MainActivity).apply {
            text = "選擇家人與照片後，系統會建立不含 EXIF／GPS 的新副本，再安全交給 LINE。"
            AppUi.body(this)
        })
        addView(AppUi.spacer(this@MainActivity, 12))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(AppUi.tag(this@MainActivity, "EXIF／GPS 清除"))
            addView(AppUi.tag(this@MainActivity, "送出前語音確認"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        })
        addView(AppUi.spacer(this@MainActivity, 16))
        addView(Button(this@MainActivity).apply {
            text = "選擇照片並傳送"
            AppUi.primary(this)
            setOnClickListener { assistantPanel?.sharePhoto() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(Button(this@MainActivity).apply {
            text = "傳送最新一張照片"
            AppUi.secondary(this)
            setOnClickListener { assistantPanel?.shareLatestPhoto() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "家人設定"
                AppUi.secondary(this)
                setOnClickListener { assistantPanel?.openFamilySettings() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            addView(Button(this@MainActivity).apply {
                text = "LINE 免觸控"
                AppUi.secondary(this)
                setOnClickListener { assistantPanel?.openLineAutomationSettings() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        val statusBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(statusBox)
        assistantPanel = AssistantPanel(this@MainActivity, statusBox, ::dp, ::assistEndpoint,
            showControls = false) { messagePanel?.pickNotification() }
    }

    private fun messageCard(): LinearLayout = AppUi.card(this).apply {
        addView(AppUi.tag(this@MainActivity, "情境 02"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "詐騙訊息檢查"
            setPadding(0, dp(12), 0, dp(6))
            AppUi.title(this)
        })
        addView(TextView(this@MainActivity).apply {
            text = "貼上訊息或從通知選取。可用電腦端本機 AI 精準判讀，也可關閉後改用手機遮蔽特徵模式。"
            AppUi.body(this)
        })
        addView(AppUi.spacer(this@MainActivity, 10))
        messagePanel = MessageCheckPanel(this@MainActivity, this, ::dp, ::assistEndpoint,
            showHeader = false, formalStyle = true)
    }

    private fun supportCard(): LinearLayout = AppUi.card(this).apply {
        addView(TextView(this@MainActivity).apply {
            text = "輔助與權限"
            AppUi.title(this, 18f)
        })
        addView(TextView(this@MainActivity).apply {
            text = "這些設定只支援上方兩個情境，不會啟動研究資料蒐集。"
            setPadding(0, dp(4), 0, dp(8))
            AppUi.body(this, 14f)
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
