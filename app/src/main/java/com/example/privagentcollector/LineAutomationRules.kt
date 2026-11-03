package com.example.privagentcollector

import java.util.Locale

/** Pure, testable rules used by the LINE screen detector and voice confirmation. */
object LineAutomationRules {
    enum class VoiceDecision { CONFIRM, CANCEL, UNKNOWN }
    enum class ScrollChoice { DOWN, FORWARD, NONE }

    private val sendLabels = setOf(
        "傳送", "分享", "送出", "send", "share", "送信", "送る"
    )
    private val shareScreenLabels = setOf(
        "分享對象", "選擇分享對象", "選擇傳送對象", "分享至", "分享給", "選擇好友",
        "sharewith", "selectrecipients", "chooserecipients",
        "シェア先", "共有先", "送信先"
    )
    private val confirmPhrases = setOf(
        "確認傳送", "確定傳送", "請傳送", "可以傳送", "傳送吧", "好傳送", "是的傳送"
    )
    private val cancelPhrases = setOf(
        "取消", "不要傳送", "不要傳", "先不要", "不傳送", "停止"
    )

    /** Ignores presentation-only whitespace and punctuation, but never does fuzzy name matching. */
    fun normalize(value: CharSequence?): String = value?.toString().orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s　・,，。.!！?？:：;；『』「」\\[\\]()（）]+"), "")

    fun isExactRecipient(value: CharSequence?, recipient: String): Boolean =
        normalize(value).isNotEmpty() && normalize(value) == normalize(recipient)

    fun isSendControl(value: CharSequence?): Boolean = normalize(value) in sendLabels

    /** Deliberately excludes generic labels such as plain "分享" that may occur in a chat menu. */
    fun isShareScreenMarker(value: CharSequence?): Boolean = normalize(value) in shareScreenLabels

    /** Accepted only after this session already clicked one recipient on a verified share screen. */
    fun isSelectedShareScreenMarker(value: CharSequence?): Boolean {
        val normalized = normalize(value)
        return Regex("^(已選|selected|選択済み)[1-9][0-9]?$", RegexOption.IGNORE_CASE).matches(normalized)
    }

    /**
     * Selects only a vertical accessibility action. A ViewPager usually exposes LEFT/RIGHT or a
     * one-row, multi-column collection; both must fail closed so Chat never advances to Groups.
     */
    fun verticalScrollChoice(
        hasDown: Boolean,
        hasLeft: Boolean,
        hasRight: Boolean,
        hasForward: Boolean,
        rowCount: Int?,
        columnCount: Int?
    ): ScrollChoice = when {
        hasDown -> ScrollChoice.DOWN
        hasLeft || hasRight -> ScrollChoice.NONE
        rowCount != null && columnCount != null && rowCount > 1 && columnCount <= 1 && hasForward ->
            ScrollChoice.FORWARD
        else -> ScrollChoice.NONE
    }

    fun voiceDecision(value: CharSequence?): VoiceDecision {
        val normalized = normalize(value)
        return when {
            normalized in confirmPhrases -> VoiceDecision.CONFIRM
            normalized in cancelPhrases -> VoiceDecision.CANCEL
            else -> VoiceDecision.UNKNOWN
        }
    }
}
