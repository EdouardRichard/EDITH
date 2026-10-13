package edith.plugins.smart.proactive

/**
 * What the intervention gate is shown (spec §4.7).
 *
 * **No time feature, by construction (C10).** Nothing here records *when* — no clock, no dwell, no interval — which
 * is what makes Laya a pure function of the state, which is in turn what makes event-driven evaluation *complete*
 * rather than merely cheap: if the state cannot change without an event, then re-running on a timer could only
 * recompute an answer that is already known. §4.8 records the price: "the world did not change but time passed" is
 * not re-evaluated, and that is a limit of what this state *can represent*, not of how often it is evaluated.
 *
 * **Order is part of the contract.** The publisher's template places the instructions and the candidates *before*
 * the state and cuts an over-long state **at its end**, so the fields the decision depends on most must come first
 * and the ones that can be lost must come last. [KEYS] is that order, and [render] follows it.
 *
 * **The invariant that keeps this honest** is [TRIGGER_EVENTS]: every key names the events that can change it. A
 * field whose answer is "nothing triggers me" is a change the assistant would never react to, so `ProactiveGateTest`
 * asserts the table's key set equals [KEYS] exactly.
 */
class ProactiveGateState private constructor(private val fields: Map<String, String>) {

    /** The state as the model reads it: one `key=value` line per present field, in [KEYS] order. */
    fun render(): String = KEYS.mapNotNull { key -> fields[key]?.let { "$key=$it" } }.joinToString("\n")

    fun value(key: String): String? = fields[key]

    override fun toString() = "ProactiveGateState(fields=${fields.size})"

    companion object {
        /**
         * The input dimensions, most decision-critical first.
         *
         * `transcript` leads because it is the strongest evidence that something needs doing — the wearer said
         * something — and because it is the field that must survive a truncation. The ambient signals follow, and the
         * calendar's long text is last because it is the one that can be lost with the least harm.
         */
        val KEYS = listOf("transcript", "app.foreground", "notification.category", "calendar.todayTitles")

        fun of(
            transcript: String? = null,
            foreground: String? = null,
            notification: String? = null,
            calendar: String? = null,
        ): ProactiveGateState = ProactiveGateState(
            linkedMapOf<String, String>().apply {
                transcript?.takeIf { it.isNotBlank() }?.let { put("transcript", it) }
                foreground?.takeIf { it.isNotBlank() }?.let { put("app.foreground", it) }
                notification?.takeIf { it.isNotBlank() }?.let { put("notification.category", it) }
                calendar?.takeIf { it.isNotBlank() }?.let { put("calendar.todayTitles", it) }
            },
        )
    }
}

/**
 * For every state field, the events that can change it — the spec's "for each field, who wakes me up?".
 *
 * The whole table resolves to two events because the host already aggregates the ambient signals: `CxrContextSignals`
 * publishes `app.foreground`, `notification.category` and `calendar.todayTitles` under **one** `ContextChanged`, and
 * a final utterance arrives as a `TranscriptEvent`. `AcousticEvent` is deliberately absent: it arms the proactive
 * path (spec §16.2) and contributes nothing this state is built from.
 */
val TRIGGER_EVENTS: Map<String, Set<String>> = linkedMapOf(
    "transcript" to setOf("TranscriptEvent"),
    "app.foreground" to setOf("ContextChanged"),
    "notification.category" to setOf("ContextChanged"),
    "calendar.todayTitles" to setOf("ContextChanged"),
)
