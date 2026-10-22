package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.TokenMeter
import com.sheen.rokid.plugin.ModelHistoryItem

/**
 * A **conservative** token estimate for a piece of text.
 *
 * 0.3 design §5.2 gives the whole harness one unit: [TokenMeter], which the send-time budgeter and the turn trimmer
 * use too. This function is the plugin's name for that same measure — it delegates rather than keeping a second
 * formula, so a prompt the plugin decided fitted cannot be priced differently one layer down. (The old bytes/3
 * estimate counted ASCII at 3 bytes per token and a CJK character at one; [TokenMeter] counts every non-ASCII code
 * point as one token and every 4 ASCII code points as one, which is the agreed unit.)
 */
fun estimateTokens(text: String): Int = TokenMeter.estimate(text)

/**
 * The text a history item contributes to an estimate.
 *
 * A tool call contributes its arguments and a tool output its payload; the identities are bookkeeping the model does
 * not read, so counting them would inflate an estimate without bounding anything real.
 */
internal fun ModelHistoryItem.estimatedText(): String = when (this) {
    is ModelHistoryItem.Text -> text
    is ModelHistoryItem.ToolCall -> argsJson
    is ModelHistoryItem.ToolOutput -> output
}

/**
 * The same contribution, as a **size**. `P3_CONTEXT` reports how much of a prompt a turn carried, and a size is what a
 * device log may hold: the text itself is the user's words and never appears in one.
 */
internal fun ModelHistoryItem.estimatedLength(): Int = estimatedText().length
