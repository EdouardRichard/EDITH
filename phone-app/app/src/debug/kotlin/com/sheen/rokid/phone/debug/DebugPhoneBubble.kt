package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.overlay.PhoneBubbleEvent
import com.sheen.rokid.phone.platform.P3Log

/**
 * 0.5 debug-only phone-only message-stack injector.
 *
 * The production path is the plugin's `ConversationDisplay` -> `ConversationWireDisplay` -> `PhoneBubbleRepository`;
 * that path is what the device turn exercises. This receiver exists for the **visual** checks a JVM test cannot make:
 * the bubble's Material card, the old-message fade at the fixed ratio, the 2 s removal and the tool row. It pushes the
 * same production [PhoneBubbleEvent]s into the same repository the overlay reads, with one labelled log line
 * (`P5_BUBBLE_DEBUG`); it is not a UI verification and must never be quoted as one.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_BUBBLE -n com.sheen.rokid.phone/.debug.DebugPhoneBubble --es scenario turn
 * ```
 *
 * Scenarios: `answer`, `turn`, `old`, `notice`. The text is synthetic Chinese and never contains user data.
 */
class DebugPhoneBubble : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val root = (context.applicationContext as? EdithApplication)?.liveComposition
            ?: return P3Log.line("P5_BUBBLE_DEBUG state=refused reason=NO_COMPOSITION")
        val route = AssistantRoute.WAKE
        val turn = "debug-bubble-${System.currentTimeMillis()}"
        val scenario = intent.getStringExtra(EXTRA_SCENARIO) ?: SCENARIO_TURN
        when (scenario) {
            SCENARIO_ANSWER -> root.phoneBubble.onEvent(
                PhoneBubbleEvent.AnswerFinal(route, turn, "debug-hint", "这是纯手机模式的消息气泡。"),
            )
            SCENARIO_TURN -> {
                root.phoneBubble.onEvent(PhoneBubbleEvent.User(route, turn, "帮我看看明天的安排"))
                root.phoneBubble.onEvent(PhoneBubbleEvent.Tool(route, turn, "读取日程", ToolPhase.START))
                root.phoneBubble.onEvent(PhoneBubbleEvent.Tool(route, turn, "读取日程", ToolPhase.END))
                root.phoneBubble.onEvent(
                    PhoneBubbleEvent.AnswerFinal(route, turn, "debug-tool-hint", "明天上午十点有一个会。"),
                )
            }
            SCENARIO_OLD -> {
                val first = "$turn-a"
                val second = "$turn-b"
                root.phoneBubble.onEvent(PhoneBubbleEvent.User(route, first, "第一条消息"))
                root.phoneBubble.onEvent(PhoneBubbleEvent.AnswerFinal(route, first, "debug-old-a", "旧消息：上一轮的回答。"))
                root.phoneBubble.onEvent(PhoneBubbleEvent.User(route, second, "第二条消息"))
                root.phoneBubble.onEvent(PhoneBubbleEvent.AnswerFinal(route, second, "debug-old-b", "新消息：最新一轮的回答。"))
            }
            SCENARIO_NOTICE -> root.phoneBubble.onEvent(
                PhoneBubbleEvent.Notice(route, "还没有开始会话，先点悬浮球打开 App 开始。"),
            )
            else -> P3Log.line("P5_BUBBLE_DEBUG state=refused reason=UNKNOWN_SCENARIO")
        }
        P3Log.line("P5_BUBBLE_DEBUG scenario=$scenario")
    }

    companion object {
        const val ACTION = "edith.DEBUG_BUBBLE"
        const val EXTRA_SCENARIO = "scenario"
        const val SCENARIO_ANSWER = "answer"
        const val SCENARIO_TURN = "turn"
        const val SCENARIO_OLD = "old"
        const val SCENARIO_NOTICE = "notice"
    }
}
