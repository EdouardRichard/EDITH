package edith.agent

/** Who answered a confirmation. `NONE` is "no answer was ever consumed". */
enum class ConfirmationSource { PHONE, VOICE, TIMEOUT, CANCEL, NONE }

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
}
