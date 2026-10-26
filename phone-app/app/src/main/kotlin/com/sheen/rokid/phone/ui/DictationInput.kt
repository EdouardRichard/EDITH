package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.SessionState
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
 * The row's one status line: the dictation state wins while it exists, otherwise the typed send status.
 *
 * A dictation result is transient and about the last action; showing "输入后点「发送」" over it would hide the one
 * outcome the wearer needs to see. Once the state is [DictationState.Idle] the ordinary row status is correct again.
 */
fun inputRowStatusText(session: SessionState, typed: TypedInputState, dictation: DictationState): String =
    dictationStatusText(dictation) ?: typedStatusText(session, typed)
