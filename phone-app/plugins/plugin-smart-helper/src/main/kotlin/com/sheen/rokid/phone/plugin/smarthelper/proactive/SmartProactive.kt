package com.sheen.rokid.phone.plugin.smarthelper.proactive
import com.sheen.rokid.phone.plugin.smarthelper.memory.score
import com.sheen.rokid.phone.plugin.smarthelper.memory.normalize

import com.sheen.rokid.contracts.AcousticLabel

/**
 * How sensitive the proactive path is.
 *
 * The dial is the **shortest transcript worth a model request**: a six-character utterance is routine small talk at
 * `LOW` and a plausible request at `HIGH`. There is deliberately no speech-duration half: the plugin receives a
 * transcript, not an audio timeline, so a millisecond threshold here would be a number nothing could ever measure -
 * the acoustic labeler already applies its own hold windows before a label is reported at all.
 */
internal enum class ProactiveSensitivity(val minTranscriptChars: Int) {
    LOW(12),
    MEDIUM(8),
    HIGH(4),
}

/**
 * Why a proactive trigger did not become a request. The names are the log vocabulary (`P3_PROACTIVE skip reason=…`),
 * so they are part of the observable contract, not just internal bookkeeping.
 *
 * - [DISARMED] the session's 「主动辅助」 switch is off.
 * - [BUSY] a turn is already in flight (a wake turn, or another proactive one).
 * - [NOISY] the acoustic label says the environment is steady noise, not speech.
 * - [VAGUE] the utterance carries no interpretable request (P3.5 §W3's feature rule; this replaced the old `SHORT`
 *   length gate, which refused 141 of 210 corpus rows - most of them real short questions).
 * - [COOLDOWN] too soon after the last request, or after a refusal that started the cooldown.
 * - [DEDUP] this sentence (about this foreground app) was already handled recently.
 *
 * There is deliberately **no** `SHORT` and **no** upload-gate reason any more: with the length gate replaced by the
 * feature rule, no candidate is refused for being short, and with `D53`① removing the session upload consent, "the gate
 * refused" is not a state the proactive path can reach. The vocabulary is the device log's contract
 * (`P3_PROACTIVE skip reason=…`), so a reason that can no longer happen must not stay wordable - `SHORT` was removed
 * for exactly the reason `GATE` was.
 */
internal enum class ProactiveSkip { DISARMED, BUSY, NOISY, VAGUE, COOLDOWN, DEDUP }

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
 * happened afterwards ([noteRequest], [noteDedup]). Nothing here calls a model, so a refusal costs nothing and the
 * expensive path is only reached by an admitted trigger.
 *
 * Rules, in the order they are applied - the order matters because it decides the *reason* when several apply:
 * `DISARMED -> BUSY -> NOISY -> VAGUE -> COOLDOWN -> DEDUP`.
 *
 * The content judgement is **P3.5 §W3's feature rule** ([admitByFeatures]), not a length threshold: length is one weak
 * signal among several, so a short real question ("现在几点了") is admitted while a longer piece of reported speech
 * ("他说他儿子发烧了") is not. The sensitivity dial still supplies the length signal's boundary.
 *
 * The cooldown is a **sliding** window: a trigger refused by [ProactiveSkip.COOLDOWN] or [ProactiveSkip.DEDUP]
 * restarts it. So while the user keeps talking the assistant keeps quiet (and reports one skip per utterance, which
 * is exactly what the acceptance log reads), and the window only runs out once the conversation pauses. [DISARMED],
 * [BUSY], [NOISY] and [VAGUE] deliberately do **not** touch it: the first is not an attempt, and the other three are
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

    /**
     * The length boundary this filter was configured with.
     *
     * Exposed so the plugin's explainability line can re-read the same rule it just applied (`P3_PROACTIVE admit
     * score=… features=…`) without a second source of truth for the number: a value copied into the plugin would drift
     * from the one `admit` used.
     */
    val minTranscriptCharsBoundary: Int get() = minTranscriptChars
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
        // P3.5 §W3: the content judgement is the feature rule, not a length gate. `minTranscriptChars` survives only as
        // the LENGTH feature's boundary, which is why a short real question now gets through.
        if (!admitByFeatures(trigger.transcript, trigger.foreground, minTranscriptChars).admitted) {
            return ProactiveSkip.VAGUE
        }
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

    /**
     * An attempt was **refused** - before any HTTP (a capability the model client will not grant) or by the transport.
     *
     * Same effect as [noteRequest] on the cooldown, and for the measured reason: without it a profile the client keeps
     * refusing would make **every** final utterance log `P3_PROACTIVE request` and attempt a call, instead of one
     * attempt per cooldown window. `D53`① removed the *upload gate*, so this is no longer about consent - it is about
     * not re-deciding on every sentence when the answer cannot change that fast.
     */
    fun noteRefused(nowNanos: Long) { synchronized(lock) { cooldownStartedNanos = nowNanos } }

    /** The hint this trigger produced must not be produced again for the same (sentence, app) within its TTL. */
    fun noteDedup(trigger: ProactiveTrigger) {
        synchronized(lock) { seen[key(trigger)] = trigger.nowNanos + dedupTtlNanos }
    }

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
