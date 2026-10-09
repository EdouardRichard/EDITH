package edith.plugins.smart

import edith.contracts.AcousticLabel

/**
 * How sensitive the proactive path is, as a threshold pair rather than a numeric dial.
 *
 * [minSpeechMillis] is the shortest stretch of speech worth considering (the acoustic labeler already holds its own
 * windows; this is the *business* floor) and [minTranscriptChars] is the shortest transcript worth a model request.
 * A six-character utterance is routine small talk at `LOW` and a plausible request at `HIGH`.
 */
internal enum class ProactiveSensitivity(val minSpeechMillis: Long, val minTranscriptChars: Int) {
    LOW(1200, 12),
    MEDIUM(800, 8),
    HIGH(400, 4),
}

/**
 * Why a proactive trigger did not become a request. The names are the log vocabulary (`P3_PROACTIVE skip reason=…`),
 * so they are part of the observable contract, not just internal bookkeeping.
 *
 * - [DISARMED] the session's 「主动辅助」 switch is off.
 * - [BUSY] a turn is already in flight (a wake turn, or another proactive one).
 * - [NOISY] the acoustic label says the environment is steady noise, not speech.
 * - [SHORT] the transcript is below the sensitivity threshold.
 * - [COOLDOWN] too soon after the last request, or after a refusal that started the cooldown.
 * - [DEDUP] this sentence (about this foreground app) was already handled recently.
 * - [GATE] the session's upload consent refused the request, so nothing left the device.
 */
internal enum class ProactiveSkip { DISARMED, BUSY, NOISY, SHORT, COOLDOWN, DEDUP, GATE }

/** One candidate: what was heard, what the acoustic layer called it, what is in front of the user, and when. */
internal data class ProactiveTrigger(
    val transcript: String,
    val label: AcousticLabel?,
    val foreground: String?,
    val nowNanos: Long,
)

/**
 * The cheap filter in front of every proactive model request (spec §5.2, §16.1#4).
 *
 * It is entirely local: [admit] answers whether a trigger may become a request, and the caller notes what actually
 * happened afterwards ([noteRequest], [noteDedup], [noteGateRefused]). Nothing here calls a model, so a refusal costs
 * nothing and the expensive path is only reached by an admitted trigger.
 *
 * Rules, in the order they are applied - the order matters because it decides the *reason* when several apply:
 * `DISARMED -> BUSY -> NOISY -> SHORT -> COOLDOWN -> DEDUP`.
 *
 * The cooldown is a **sliding** window: a trigger refused by [ProactiveSkip.COOLDOWN] or [ProactiveSkip.DEDUP]
 * restarts it. So while the user keeps talking the assistant keeps quiet (and reports one skip per utterance, which
 * is exactly what the acceptance log reads), and the window only runs out once the conversation pauses. [DISARMED],
 * [BUSY], [NOISY] and [SHORT] deliberately do **not** touch it: the first is not an attempt, and the other three are
 * properties of one utterance that a later one may not share.
 *
 * Dedup keys on the normalized transcript **and** the foreground app, so the same sentence about a different app is
 * a different event. The store is bounded ([dedupCapacity], oldest-inserted evicted first) and expires after
 * [dedupTtlNanos], so a long session cannot grow it without bound.
 */
internal class SmartProactive(
    private val cooldownNanos: Long,
    sensitivity: ProactiveSensitivity,
    private val dedupCapacity: Int = 8,
    private val dedupTtlNanos: Long = 600_000_000_000L,
) {
    private val minTranscriptChars = sensitivity.minTranscriptChars
    private var cooldownStartedNanos: Long? = null

    /**
     * Guards every field below. [admit] is called on the host's dispatcher while the `note*` calls arrive from the
     * task that ran the request, so this type is synchronized rather than merely documented as single-threaded: the
     * dedup store is a `LinkedHashMap`, and an unsynchronized shared one loses updates or corrupts under a resize.
     */
    private val lock = Any()

    /** Key to expiry, oldest insertion evicted at [dedupCapacity]. */
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > dedupCapacity
    }

    /** Returns null when the trigger may become a request, or the reason it may not. */
    fun admit(trigger: ProactiveTrigger, armed: Boolean, busy: Boolean): ProactiveSkip? {
        if (!armed) return ProactiveSkip.DISARMED
        if (busy) return ProactiveSkip.BUSY
        if (trigger.label == AcousticLabel.Noisy) return ProactiveSkip.NOISY
        if (normalize(trigger.transcript).length < minTranscriptChars) return ProactiveSkip.SHORT
        synchronized(lock) {
            val started = cooldownStartedNanos
            if (started != null && trigger.nowNanos - started < cooldownNanos) {
                cooldownStartedNanos = trigger.nowNanos
                return ProactiveSkip.COOLDOWN
            }
            if (isDuplicateLocked(trigger)) {
                cooldownStartedNanos = trigger.nowNanos
                return ProactiveSkip.DEDUP
            }
        }
        return null
    }

    /** A request was actually made; the cooldown starts here, not at the trigger. */
    fun noteRequest(nowNanos: Long) { synchronized(lock) { cooldownStartedNanos = nowNanos } }

    /** The hint this trigger produced must not be produced again for the same (sentence, app) within its TTL. */
    fun noteDedup(trigger: ProactiveTrigger) {
        synchronized(lock) { seen[key(trigger)] = trigger.nowNanos + dedupTtlNanos }
    }

    /** The request was refused by the session's upload gate (zero HTTP); do not re-decide on every sentence. */
    fun noteGateRefused(nowNanos: Long) { synchronized(lock) { cooldownStartedNanos = nowNanos } }

    /** Must hold [lock]. */
    private fun isDuplicateLocked(trigger: ProactiveTrigger): Boolean {
        val key = key(trigger)
        val expiry = seen[key] ?: return false
        if (trigger.nowNanos > expiry) {
            seen.remove(key)
            return false
        }
        return true
    }

    private fun key(trigger: ProactiveTrigger): String =
        normalize(trigger.transcript) + "\u0000" + (trigger.foreground ?: "-")

    private fun normalize(transcript: String): String = transcript.trim().replace(WHITESPACE, " ").lowercase()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
