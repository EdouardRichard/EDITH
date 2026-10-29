package com.sheen.rokid.phone.overlay

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase

/**
 * 0.5: one thing the phone-only message stack should show.
 *
 * The events mirror the `ConversationDisplay` calls the plugin makes for the glasses, but they carry only what a bubble
 * needs (kind, label, text) and no wire identity. They are app-internal: the public plugin contract is unchanged.
 *
 * The route is kept so the repository can refuse the PROACTIVE route (the phone-only model has no proactive UI); the
 * overlay only ever shows the WAKE conversation. Turn/hint ids are nullable because the legacy `userMessage` /
 * `assistantMessage` entry points predate the 0.24 turn identity.
 */
sealed interface PhoneBubbleEvent {
    val route: AssistantRoute

    /** The wearer's final utterance (or a typed message, which enters the same route). */
    data class User(
        override val route: AssistantRoute,
        val turnId: String?,
        val text: String,
    ) : PhoneBubbleEvent

    /** The agent is working on [turnId]; the repository emits this after a [User] so the ball/bubble can show it. */
    data class Thinking(
        override val route: AssistantRoute,
        val turnId: String?,
    ) : PhoneBubbleEvent

    /** One tool row: START opens it, END updates the same row (the glasses' own rule). */
    data class Tool(
        override val route: AssistantRoute,
        val turnId: String?,
        val label: String,
        val phase: ToolPhase,
    ) : PhoneBubbleEvent

    /** One streaming answer revision: updates the current answer message, never adds a new bubble. */
    data class AnswerPartial(
        override val route: AssistantRoute,
        val turnId: String?,
        val hintId: String?,
        val text: String,
    ) : PhoneBubbleEvent

    /** The final answer for [hintId]: same message as its partials, now final. */
    data class AnswerFinal(
        override val route: AssistantRoute,
        val turnId: String?,
        val hintId: String?,
        val text: String,
    ) : PhoneBubbleEvent

    /** One assistant statement that is not the turn's answer (the explicit-memory acknowledgement). */
    data class Notice(
        override val route: AssistantRoute,
        val text: String,
    ) : PhoneBubbleEvent

    /** The turn reached its idle state; the stack starts its 3 s dismissal clock. */
    data class TurnIdle(
        override val route: AssistantRoute,
    ) : PhoneBubbleEvent
}

/** The one method the display needs; [NONE] is the pre-0.5 behavior. */
fun interface PhoneBubbleFeed {
    fun onEvent(event: PhoneBubbleEvent)

    companion object {
        val NONE = PhoneBubbleFeed { }
    }
}
