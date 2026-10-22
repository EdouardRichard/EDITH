package edith.glasses.coexistence

/**
 * How one attempted wake-word registration ended.
 *
 * `REJECTED_BY_RETURN` and `THREW` are kept apart deliberately: a returned refusal is the SDK answering the question,
 * an exception is the SDK failing to, and what the spec may conclude differs between the two.
 */
enum class WordOutcome { ACCEPTED, THREW, REJECTED_BY_RETURN }

data class WordAttempt(val word: String, val pinyin: String, val outcome: WordOutcome)

data class WordTrial(val attempts: List<WordAttempt>)

fun summarize(trial: WordTrial): Map<WordOutcome, Int> =
    WordOutcome.entries.associateWith { outcome -> trial.attempts.count { it.outcome == outcome } }

/**
 * The speech threshold P2 measured controlled captures against. Kept numerically identical so two probes on the same
 * hardware cannot disagree about what "someone spoke" means.
 */
const val MIN_SPEECH_RMS = 300

/**
 * T1's second answer: can the offline wake-word lexicon and our own microphone capture both carry real audio at once.
 *
 * [reason] is `"offline"` when only the capture worked, `"capture"` when only the lexicon fired, `"silent"` when the
 * capture delivered its byte rate but no level (the stolen-microphone shape), `"both"` when neither worked, and empty
 * on success. Naming the failing path is the point: "T1 failed" alone would not say which half of the design to change.
 */
data class ContentionVerdict(val ok: Boolean, val reason: String)

fun contention(
    offlineTriggers: Int,
    captureBytes: Long,
    captureRms: Int,
    minSpeechRms: Int = MIN_SPEECH_RMS,
): ContentionVerdict {
    require(offlineTriggers >= 0) { "OFFLINE_TRIGGERS_MUST_NOT_BE_NEGATIVE" }
    require(captureBytes >= 0) { "CAPTURE_BYTES_MUST_NOT_BE_NEGATIVE" }
    require(captureRms >= 0) { "CAPTURE_RMS_MUST_NOT_BE_NEGATIVE" }

    val offlineOk = offlineTriggers > 0
    val captureOk = captureBytes > 0 && captureRms >= minSpeechRms
    if (offlineOk && captureOk) return ContentionVerdict(ok = true, reason = "")

    val reason = when {
        !offlineOk && !captureOk -> "both"
        !offlineOk -> "offline"
        captureBytes == 0L -> "capture"
        else -> "silent"
    }
    return ContentionVerdict(ok = false, reason = reason)
}
