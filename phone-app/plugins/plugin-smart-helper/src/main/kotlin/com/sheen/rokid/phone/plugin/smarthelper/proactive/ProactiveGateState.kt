package com.sheen.rokid.phone.plugin.smarthelper.proactive

/**
 * What the intervention gate is shown (spec §4.7).
 *
 * **No time feature, by construction (C10).** Nothing here records *when* — no clock, no dwell, no interval — which
 * is what makes Laya a pure function of the state, which is in turn what makes event-driven evaluation *complete*
 * rather than merely cheap: if the state cannot change without an event, then re-running on a timer could only
 * recompute an answer that is already known. §4.8 records the price: "the world did not change but time passed" is
 * not re-evaluated, and that is a limit of what this state *can represent*, not of how often it is evaluated.
 *
 * **The rendered form is the trained contract, and that is a fix, not a style choice (Phase C).** Until Phase C this
 * class rendered `key=value` lines under the host's own signal names (`transcript=…`, `app.foreground=…`). The model
 * was fine-tuned on a **JSON object** with four different keys, in a fixed order —
 * `{"recent_transcript": …, "notification_category": …, "calendar_event": …, "foreground": …}` — pinned by
 * `E:\layaft\tasks\edith-gate.yaml` ("Rendered with json.dumps in this key order, which is the order the deployed
 * host sends") and by the fine-tune spec §2.1. Nothing translated between the two: `LayaPromptBuilder.serializeState`
 * passes a `String` state through verbatim, so the deployed gate was asking the model with a shape it had never seen.
 *
 * That is why there are now **two** key lists. [CONTEXT_KEYS] names the dimensions the way the *host's* events and
 * context values name them (`CxrContextSignals` publishes `app.foreground`, `notification.category`,
 * `calendar.todayTitles`); [KEYS] names the same four dimensions the way the *model* was trained to read them. They
 * are deliberately kept as separate lists — collapsing them would mean renaming one side of a contract that the other
 * side cannot be changed on, which is how the drift happened in the first place.
 *
 * **Bounded construction (fine-tune spec §2.1).** Each field is cut to its own bound *here*, at assembly time, so the
 * serialized state always fits the window. The alternative — letting `LayaPromptBuilder` take the first `room` tokens
 * — trims from the tail and can cut the JSON mid-value, producing a fragment the model has never seen. A missing or
 * over-long field is an explicit decision at construction rather than a silent truncation at inference.
 */
class ProactiveGateState private constructor(private val fields: Map<String, String>) {

    /** The state as the model reads it: a JSON object, one entry per present field, in [KEYS] order. */
    fun render(): String =
        KEYS.mapNotNull { key -> fields[key]?.let { "\"$key\": \"${escapeJson(it)}\"" } }
            .joinToString(prefix = "{", postfix = "}", separator = ", ")

    /** The value under a **contract** key, or null when that dimension carried nothing. */
    fun value(key: String): String? = fields[key]

    override fun toString() = "ProactiveGateState(fields=${fields.size})"

    companion object {
        /**
         * The model's own field names, most decision-critical first.
         *
         * `recent_transcript` leads because it is the strongest evidence that something needs doing — the wearer said
         * something — and because it is the field that must survive a truncation. The ambient signals follow, and the
         * calendar's long text is last because it is the one that can be lost with the least harm.
         */
        val KEYS = listOf("recent_transcript", "notification_category", "calendar_event", "foreground")

        /**
         * The same four dimensions under the names the host's events and context values use.
         *
         * Positionally parallel to [KEYS]. `TRIGGER_EVENTS` is keyed by these, because a delivered event carries
         * these strings; `of` takes them as parameter names for the same reason.
         */
        val CONTEXT_KEYS = listOf("transcript", "app.foreground", "notification.category", "calendar.todayTitles")

        /**
         * How long each field may be, in characters.
         *
         * The window is 256 tokens and the prompt head takes roughly 50 of them, so the state has room to spare —
         * these are a defence, not a budget. They exist so that an unusually long calendar or a runaway transcript
         * degrades into "this field was cut" instead of "the state is not JSON any more".
         */
        private val BOUNDS = mapOf(
            "recent_transcript" to 200,
            "notification_category" to 60,
            "calendar_event" to 120,
            "foreground" to 60,
        )

        fun of(
            transcript: String? = null,
            foreground: String? = null,
            notification: String? = null,
            calendar: String? = null,
        ): ProactiveGateState = ProactiveGateState(
            linkedMapOf<String, String>().apply {
                transcript?.let { put("recent_transcript", bound("recent_transcript", it)) }
                notification?.let { put("notification_category", bound("notification_category", it)) }
                calendar?.let { put("calendar_event", bound("calendar_event", it)) }
                foreground?.let { put("foreground", bound("foreground", it)) }
            }.filterValues { it.isNotBlank() },
        )

        private fun bound(key: String, value: String): String {
            val limit = BOUNDS.getValue(key)
            return if (value.length <= limit) value else value.take(limit)
        }

        /**
         * The escapes `json.dumps(..., ensure_ascii=False)` produces, and no others.
         *
         * CJK is deliberately left as itself: `ensure_ascii=True` would turn every Chinese character into a `\uXXXX`
         * pair, which is a *different token sequence* from the one the corpus was written with — the same class of
         * silent input-shape change this whole fix is about.
         */
        private fun escapeJson(value: String): String = buildString {
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
                }
            }
        }
    }
}

/**
 * For every state field, the events that can change it — the spec's "for each field, who wakes me up?".
 *
 * Keyed by [ProactiveGateState.CONTEXT_KEYS]: these are the strings a delivered event actually carries. The whole
 * table resolves to two events because the host already aggregates the ambient signals: `CxrContextSignals` publishes
 * `app.foreground`, `notification.category` and `calendar.todayTitles` under **one** `ContextChanged`, and a final
 * utterance arrives as a `TranscriptEvent`. `AcousticEvent` is deliberately absent: it arms the proactive path
 * (spec §16.2) and contributes nothing this state is built from.
 */
val TRIGGER_EVENTS: Map<String, Set<String>> = linkedMapOf(
    "transcript" to setOf("TranscriptEvent"),
    "app.foreground" to setOf("ContextChanged"),
    "notification.category" to setOf("ContextChanged"),
    "calendar.todayTitles" to setOf("ContextChanged"),
)
