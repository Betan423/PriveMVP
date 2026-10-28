package com.example.privagentcollector

import android.content.Context
import java.util.UUID

enum class DataSource(val title: String, val sensitivity: String, val basic: Boolean = false) {
    DEVICE("裝置與硬體資訊", "P2", true),
    BATTERY("電池狀態", "P0", true),
    NETWORK("網路與 Wi-Fi 狀態", "P2", true),
    SENSORS("感測器清單（不含連續採樣）", "P0", true),
    APPS("可見 App 清單", "P2", true),
    CONTACTS("聯絡人：姓名、電話、Email、地址", "P3"),
    CALENDAR("行事曆事件", "P3"),
    IMAGES("圖片檔案資訊", "P2"),
    VIDEOS("影片檔案資訊", "P2"),
    AUDIO("音訊檔案資訊", "P2"),
    USAGE("App 使用統計與使用事件", "P2"),
    LOCATION("最後已知位置", "P3"),
    NOTIFICATIONS("通知內容（含現有通知）", "P3"),
    ACCESSIBILITY("畫面結構與互動事件", "P3")
}

/** A process restart always starts paused. An old queued task cannot join a new session. */
object CollectionControl {
    private const val PREFS = "collection_options"
    @Volatile private var currentSession: String? = null
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun consented(context: Context) = prefs(context).getBoolean("consent_v2", false)
    fun setConsent(context: Context, value: Boolean) {
        if (!value) stop()
        prefs(context).edit().putBoolean("consent_v2", value).apply()
    }
    fun selected(context: Context, source: DataSource) =
        prefs(context).getBoolean("source_${source.name}", source.basic)
    fun select(context: Context, source: DataSource, value: Boolean) {
        prefs(context).edit().putBoolean("source_${source.name}", value).apply()
    }
    fun accessibilityConsented(context: Context) = prefs(context).getBoolean("accessibility_consent_v2", false)
    fun consentAccessibility(context: Context) {
        prefs(context).edit().putBoolean("accessibility_consent_v2", true).apply()
    }
    fun exclusions(context: Context): Set<String> =
        prefs(context).getString("excluded_packages", "").orEmpty().split(',')
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    fun setExclusions(context: Context, value: String) {
        prefs(context).edit().putString("excluded_packages", value.take(4096)).apply()
    }
    @Synchronized fun start(context: Context): String {
        check(consented(context)) { "請先閱讀並同意資料蒐集說明" }
        check(DataSource.entries.any { selected(context, it) }) { "請至少選擇一個資料類別" }
        return UUID.randomUUID().toString().also { currentSession = it }
    }
    @Synchronized fun stop() { currentSession = null }
    fun session() = currentSession
    fun active() = currentSession != null
    fun allowed(context: Context, source: DataSource, session: String?): Boolean =
        session != null && session == currentSession && consented(context) && selected(context, source) &&
            (source != DataSource.ACCESSIBILITY || accessibilityConsented(context))
}
