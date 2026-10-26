package com.sheen.rokid.phone.audio

/**
 * One dictation final handed to the input row (0.4 Task 12b-ii, spec §5.3 D204).
 *
 * [sequence] is monotonic across the **composition's** whole life, not the process's or the page's. The final is
 * delivered by the host and stays in [DictationUiState] until the page that appended it acknowledges delivery with
 * `HostCompositionRoot.consumeDictationFinal`, which clears it. That ownership is what makes the page's lifetime
 * irrelevant (0.4 Task 12b-ii fix 2, finding 1):
 *
 * - a **tab switch** disposes the page but not the host: a final nobody consumed is still there for the page that
 *   returns, and one already consumed is gone - so returning never inserts the same words twice;
 * - **process death** creates a new composition whose sequence starts at 1 again. The page deliberately keeps no
 *   `rememberSaveable` sequence, so the new lifetime's first final is appended instead of being compared against the
 *   dead composition's count and dropped.
 *
 * The text is held in memory only - it is never logged, persisted or sent anywhere by the host.
 */
data class DictationFinal(val sequence: Long, val text: String)

/**
 * How one dictation start press ended (0.4 Task 12b-ii fix 2, finding 4c).
 *
 * [Accepted] means the lease is listening. Every other entry is a **retryable** refusal with its own cause, returned
 * to the caller and published in [DictationUiState.refusal] so the input row can say what to do next instead of a tap
 * that appears to do nothing:
 *
 * - [Busy] - this composition already holds a dictation, a wake request is being transcribed, or a previous lease is
 *   still cleaning up;
 * - [NoSession] - there is no live (`Ready`/`Listening`) session to dictate under;
 * - [Permission] - `RECORD_AUDIO` is not granted (the row also requests it);
 * - [NoSource] - no armed audio epoch, no phone ingress, or the phone capture refused to start;
 * - [NoScheduler] - the composition has no deadline scheduler, so the 15 s cap cannot be guaranteed;
 * - [StartFailed] - the controller refused to install its redirect/deadline after the route was prepared;
 * - [Closed] - the composition itself is shut down.
 */
enum class DictationStartResult {
    Accepted,
    Busy,
    NoSession,
    Permission,
    NoSource,
    NoScheduler,
    StartFailed,
    Closed,
}

/**
 * The dictation state the assistant page renders (0.4 Task 12b-ii, spec §5.3 D204).
 *
 * [state] is the [DictationState] the controller leases through; [final] keeps the most recent delivered final until
 * the page consumes it (see [DictationFinal]); [refusal] is the retryable reason of the last refused start, cleared by
 * the next start attempt, send, draft edit or session change (`HostCompositionRoot.clearDictationStatus`). The text is
 * in memory only.
 */
data class DictationUiState(
    val state: DictationState = DictationState.Idle,
    val final: DictationFinal? = null,
    val refusal: DictationStartResult? = null,
)
