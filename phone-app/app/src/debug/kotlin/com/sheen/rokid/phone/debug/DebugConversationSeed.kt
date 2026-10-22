package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.platform.P3Log

/**
 * Debug-only synthetic conversation seed, used only to take the 0.23 P1 layout screenshots.
 *
 * The assistant page's two acceptance facts — the header staying put while the message list scrolls, and the
 * assistant/user bubble sides — need a record longer than one screen. A real session would need a model, a network and
 * a person speaking; this receiver appends synthetic Chinese lines through the **production** `ConversationLog.append`
 * API instead, so the UI under test is the real one and the log's real bounds/order still apply.
 *
 * ```
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_CONVERSATION \
 *   -n com.sheen.rokid.phone/.debug.DebugConversationSeed --ei count 30 --ez clear true
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_CONVERSATION \
 *   -n com.sheen.rokid.phone/.debug.DebugConversationSeed --ei count 3 --es route PROACTIVE
 * ```
 *
 * `clear` is **global** (`ConversationLog` has no per-route clear), so a seeded PROACTIVE run after a WAKE run must
 * omit `--ez clear true`; the ids include the route name, so the two routes cannot replace each other.
 *
 * It exists **only in the debug source set** (see `src/debug/AndroidManifest.xml`) and is `exported` so `am broadcast`
 * from the host can reach it; a release build contains no receiver and no seed path. It logs counts only — never the
 * synthetic text, and never any real conversation content.
 */
class DebugConversationSeed : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? EdithApplication ?: return
        val count = intent.getIntExtra(EXTRA_COUNT, DEFAULT_COUNT).coerceIn(0, MAX_COUNT)
        val route = AssistantRoute.fromWire(intent.getStringExtra(EXTRA_ROUTE)) ?: AssistantRoute.WAKE
        val log = app.composition.conversationLog
        // Deliberately global: ConversationLog exposes clear() only. A caller seeding the second route must omit clear.
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) log.clear()
        for (index in 1..count) {
            val role = if (index % 2 == 1) ConversationRole.USER else ConversationRole.ASSISTANT
            log.append(
                ConversationEntry(
                    entryId = "$ID_PREFIX${route.name}-$index-${role.name}",
                    route = route,
                    role = role,
                    text = textFor(route, index, role),
                ),
            )
        }
        // Counts and route only: the text itself is the evidence subject and must not reach logcat.
        P3Log.line("P3_CONV_SEED route=${route.name} count=$count total=${log.size()}")
    }

    private fun textFor(route: AssistantRoute, index: Int, role: ConversationRole): String {
        val conversation = when (route) {
            AssistantRoute.WAKE -> "唤醒"
            AssistantRoute.PROACTIVE -> "主动辅助"
        }
        return when (role) {
            ConversationRole.USER ->
                "$conversation 第 $index 轮用户消息：这是用于布局证据的合成记录，句子长度足以让对话列表超过一屏并触发滚动。"
            ConversationRole.ASSISTANT ->
                "$conversation 第 $index 轮助手回复：这是用于布局证据的合成记录，句子长度足以让对话列表超过一屏并触发滚动。"
        }
    }

    companion object {
        /** The explicit action, so the receiver is reachable without a matching implicit broadcast. */
        const val ACTION = "edith.DEBUG_CONVERSATION"

        const val EXTRA_COUNT = "count"
        const val EXTRA_CLEAR = "clear"

        /** `WAKE` (default) or `PROACTIVE`; the ids include the route name. `clear` is global regardless of route. */
        const val EXTRA_ROUTE = "route"

        const val DEFAULT_COUNT = 30

        /** A bound so a mistaken `--ei count` cannot spend minutes appending or evict through thousands of entries. */
        const val MAX_COUNT = 200

        /** Stable ids, so a re-seed replaces the same positions instead of growing the log without bound. */
        const val ID_PREFIX = "seed-"
    }
}
