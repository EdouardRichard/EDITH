package com.sheen.rokid.phone.overlay

import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings

/** 0.5: what one message bubble is. The UI maps these to Material icons/typography; the model only times them. */
enum class BubbleKind { USER, THINKING, TOOL_RUNNING, TOOL_DONE, ANSWER, NOTICE }

/**
 * One stored message. [createdAtMillis], [supersededAtMillis] and [dismissAtMillis] are monotonic-millisecond
 * timestamps; [dismissAtMillis] is set only on a local Notice (C10), whose lifetime does not depend on a turn.
 */
data class BubbleMessage(
    val id: Long,
    val kind: BubbleKind,
    val text: String,
    val turnId: String?,
    val hintId: String?,
    val createdAtMillis: Long,
    val supersededAtMillis: Long? = null,
    val finalized: Boolean = false,
    val dismissAtMillis: Long? = null,
)

/**
 * One message the UI should draw now. [relativeAlpha] is the model's time factor relative to the configured bubble
 * opacity; the renderer turns it into the whole-bubble alpha through `BubbleRenderMath`. [newest] marks the one message
 * that carries the tail and renders at the configured (not faded) opacity.
 */
data class VisibleBubble(
    val id: Long,
    val kind: BubbleKind,
    val text: String,
    val relativeAlpha: Float,
    /** True for the one message that carries the tail (and the configured, non-faded bubble opacity). */
    val newest: Boolean = false,
)

/** The visible stack at one instant; [stackAlpha] is the idle-dismissal fade. */
data class BubbleSnapshot(
    val messages: List<VisibleBubble> = emptyList(),
    val stackAlpha: Float = 1f,
)

/**
 * 0.5 (`D211`/`D215`/`D216`/`D217`), corrected by 0.5.1 (`C6`/`C10`): the timing model behind the phone-only message
 * bubbles, pure and clock-driven.
 *
 * Rules this class is the single authority for:
 *
 * - every message is its own bubble; a new message supersedes the previous one, which fades to
 *   [SmartSettings.OLD_MESSAGE_ALPHA_RATIO] times the configured bubble opacity (the UI applies the configured part) and
 *   is removed [KEEP_MILLIS] + [FADE_MILLIS] after it was superseded;
 * - at most [MAX_VISIBLE] messages are kept: the newest plus one old one. A third message drops the existing old one
 *   immediately and turns the previous newest into the old one. Per the owner's "其余逻辑不变" there is deliberately
 *   **no** extra accelerated fade (the review's 200 ms idea is not implemented);
 * - a tool START creates the row and its END updates the **same** message (no old/new rotation for one action);
 * - a streaming answer updates its own message; a final answer for a new hint creates a new one;
 * - `showIdle` starts a [HIDE_DELAY_MILLIS] dismissal clock; any later message cancels it;
 * - a local [BubbleKind.NOTICE] (no session, refused dictation, …) carries its own
 *   [NOTICE_DISMISS_AFTER_MILLIS] deadline and [purgeExpired] removes it without any `TurnIdle`;
 * - the relative alpha is 1.0 for the current message and the fixed ratio for a superseded one, so the UI can multiply
 *   by the user's configured bubble opacity without the model knowing the settings; [VisibleBubble.newest] is the
 *   model's own answer to "which message carries the tail".
 *
 * Not thread-safe by itself: [PhoneBubbleRepository] serializes calls on its own lock.
 */
class BubbleStackModel {

    private val messages = mutableListOf<BubbleMessage>()
    private var nextId = 1L
    private var hideAtMillis: Long? = null

    /** Applies one event at [nowMillis] (monotonic). */
    fun onEvent(event: PhoneBubbleEvent, nowMillis: Long) {
        purgeExpired(nowMillis)
        hideAtMillis = null
        when (event) {
            is PhoneBubbleEvent.User -> add(BubbleKind.USER, event.text, event.turnId, null, nowMillis)
            is PhoneBubbleEvent.Thinking -> add(BubbleKind.THINKING, THINKING_TEXT, event.turnId, null, nowMillis)
            is PhoneBubbleEvent.Tool -> if (event.phase == ToolPhase.START) {
                add(BubbleKind.TOOL_RUNNING, event.label, event.turnId, null, nowMillis)
            } else {
                finishTool(event, nowMillis)
            }
            is PhoneBubbleEvent.AnswerPartial ->
                answer(event.turnId, event.hintId, event.text, finalized = false, nowMillis = nowMillis)
            is PhoneBubbleEvent.AnswerFinal ->
                answer(event.turnId, event.hintId, event.text, finalized = true, nowMillis = nowMillis)
            is PhoneBubbleEvent.Notice -> add(
                BubbleKind.NOTICE, event.text, null, null, nowMillis,
                dismissAtMillis = nowMillis + NOTICE_DISMISS_AFTER_MILLIS,
            )
            is PhoneBubbleEvent.TurnIdle -> hideAtMillis = nowMillis + HIDE_DELAY_MILLIS
        }
    }

