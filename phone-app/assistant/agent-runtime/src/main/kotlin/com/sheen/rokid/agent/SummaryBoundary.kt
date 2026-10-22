package com.sheen.rokid.agent

/**
 * 摘要段的显式边界（H1 / 规格 §3.11 决策 8c）。
 *
 * 归属判定**只**来自 [SegmentOrigin.SUMMARY] 这个元数据，**不是**对文本的匹配；[isRendered] 只回答"这段文本是否
 * 已被包过"这一个展示问题，**不得**用它做归属判定。
 *
 * Phase 5 起边界在**入库时**渲染一次：`ConversationStore.appendSummary` 存的就是套上边界的文本，因此 `history()`
 * 与 `snapshot()` 交出的字节完全一致，记账量到的就是上线缆的（见 `ConversationStore.kt:17-22`）。网关侧
 * `frameToTranscript` 只对**尚未**带边界的正文包一次，不会再包第二层。
 */
object SummaryBoundary {
    const val OPEN: String = "<conversation_summary origin=\"SUMMARY\">"
    const val CLOSE: String = "</conversation_summary>"

    fun render(body: String): String = "$OPEN\n$body\n$CLOSE"

    /** 仅供"这段文本是否已被包过"的展示判断；**不得**用它做归属判定。 */
    fun isRendered(text: String): Boolean = text.startsWith(OPEN) && text.endsWith(CLOSE)
}
