package com.sheen.rokid.phone.plugin.smarthelper.scene

/**
 * The scene policy: the one gate that stops the passive loop from answering a wake it should not answer yet.
 *
 * Spec §4.3 keeps the knowledge / privacy / dilemma behaviour in the versioned prompt catalog, and this class keeps
 * the *timing* half: a wake that arrives within [cooldownNanos] of the previous admitted wake is refused. Without it, one utterance
 * that happens to contain the wake word several times would open several turns for the same thing, and the glasses
 * would be handed several hints for one request.
 *
 * The cooldown is **time-based, not per-turn**: [admit] answers from the last admitted time alone, so a different
 * `turnId` inside the window is refused exactly like a repeat of the same one. [turnId] is therefore accepted for the
 * caller's benefit (it is what the caller has in hand when it decides) and deliberately not part of the decision.
 *
 * [noteTurn] exists separately from [admit] so a caller can ask "may I start?" and only then record the start; the
 * plugin calls the pair together. A refused [admit] never moves the reference time, so a refused wake cannot extend
 * the window for the next one.
 */
internal class ScenePolicy(private val cooldownNanos: Long) {

    init {
        require(cooldownNanos >= 0) { "cooldownNanos must not be negative, was $cooldownNanos" }
    }

    private var lastAdmittedNanos: Long? = null

    /** True when a turn may open at [nowNanos]; false while the previous admitted turn is still inside the cooldown. */
    @Synchronized
    fun admits(nowNanos: Long): Boolean {
        val last = lastAdmittedNanos ?: return true
        return nowNanos - last >= cooldownNanos
    }

    /**
     * The caller-facing form of [admits]: [turnId] is accepted for the caller's benefit and deliberately not part of
     * the decision (the policy is time-based, not per-turn).
     */
    @Synchronized
    fun admit(nowNanos: Long, turnId: String): Boolean = admits(nowNanos)

    /** Records the moment the admitted turn actually opened. */
    @Synchronized
    fun noteTurn(nowNanos: Long) {
        lastAdmittedNanos = nowNanos
    }
}
