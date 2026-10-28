package com.example.privagentcollector

import android.content.Context

data class RealtimeSettings(
    val baselineSeconds: Int,
    val intervalSeconds: Int,
    val immediateEvents: Boolean
)

object RealtimeSettingRules {
    const val DEFAULT_BASELINE_SECONDS = 30
    const val DEFAULT_INTERVAL_SECONDS = 60
    const val MIN_BASELINE_SECONDS = 5
    const val MAX_BASELINE_SECONDS = 600
    const val MIN_INTERVAL_SECONDS = 10
    const val MAX_INTERVAL_SECONDS = 3600

    fun parse(baseline: String, interval: String, immediateEvents: Boolean): RealtimeSettings {
        val baselineSeconds = baseline.trim().toIntOrNull()
            ?: throw IllegalArgumentException("基礎蒐集秒數必須是整數")
        val intervalSeconds = interval.trim().toIntOrNull()
            ?: throw IllegalArgumentException("定期傳送秒數必須是整數")
        require(baselineSeconds in MIN_BASELINE_SECONDS..MAX_BASELINE_SECONDS) {
            "基礎蒐集秒數需介於 $MIN_BASELINE_SECONDS 到 $MAX_BASELINE_SECONDS"
        }
        require(intervalSeconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) {
            "定期傳送秒數需介於 $MIN_INTERVAL_SECONDS 到 $MAX_INTERVAL_SECONDS"
        }
        return RealtimeSettings(baselineSeconds, intervalSeconds, immediateEvents)
    }
}

object RealtimeConfig {
    private const val PREFS = "realtime_options"
    private const val BASELINE_SECONDS = "baseline_seconds"
    private const val INTERVAL_SECONDS = "interval_seconds"
    private const val IMMEDIATE_EVENTS = "immediate_events"

    fun load(context: Context): RealtimeSettings {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RealtimeSettings(
            preferences.getInt(BASELINE_SECONDS, RealtimeSettingRules.DEFAULT_BASELINE_SECONDS),
            preferences.getInt(INTERVAL_SECONDS, RealtimeSettingRules.DEFAULT_INTERVAL_SECONDS),
            preferences.getBoolean(IMMEDIATE_EVENTS, true)
        )
    }

    fun save(context: Context, settings: RealtimeSettings) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(BASELINE_SECONDS, settings.baselineSeconds)
            .putInt(INTERVAL_SECONDS, settings.intervalSeconds)
            .putBoolean(IMMEDIATE_EVENTS, settings.immediateEvents)
            .commit()) { "無法保存即時傳送設定" }
    }
}
