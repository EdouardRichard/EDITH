package com.sheen.rokid.agent

/**
 * Who answered a confirmation. `NONE` is "no answer was ever consumed"; `UNAVAILABLE` is "there was nobody able to
 * ask" (no host channel, or the host could not present the question), which is not a denial by the wearer.
 */
enum class ConfirmationSource { PHONE, VOICE, TIMEOUT, CANCEL, NONE, UNAVAILABLE }

/** One settled confirmation: whether the action may run, and which channel settled it. */
data class ConfirmationOutcome(val approved: Boolean, val source: ConfirmationSource)

/**
 * The loop's question to the user (spec §17.4).
 *
 * Blocking by design - the loop is synchronous and one turn owns its thread - so an implementation must be safe to call
 * from the turn's thread, must return only when the question is settled (answered, timed out or withdrawn), and must
 * never execute anything itself. A `null` port keeps P3.1's behaviour: such an action is refused with
 * `CONFIRMATION_UNAVAILABLE` and never runs.
 */
interface ConfirmationPort {
    fun request(requestId: String, summary: String): ConfirmationOutcome

    /**
     * Whether a question can be put to the wearer at all right now (0.3 Phase 4B, spec §5.4).
     *
     * Additive with a `true` default: every port written before the availability check keeps its exact blocking
     * behaviour. The loop checks this **before** it calls [request]; `false` means "nobody can be asked", so the action
     * is refused immediately with `CONFIRMATION_UNAVAILABLE` rather than spending the decision window on a question
     * that cannot be presented. A throwing check is treated as unavailable for the same reason - the alternative is a
     * refusal delayed by the full 120 s.
     */
    fun available(): Boolean = true
}
