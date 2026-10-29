package com.sheen.rokid.phone.overlay

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.phone.platform.P3Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 0.5: the one path from the plugin's conversation display to the phone-only message stack.
 *
 * It is a [PhoneBubbleFeed] (the display's app-internal hook), it owns a [BubbleStackModel], and it serializes every
 * mutation because `ConversationDisplay` is called from the plugin's turn thread, the transport thread and the UI
 * thread. It also adds the processing row right after a user utterance: the glasses' `ASR_END → 正在思考` transition is
 * not a separate display call, and the bubble needs that row without inventing a plugin-side event.
 *
 * Only the WAKE route is accepted. The phone-only model has no proactive assistance (0.4/D202), and mixing a proactive
 * hint into this stack would show the wearer a message that has no bubble surface or dismissal contract.
 *
 * 0.5.1 (`C10`): a local Notice (no session, refused dictation, …) is added to the same model and carries its own
 * 3–4 s deadline. It therefore expires from [snapshotNow] on the injected clock without a `TurnIdle`, and the overlay's
 * frame loop is what paints the disappearance.
 *
 * The clock is injected and monotonic-millisecond based. Production passes `SystemClock.elapsedRealtime()`; JVM tests
 * pass a mutable fake.
 */
class PhoneBubbleRepository(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : PhoneBubbleFeed {

    private val lock = Any()
    private val model = BubbleStackModel()
    private val state = MutableStateFlow(BubbleSnapshot())

    /** Event-driven updates; a Compose collector uses this to start its frame loop. */
    val snapshots: StateFlow<BubbleSnapshot> = state.asStateFlow()

    override fun onEvent(event: PhoneBubbleEvent) {
        if (event.route != AssistantRoute.WAKE) return
        synchronized(lock) {
            val now = nowMillis()
            when (event) {
                is PhoneBubbleEvent.User -> {
                    model.onEvent(event, now)
                    // The glasses show 正在思考 between the wearer's final and the first tool/answer; the display has no
                    // separate call for it, so the repository emits it here. It is a real message (supersedes the user
                    // line, gets superseded by the first tool/answer) and therefore follows the same 2 s rule.
                    model.onEvent(PhoneBubbleEvent.Thinking(event.route, event.turnId), now)
                }
                else -> model.onEvent(event, now)
            }
            state.value = model.snapshot(now)
            // 0.5 evidence line: which event reached the stack and how many messages the model now shows. Never the
            // message text; this is what tells the device run whether the plugin's display is feeding the overlay.
            P3Log.line(
                "P5_BUBBLE event=${event.javaClass.simpleName} route=${event.route.name} " +
                    "visible=${state.value.messages.size}",
            )
        }
    }

    /** The current snapshot, independent of whether an event just arrived; the UI calls this from its frame loop. */
    fun snapshotNow(): BubbleSnapshot = synchronized(lock) { model.snapshot(nowMillis()) }

    /** True while the visible newest message is the agent thinking or a tool running (the ball's light-ring state). */
    fun isAgentWorking(): Boolean = snapshotNow().messages.lastOrNull()?.kind.let {
        it == BubbleKind.THINKING || it == BubbleKind.TOOL_RUNNING
    }

    /** True while an answer bubble is the newest message (the ball's gentler "speaking" light). */
    fun isAnswering(): Boolean = snapshotNow().messages.lastOrNull()?.kind == BubbleKind.ANSWER

    /** Drops the model and publishes an empty snapshot; called when the overlay is torn down. */
    fun clear() {
        synchronized(lock) {
            model.clear()
            state.value = BubbleSnapshot()
        }
    }
}
