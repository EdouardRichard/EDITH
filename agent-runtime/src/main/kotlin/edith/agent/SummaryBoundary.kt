package edith.agent

/**
 * 摘要段的显式边界（H1 / 规格 §3.11 决策 8c）。
 *
 * 边界是**渲染期**产物：`ConversationStore` 里存的是干净正文，边界只在把它写上线缆时包上去。
 * 这样"摘要正文无法伪造边界"，且归属判定始终来自 [SegmentOrigin.SUMMARY] 这个元数据，**不是**对文本的匹配。
 */
object SummaryBoundary {
    const val OPEN: String = "<conversation_summary origin=\"SUMMARY\">"
    const val CLOSE: String = "</conversation_summary>"

    fun render(body: String): String = "$OPEN\n$body\n$CLOSE"

    /** 仅供"这段文本是否已被包过"的展示判断；**不得**用它做归属判定。 */
    fun isRendered(text: String): Boolean = text.startsWith(OPEN) && text.endsWith(CLOSE)
}
