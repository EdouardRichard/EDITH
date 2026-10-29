package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.TypedSendResult
import com.sheen.rokid.phone.audio.DictationState
import com.sheen.rokid.phone.audio.DictationTarget

/**
 * The WAKE conversation composer's pure decisions (0.4 Task 11, spec §5.1).
 *
 * The module has no Compose UI test dependency, so the composer's behaviour lives here as data + functions and
 * `TypedInputRow` in `PhoneScreen.kt` only draws it. Everything in this file is JVM-testable and none of it reads the
 * session identity: the composition's `requestTypedMessage` owns the `currentIdentity()` check, the page must not.
 *
 * The owner's 2026-10-27 redesign (option A) replaced the old text + mic + send row with one pill and one circular
 * action. The rules that changed are decisions, so they are pinned here: which action the slot offers
 * ([composerActionSlot]), what its accessibility description says ([composerActionDescription]), and when the status
 * line exists at all ([typedStatusText] - absent while there is nothing to say, unlike the old permanent hint).
 */

/** The field's hard cap; the 0.4 Task 11 brief fixes the limit at 2000 characters. */
const val TYPED_DRAFT_MAX_CHARS = 2000

/** The composer's placeholder: the only permanent prompt in the field. */
const val TYPED_INPUT_PLACEHOLDER = "发消息…"

/** The send action's accessibility label. The action is icon-only, so this is its only visible-to-a-reader text. */
const val TYPED_SEND_LABEL = "发送"

/** The status line while the one synchronous send is in flight. */
const val TYPED_SENDING_LABEL = "正在发送…"

/** The status line while no live session can accept typed input. */
const val TYPED_NO_SESSION_LABEL = "请先开始会话"

/**
 * Whether the microphone is wired. True since 0.4 Task 12b-ii: the composer starts/stops a phone-microphone dictation
 * (see `DictationInput.kt`). Kept as the one named switch so the composer's history stays explicit.
 */
const val MIC_DICTATION_ENABLED = true

/** The input row's page-recreation state: the draft, whether the one synchronous send is running, and its result. */
data class TypedInputState(
    val draft: String = "",
    val sending: Boolean = false,
    val result: TypedSendResult? = null,
)

/** The one action the composer's trailing circular slot currently offers. */
enum class ComposerAction { Microphone, Send, Stop }

/**
 * What the composer's action slot draws and whether a tap does anything.
 *
 * There is deliberately no `Disabled` action: the slot keeps its contextual icon (microphone for a blank draft, send
 * arrow for text, stop square while listening) and only greys it out. That is what keeps the row to **one** control:
 * the wearer never sees a second button appear or disappear as the session changes.
 */
data class ComposerActionSlot(
    val action: ComposerAction,
    val enabled: Boolean,
)

/**
 * The state while the host call runs, with the previous result cleared.
 *
 * `requestTypedMessage` is synchronous, so this state is visible only for that call — but it is what disables the
 * send action and says 正在发送…, and it keeps a double tap from submitting twice.
 */
fun TypedInputState.beginSend(): TypedInputState = copy(sending = true, result = null)

/**
 * The state after the composition answered (spec §5.1).
 *
 * Accepted clears the draft: the host took the event, so keeping the text would invite an accidental duplicate.
 * Blank/NoSession/Busy/Stale keep it: every one of those is a retryable refusal, and losing the wearer's words would
 * be the worst outcome of a busy assistant.
 */
fun TypedInputState.finishSend(result: TypedSendResult): TypedInputState = copy(
    draft = if (result == TypedSendResult.Accepted) "" else draft,
    sending = false,
    result = result,
)

/**
 * The composer state after the session it belongs to moved (0.4 Task 13e-prep).
 *
 * A [TypedInputState.result] is the answer to one send in the session that was live when it happened. Once that
 * session stops (leaving `Ready`/`Listening`) or the next one starts (entering them), the sentence describes a session
 * that is gone: because [typedStatusText] prefers a present result over every live fact, keeping it leaves
 * 助手正在处理 (or 会话未运行) on screen indefinitely. [draft] is the wearer's own words and survives both directions;
 * a move inside the live set (`Ready` <-> `Listening`) is the same session handling a wake word, so its result stays.
 *
 * `sending` is deliberately untouched: `requestTypedMessage` is synchronous, so a session change cannot strand a send,
 * and the field is transient by design (see [typedInputSaveValues]).
 */
fun TypedInputState.afterSessionChange(previous: SessionState, current: SessionState): TypedInputState =
    if (sessionAcceptsTypedInput(previous) == sessionAcceptsTypedInput(current)) this else copy(result = null)

/**
 * The composer state after the host delivered one dictation final (0.4 Task 13e-prep moved the transition here).
 *
 * The recognized words are appended to the current draft and never sent, exactly like [appendDictation]; the finished
 * dictation is the wearer's newest action, so an older typed result no longer describes the field. The draft is owned
 * by `PhoneScreen`, so a bottom-tab switch cannot discard either half of this transition.
 */
fun TypedInputState.afterDictationFinal(text: String): TypedInputState =
    copy(draft = appendDictation(draft, text), result = null)

/**
 * Applies the field's length cap to a candidate value.
 *
 * Truncating (rather than rejecting the whole edit) also keeps a long paste usable: the first 2000 characters survive.
 */
fun typedDraftAfterInput(candidate: String): String = candidate.take(TYPED_DRAFT_MAX_CHARS)

/** The only two states in which the host can accept a typed WAKE message (spec §5.1 可用性). */
private fun sessionAcceptsTypedInput(session: SessionState): Boolean =
    session == SessionState.Ready || session == SessionState.Listening

