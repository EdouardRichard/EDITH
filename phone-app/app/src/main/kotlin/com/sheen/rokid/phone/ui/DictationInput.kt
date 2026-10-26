package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.audio.DictationFinal
import com.sheen.rokid.phone.audio.DictationStartResult
import com.sheen.rokid.phone.audio.DictationState

/**
 * 0.4 Task 12b-ii (spec §5.3, D204): the WAKE input row's dictation decisions, as pure functions.
 *
 * `TypedInputRow` in `PhoneScreen.kt` only draws these; the decisions live here because the module has no Compose UI
 * test dependency. The rules:
 *
 * - a final transcript is **appended** to the editable draft and never sent - the wearer still presses 发送;
 * - the microphone is enabled only for a live session (`Ready`/`Listening`), the same rule as typing, and its Chinese
 *   `contentDescription` switches between 开始 and 停止 with the lease;
 * - the status line shows the dictation state while it lasts, then falls back to the ordinary send status.
 */

/** The microphone's accessibility label when a tap starts a dictation. */
const val DICTATION_START_DESCRIPTION = "开始语音输入"

/** The microphone's accessibility label when a tap stops the running dictation. */
const val DICTATION_STOP_DESCRIPTION = "停止语音输入"

/** Status while the phone microphone is being transcribed. */
const val DICTATION_LISTENING_LABEL = "正在听写…"

/** Status when the dictation ended without a transcript (stop with no speech, or the 15 s cap). */
const val DICTATION_NO_SPEECH_LABEL = "没有听到内容，请重试"

/** Status when the dictation failed (for example the recorder died); the stable code is not shown on the page. */
const val DICTATION_FAILED_LABEL = "听写失败，请重试"

/** Status when a start was refused because a dictation/wake turn still owns the source. */
const val DICTATION_BUSY_LABEL = "上一段语音还没结束，请稍后再试"

/** Status when a start was refused because there is no live session under which to record. */
const val DICTATION_NO_SESSION_LABEL = "会话未开始，请开始会话后重试"

/** Status when `RECORD_AUDIO` is missing; it names the permission so the wearer knows what to allow. */
const val DICTATION_PERMISSION_LABEL = "需要麦克风权限，请在系统设置中允许后重试"

/** Status when no armed phone source exists or the capture refused to start. */
const val DICTATION_NO_SOURCE_LABEL = "麦克风暂不可用，请稍后重试"

/** Status when the composition cannot guarantee the 15 s cap because it has no deadline scheduler. */
const val DICTATION_NOT_READY_LABEL = "听写服务未就绪，请稍后重试"

/** Status when the controller failed to install the redirect/deadline after the route was prepared. */
const val DICTATION_START_FAILED_LABEL = "语音输入启动失败，请重试"

/** Status when the composition is already shut down. */
const val DICTATION_CLOSED_LABEL = "应用已关闭，请重新打开"

/**
 * Appends one delivered final to the editable draft (spec §5.3: 最终 transcript 追加到输入框，已有文字后空格分隔).
 *
 * A blank final leaves the draft untouched. Existing trailing whitespace is collapsed to exactly one separating space,
 * and the result is capped by the field's own [TYPED_DRAFT_MAX_CHARS]. This function never sends anything.
 */
fun appendDictation(draft: String, text: String): String {
    val dictated = text.trim()
    if (dictated.isEmpty()) return draft
    val combined = if (draft.isBlank()) dictated else draft.trimEnd() + " " + dictated
    return typedDraftAfterInput(combined)
}

/** Whether the session is live enough to dictate: the same two states that enable 发送. */
fun canDictate(session: SessionState): Boolean =
    session == SessionState.Ready || session == SessionState.Listening

/**
 * Whether [delivered] is a final this page has not appended yet (0.4 Task 12b-ii fix 2, finding 1).
 *
 * [lastAppendedSequence] is the page's **own** counter, reset whenever the page is re-created. The page deliberately
 * does not save it: the host owns the final's lifetime and clears it once consumed, so
 *
 * - `delivered == null` means nothing to append (a consumed final, or a page returning to the tab);
 * - a host that restarted starts its sequence at 1 again, and a fresh page's counter of 0 accepts that final;
 * - the same delivered value collected twice compares equal, so it is appended exactly once.
 */
fun isNewDictationFinal(delivered: DictationFinal?, lastAppendedSequence: Long): Boolean =
    delivered != null && delivered.sequence > lastAppendedSequence

/** Whether the row's microphone button is enabled. Kept as its own name so the UI states the rule once. */
fun dictationMicEnabled(session: SessionState): Boolean = canDictate(session)

/** The microphone's Chinese description for the current lease state. */
fun dictationMicDescription(state: DictationState): String = when (state) {
    DictationState.Listening -> DICTATION_STOP_DESCRIPTION
    else -> DICTATION_START_DESCRIPTION
}

/** The dictation status line, or null while there is no dictation state to show. */
fun dictationStatusText(state: DictationState): String? = when (state) {
    DictationState.Idle -> null
    DictationState.Listening -> DICTATION_LISTENING_LABEL
    DictationState.NoSpeech -> DICTATION_NO_SPEECH_LABEL
    is DictationState.Error -> DICTATION_FAILED_LABEL
}

/**
 * The Chinese, retryable status for a refused start, or null while nothing was refused (0.4 Task 12b-ii fix 2,
 * finding 4c).
 *
 * [DictationStartResult.Accepted] is the listening case and has no refusal text - the Listening state speaks for it.
 */
fun dictationStartStatusText(result: DictationStartResult?): String? = when (result) {
    null, DictationStartResult.Accepted -> null
    DictationStartResult.Busy -> DICTATION_BUSY_LABEL
    DictationStartResult.NoSession -> DICTATION_NO_SESSION_LABEL
    DictationStartResult.Permission -> DICTATION_PERMISSION_LABEL
    DictationStartResult.NoSource -> DICTATION_NO_SOURCE_LABEL
    DictationStartResult.NoScheduler -> DICTATION_NOT_READY_LABEL
    DictationStartResult.StartFailed -> DICTATION_START_FAILED_LABEL
    DictationStartResult.Closed -> DICTATION_CLOSED_LABEL
}

/**
 * The composer's one status line, or null when there is nothing to say.
 *
 * Precedence: a live/terminal dictation state, then a refused start's explanation, then the typed send status. A
 * dictation result is transient and about the last action; a refusal is equally transient: it explains the tap that
 * did nothing, and once the host dismisses it (the next draft edit, send or session change) the ordinary status is
 * correct again. The redesign removed the old always-on idle hint, so this can now return null while the composer is
 * usable and quiet.
 */
fun inputRowStatusText(
    session: SessionState,
    typed: TypedInputState,
    dictation: DictationState,
    refusal: DictationStartResult? = null,
): String? =
    dictationStatusText(dictation) ?: dictationStartStatusText(refusal) ?: typedStatusText(session, typed)
