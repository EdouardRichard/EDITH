package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.TypedSendResult

/**
 * The WAKE conversation input row's pure decisions (0.4 Task 11, spec §5.1).
 *
 * The module has no Compose UI test dependency, so the row's behaviour lives here as data + functions and
 * `TypedInputRow` in `PhoneScreen.kt` only draws it. Everything in this file is JVM-testable and none of it reads the
 * session identity: the composition's `requestTypedMessage` owns the `currentIdentity()` check, the page must not.
 */

/** The field's hard cap; the 0.4 Task 11 brief fixes the limit at 2000 characters. */
const val TYPED_DRAFT_MAX_CHARS = 2000

/** The field's label; Chinese because the whole page is (spec §5.1 可访问性). */
const val TYPED_INPUT_LABEL = "输入消息"

/** The send button's visible label. It also serves as its accessibility label, so no separate description is added. */
const val TYPED_SEND_LABEL = "发送"

/** The status line when there is neither a refusal nor a blocking fact. */
const val TYPED_IDLE_LABEL = "输入后点「发送」"

/**
 * The microphone's Chinese description. The control is visible but disabled in this task; the description still names
 * what it will do, so a screen reader does not meet an unlabelled dead button (Task 12b wires it).
 */
const val MIC_DICTATION_DESCRIPTION = "语音输入（听写功能即将提供）"

/**
 * Whether the microphone is wired. False in Task 11: dictation (Task 12b) owns the listener; this task only reserves
 * the control and its accessibility label, so the row's layout does not move when it lands.
 */
const val MIC_DICTATION_ENABLED = false

/** The input row's page-recreation state: the draft, whether the one synchronous send is running, and its result. */
data class TypedInputState(
    val draft: String = "",
    val sending: Boolean = false,
    val result: TypedSendResult? = null,
)

/**
 * The state while the host call runs, with the previous result cleared.
 *
 * `requestTypedMessage` is synchronous, so this state is visible only for that call — but it is what disables the
 * send button and says 正在发送…, and it keeps a double tap from submitting twice.
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
 * Applies the field's length cap to a candidate value.
 *
 * Truncating (rather than rejecting the whole edit) also keeps a long paste usable: the first 2000 characters survive.
 */
fun typedDraftAfterInput(candidate: String): String = candidate.take(TYPED_DRAFT_MAX_CHARS)

/** The only two states in which the host can accept a typed WAKE message (spec §5.1 可用性). */
private fun sessionAcceptsTypedInput(session: SessionState): Boolean =
    session == SessionState.Ready || session == SessionState.Listening

/**
 * Whether the send button is enabled: a live session, something to send, and no send already in flight.
 *
 * `blank` is the caller's `draft.isBlank()`, not `isEmpty()`, so whitespace is refused locally exactly like the
 * composition's own trim-then-blank check. The composition re-checks blank and busy; this is the UI's honest pre-check.
 */
fun canSendTyped(session: SessionState, blank: Boolean, sending: Boolean): Boolean =
    !sending && !blank && sessionAcceptsTypedInput(session)

/**
 * Why the send button is disabled, in one short Chinese sentence, or null when it is enabled.
 *
 * The session reason is checked before blank: a wearer who has typed nothing and not started a session is told the
 * step that unblocks both. Sending is checked first because it is the transient, self-clearing fact.
 */
fun typedSendBlockedReason(session: SessionState, blank: Boolean, sending: Boolean): String? = when {
    sending -> "正在发送…"
    !sessionAcceptsTypedInput(session) -> "请先开始会话"
    blank -> "输入内容后可发送"
    else -> null
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
 * The status line: the last send's outcome while it exists, otherwise the reason send is disabled, otherwise the idle
 * hint. A result wins over the disabled reason so a refused send's explanation is not immediately replaced by
 * "输入内容后可发送" just because the draft is still there.
 */
fun typedStatusText(session: SessionState, state: TypedInputState): String =
    typedResultLabel(state.result)
        ?: typedSendBlockedReason(session, state.draft.isBlank(), state.sending)
        ?: TYPED_IDLE_LABEL

/**
 * The input row is part of the WAKE conversation view only (spec §5.1).
 *
 * It is deliberately independent of the detached switch: the requirement is that it is always rendered on WAKE, so
 * "should I show the row?" must not grow a second input that could hide it in detached mode.
 */
fun showsTypedInputRow(route: AssistantRoute): Boolean = route == AssistantRoute.WAKE

/**
 * What `rememberSaveable` stores for the row: the draft and the last result's name.
 *
 * `sending` is deliberately not stored: the host call is synchronous, so a recreated page can never have a send to
 * resume, and claiming one would disable the button forever.
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