    /** The stack's state at [nowMillis]; expired messages are skipped (and purged here, so callers need not age them). */
    fun snapshot(nowMillis: Long): BubbleSnapshot {
        purgeExpired(nowMillis)
        val hideAt = hideAtMillis
        val stackAlpha = when {
            hideAt == null -> 1f
            nowMillis < hideAt -> 1f
            nowMillis < hideAt + STACK_FADE_MILLIS -> 1f - (nowMillis - hideAt).toFloat() / STACK_FADE_MILLIS
            else -> 0f
        }
        if (stackAlpha <= 0f) return BubbleSnapshot(emptyList(), 0f)
        val lastIndex = messages.lastIndex
        val visible = messages.mapIndexedNotNull { index, message ->
            if (nowMillis < message.createdAtMillis) return@mapIndexedNotNull null
            val entering = ((nowMillis - message.createdAtMillis).toFloat() / ENTER_MILLIS).coerceIn(0f, 1f)
            val relative = message.supersededAtMillis?.let { supersededAt ->
                val age = nowMillis - supersededAt
                when {
                    age < 0 -> entering
                    age < SUPERSEDE_FADE_MILLIS ->
                        1f + (OLD_MESSAGE_RATIO - 1f) * (age.toFloat() / SUPERSEDE_FADE_MILLIS)
                    age < KEEP_MILLIS -> OLD_MESSAGE_RATIO
                    age < KEEP_MILLIS + FADE_MILLIS ->
                        OLD_MESSAGE_RATIO * (1f - (age - KEEP_MILLIS).toFloat() / FADE_MILLIS)
                    else -> 0f
                }
            } ?: entering
            // A row is kept even at a zero relative alpha: the newest renders at its configured opacity immediately
            // (the renderer ignores the entry ramp for it), and for a lone local Notice this snapshot is the only
            // signal the overlay ever gets - the non-empty list is what starts its update loop. Expired rows are
            // already gone via purgeExpired, so nothing can linger here.
            VisibleBubble(message.id, message.kind, message.text, relative, newest = index == lastIndex)
        }
        return BubbleSnapshot(visible, stackAlpha)
    }

    /** Drops everything; used when the overlay is torn down or the mode is turned off. */
    fun clear() {
        messages.clear()
        hideAtMillis = null
    }

    private fun add(
        kind: BubbleKind,
        text: String,
        turnId: String?,
        hintId: String?,
        nowMillis: Long,
        dismissAtMillis: Long? = null,
    ) {
        if (text.isBlank()) return
        supersedeNewest(nowMillis)
        messages += BubbleMessage(nextId++, kind, text, turnId, hintId, nowMillis, dismissAtMillis = dismissAtMillis)
        while (messages.size > MAX_VISIBLE) messages.removeAt(0)
    }

    private fun supersedeNewest(nowMillis: Long) {
        val newest = messages.lastOrNull() ?: return
        if (newest.supersededAtMillis == null) {
            messages[messages.lastIndex] = newest.copy(supersededAtMillis = nowMillis)
        }
    }

    private fun finishTool(event: PhoneBubbleEvent.Tool, nowMillis: Long) {
        if (event.label.isBlank()) return
        val index = messages.indexOfLast {
            it.kind == BubbleKind.TOOL_RUNNING && it.supersededAtMillis == null &&
                it.text == event.label && (event.turnId == null || it.turnId == event.turnId)
        }
        if (index < 0) return
        messages[index] = messages[index].copy(kind = BubbleKind.TOOL_DONE)
    }

    private fun answer(
        turnId: String?,
        hintId: String?,
        text: String,
        finalized: Boolean,
        nowMillis: Long,
    ) {
        if (text.isBlank()) return
        val index = messages.indexOfLast {
            it.kind == BubbleKind.ANSWER && !it.finalized && it.supersededAtMillis == null &&
                (hintId == null || it.hintId == hintId) && (turnId == null || it.turnId == turnId)
        }
        if (index >= 0) {
            messages[index] = messages[index].copy(text = text, finalized = finalized)
            return
        }
        add(BubbleKind.ANSWER, text, turnId, hintId, nowMillis)
        if (finalized) messages[messages.lastIndex] = messages[messages.lastIndex].copy(finalized = true)
    }

    private fun purgeExpired(nowMillis: Long) {
        messages.removeAll { message ->
            val superseded = message.supersededAtMillis?.let { nowMillis - it > KEEP_MILLIS + FADE_MILLIS } == true
            val dismissed = message.dismissAtMillis?.let { nowMillis >= it } == true
            superseded || dismissed
        }
    }

    companion object {
        /** The official-thinking copy the glasses also shows. */
        const val THINKING_TEXT = "正在思考"

        /** The fixed extra fade of a superseded bubble; the UI multiplies it by the configured bubble opacity. */
        val OLD_MESSAGE_RATIO: Float = SmartSettings.OLD_MESSAGE_ALPHA_RATIO

        /**
         * 0.5.1 (`C6`): the newest plus one old message. A cap must never let an unbounded history into the overlay,
         * and the owner fixed the visible count at two.
         */
        const val MAX_VISIBLE = 2

        /**
         * 0.5.1 (`C10`): a local Notice's own lifetime. The overlay shows refusals ("no session", "busy") outside any
         * turn, so `TurnIdle` never arrives for them; the model expires them on its clock instead. 3.5 s is inside
         * the owner's 3–4 s window.
         */
        const val NOTICE_DISMISS_AFTER_MILLIS = 3_500L

        const val ENTER_MILLIS = 200L
        const val SUPERSEDE_FADE_MILLIS = 180L
        const val KEEP_MILLIS = 2_000L
        const val FADE_MILLIS = 250L
        const val HIDE_DELAY_MILLIS = 3_000L
        const val STACK_FADE_MILLIS = 300L
    }
}
