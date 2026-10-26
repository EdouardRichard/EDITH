package com.sheen.rokid.phone.audio

/**
 * One dictation final handed to the input row (0.4 Task 12b-ii, spec §5.3 D204).
 *
 * [sequence] is monotonic across the composition's whole life. The UI uses it, not the text, to decide whether it has
 * already appended this result: a recomposition, a page recreation or a second collect of the same `StateFlow` value
 * must not duplicate the wearer's words into the draft.
 */
data class DictationFinal(val sequence: Long, val text: String)

/**
 * The dictation state the assistant page renders (0.4 Task 12b-ii, spec §5.3 D204).
 *
 * [state] is the [DictationState] the controller leases through; [final] keeps the most recent delivered final so a
 * page that returns to the foreground can still append it once (the UI tracks [DictationFinal.sequence]). The text is
 * held in memory only - it is never logged, persisted or sent anywhere by the host.
 */
data class DictationUiState(
    val state: DictationState = DictationState.Idle,
    val final: DictationFinal? = null,
)