/**
 * Whether the send action is enabled: a live session, something to send, and no send already in flight.
 *
 * `blank` is the caller's `draft.isBlank()`, not `isEmpty()`, so whitespace is refused locally exactly like the
 * composition's own trim-then-blank check. The composition re-checks blank and busy; this is the UI's honest pre-check.
 */
fun canSendTyped(session: SessionState, blank: Boolean, sending: Boolean): Boolean =
    !sending && !blank && sessionAcceptsTypedInput(session)

/**
 * Which single action the trailing circular slot offers, and whether it is live.
 *
 * The decision order is the visual promise of the redesign:
 *
 * 1. a live dictation is always the filled stop square, with or without text, and stays tappable even if the session
 *    state has already moved - a lease must never become unstoppable;
 * 2. any non-blank draft is the send arrow;
 * 3. a blank draft is the microphone.
 *
 * The icon does not vanish when the action is unavailable: a non-live session, an in-flight send, or a disabled
 * microphone merely greys the same circle out, which is why [ComposerActionSlot.enabled] is part of the decision.
 * Terminal dictation outcomes (`NoSpeech`/`Error`) give the microphone back: they end one attempt, not the field.
 *
 * 0.5.1 C3 (`review Important #2`): [dictation] must be the **composer's** view of the shared host flow
 * (`composerDictationView` in `DictationInput.kt`), not the raw `DictationUiState.state`. A BALL lease's Listening state
 * would otherwise turn this slot into Stop and let the page stop a lease it does not own.
 *
 * [owner] is the shared flow's raw owner, passed for the one case the filtered [dictation] cannot express: while the
 * ball holds the lease the microphone stays visible (one control) but is disabled. A tap would only be refused `Busy`,
 * and the page filters BALL-owned refusals, so the wearer would see a control that silently does nothing.
 */
fun composerActionSlot(
    session: SessionState,
    state: TypedInputState,
    dictation: DictationState,
    owner: DictationTarget?,
): ComposerActionSlot = when {
    dictation == DictationState.Listening ->
        ComposerActionSlot(ComposerAction.Stop, enabled = true)

    state.draft.isNotBlank() ->
        ComposerActionSlot(
            ComposerAction.Send,
            enabled = canSendTyped(session, blank = false, sending = state.sending),
        )

    else ->
        ComposerActionSlot(
            ComposerAction.Microphone,
            enabled = MIC_DICTATION_ENABLED &&
                dictationMicEnabled(session) &&
                owner != DictationTarget.BALL &&
                !state.sending,
        )
}

/** The Chinese accessibility description of one action-slot icon. */
fun composerActionDescription(action: ComposerAction): String = when (action) {
    ComposerAction.Microphone -> DICTATION_START_DESCRIPTION
    ComposerAction.Send -> TYPED_SEND_LABEL
    ComposerAction.Stop -> DICTATION_STOP_DESCRIPTION
}

/**
 * One short Chinese label per [TypedSendResult], or null before any send.
 *
 * Each refusal gets its own words because they call for different actions: start a session (`NoSession`), wait
 * (`Busy`), retry after the session moved (`Stale`), or type something (`Blank`). A single "发送失败" would hide that.
 */
fun typedResultLabel(result: TypedSendResult?): String? = when (result) {
    null -> null
    TypedSendResult.Accepted -> "已发送"
    TypedSendResult.Blank -> "内容为空，未发送"
    TypedSendResult.NoSession -> "会话未运行，请先开始会话"
    TypedSendResult.Busy -> "助手正在处理，请稍后再试"
    TypedSendResult.Stale -> "会话已变化，请重试"
}

/**
 * The composer's status line, or null when there is nothing to say.
 *
 * The redesign removed the old permanent 输入后点「发送」 hint: an empty or sendable draft is silent, because the
 * placeholder and the green action already say what to do. A result, an in-flight send, or a missing session is a fact
 * the wearer has to act on, so those still speak. Dictation and start refusals are layered on top by
 * [inputRowStatusText] in `DictationInput.kt`.
 */
fun typedStatusText(session: SessionState, state: TypedInputState): String? =
    typedResultLabel(state.result)
        ?: when {
            state.sending -> TYPED_SENDING_LABEL
            !sessionAcceptsTypedInput(session) -> TYPED_NO_SESSION_LABEL
            else -> null
        }

/**
 * The composer is part of the WAKE conversation view only (spec §5.1).
 *
 * It is deliberately independent of the detached switch: the requirement is that it is always rendered on WAKE, so
 * "should I show the composer?" must not grow a second input that could hide it in detached mode.
 */
fun showsTypedInputRow(route: AssistantRoute): Boolean = route == AssistantRoute.WAKE

/**
 * What `rememberSaveable` stores for the composer: the draft and the last result's name.
 *
 * `sending` is deliberately not stored: the host call is synchronous, so a recreated page can never have a send to
 * resume, and claiming one would disable the action forever.
 */
fun typedInputSaveValues(state: TypedInputState): List<String> =
    listOf(state.draft, state.result?.name ?: "")

/**
 * Restores [typedInputSaveValues]; an unknown result name (an enum that changed) becomes "no result" rather than a
 * crash.
 */
fun typedInputRestore(values: List<String>): TypedInputState = TypedInputState(
    draft = values.getOrElse(0) { "" },
    result = values.getOrNull(1)
        ?.takeIf { it.isNotEmpty() }
        ?.let { name -> TypedSendResult.entries.firstOrNull { it.name == name } },
)
