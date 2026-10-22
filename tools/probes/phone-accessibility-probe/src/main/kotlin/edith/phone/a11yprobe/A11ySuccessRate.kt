package edith.phone.a11yprobe

/** The two ways to drive another app's UI, which T3b must compare rather than blend. */
enum class A11yMode { NODE_CLICK, COORDINATE_TAP }

/** One attempt. [failure] is null on success and a stable short reason otherwise, so failures can be grouped. */
data class A11yRun(val attempt: Int, val ok: Boolean, val mode: A11yMode, val failure: String?)

/**
 * Integer counts, never a pre-computed rate: the device produced facts, and a rate would bake in a rounding decision
 * before anyone has seen the numbers.
 */
data class A11ySummary(
    val nodeSuccess: Int,
    val nodeRuns: Int,
    val coordSuccess: Int,
    val coordRuns: Int,
    val failures: Map<String, Int>,
)

fun summarize(runs: List<A11yRun>): A11ySummary {
    val node = runs.filter { it.mode == A11yMode.NODE_CLICK }
    val coordinate = runs.filter { it.mode == A11yMode.COORDINATE_TAP }
    return A11ySummary(
        nodeSuccess = node.count { it.ok },
        nodeRuns = node.size,
        coordSuccess = coordinate.count { it.ok },
        coordRuns = coordinate.size,
        failures = runs.mapNotNull { it.failure }.groupingBy { it }.eachCount(),
    )
}

/**
 * Whether an action actually did something.
 *
 * Effectiveness is a **change of screen**, never the disappearance of a label. The first real run judged both modes by
 * "is the target text still on screen", and tapping into 「网络和互联网」 opens a page whose *title is*
 * 「网络和互联网」 — successful navigation was scored as failure for all 20 attempts. Comparing the whole visible screen
 * before and after cannot make that mistake.
 */
fun isActionEffective(performed: Boolean, before: String, after: String): Boolean =
    performed && before != after

/**
 * The failure name to record, or null on success.
 *
 * [rejection] names the mechanism that refused, because "the gesture was never dispatched" and "the gesture was
 * dispatched and changed nothing" need different fixes — the first is a capability question, the second a targeting one.
 */
fun failureFor(
    performed: Boolean,
    before: String,
    after: String,
    rejection: String = "performAction(ACTION_CLICK) returned false",
): String? = when {
    !performed -> rejection
    before == after -> "no screen change"
    else -> null
}
