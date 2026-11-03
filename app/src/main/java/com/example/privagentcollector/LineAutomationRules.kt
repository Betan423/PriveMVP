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
    private val messageShareLabels = setOf("分享", "share", "シェア", "共有", "공유")
    private val messageSelectionLabels = setOf(
        "選擇訊息", "選取訊息", "selectmessages", "selectmessage", "メッセージを選択", "메시지선택"
    )
    private val moreLabels = setOf(
        "更多", "更多選項", "顯示更多", "查看更多", "more", "moreoptions", "showmore",
        "もっと見る", "その他", "더보기", "추가옵션"
    )

    /** Ignores presentation-only whitespace and punctuation, but never does fuzzy name matching. */
    fun normalize(value: CharSequence?): String = value?.toString().orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s　・,，。.!！?？:：;；『』「」\\[\\]()（）]+"), "")

    fun isExactRecipient(value: CharSequence?, recipient: String): Boolean =
        normalize(value).isNotEmpty() && normalize(value) == normalize(recipient)

    fun isSendControl(value: CharSequence?): Boolean = normalize(value) in sendLabels

    fun isMessageShareControl(value: CharSequence?): Boolean = normalize(value) in messageShareLabels

    fun isMessageSelectionScreenMarker(value: CharSequence?): Boolean =
        normalize(value) in messageSelectionLabels

    fun isSelectedMessageShareControl(value: CharSequence?): Boolean {
        val normalized = normalize(value)
        return messageShareLabels.any { normalized == "${it}1" }
    }

    fun isMoreControl(value: CharSequence?): Boolean = normalize(value) in moreLabels

    /** Rejects status/navigation-edge taps and obviously invalid display coordinates. */
    fun isSafeTargetPoint(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val topGuard = screenHeight / 25
        val bottomGuard = screenHeight / 18
        return x in 0 until screenWidth && y in topGuard until (screenHeight - bottomGuard)
    }

    fun isPlausibleChatImage(
        className: CharSequence?,
        text: CharSequence?,
        description: CharSequence?,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (width < screenWidth / 6 || height < screenHeight / 10) return false
        if (normalize(text).isNotEmpty()) return false
        val type = className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val label = normalize(description)
        val imageType = type.contains("imageview") || type.endsWith(".image")
        val imageLabel = listOf("圖片", "照片", "相片", "image", "photo").any { label.contains(it) }
        return imageType || imageLabel
    }

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
