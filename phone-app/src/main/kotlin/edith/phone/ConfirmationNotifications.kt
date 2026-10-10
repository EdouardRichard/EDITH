package edith.phone

/**
 * The phone's notification shade, as the confirmation path needs it (spec §17.4).
 *
 * A narrow port for the same reason `SummaryDisplayer` and `ConfirmationUi` are ports: the rules - when a question is
 * posted, what a missing permission means, what the log may carry - are then testable on the JVM instead of only by
 * looking at a phone. The Android implementation is `AndroidNotificationSurface`.
 */
internal interface NotificationSurface {
    /**
     * False when this App may not post notifications at all (the user never granted `POST_NOTIFICATIONS`, or turned the
     * channel off). A surface that cannot post is a **degradation**: the card and the glasses still carry the question.
     */
    fun available(): Boolean

    /** Posts (or replaces) the one live question. `ttlMillis` is a fallback cleanup, not the decision window. */
    fun post(requestId: String, title: String, text: String, ttlMillis: Long, actions: ConfirmationActions)

    fun cancel(requestId: String)

    fun cancelAll()
}

/** One notification button: the receiver action it carries, and the word the user reads. */
internal data class ConfirmationAction(val id: String, val label: String)

/** The three buttons, in the order they are shown. */
internal class ConfirmationActions(
    val allow: ConfirmationAction,
    val deny: ConfirmationAction,
    val stop: ConfirmationAction,
) {
    val all: List<ConfirmationAction> get() = listOf(allow, deny, stop)
}

/**
 * Puts the live confirmation question in the notification shade, and takes it down again.
 *
 * The split of authority is the same as the card's: the plugin's `ConfirmationGate` decides what silence means, and the
 * host only *presents*. Every notification this posts is removed by [cancel] when the question settles (answer, timeout
 * or 急停), and [ConfirmationNotifier.TTL_MILLIS] only exists so a process that dies mid-question cannot leave a dead
 * question in the shade forever.
 *
 * §17.5 applies to this file's log lines exactly as everywhere else: they name the request and the outcome, never the
 * summary.
 */
internal class ConfirmationNotifier(
    private val surface: NotificationSurface,
    private val log: (String) -> Unit = { P3Log.line(it) },
) {
    /** Returns true when the question reached the shade; false means the card is the only surface (never a refusal). */
    fun post(requestId: String, summary: String): Boolean {
        if (!surface.available()) {
            log("P3_CONFIRM_NOTIFY posted=false request=$requestId reason=PERMISSION_DENIED")
            return false
        }
        surface.post(requestId, TITLE, summary, TTL_MILLIS, ACTIONS)
        log("P3_CONFIRM_NOTIFY posted=true request=$requestId")
        return true
    }

    fun cancel(requestId: String) = surface.cancel(requestId)

    fun cancelAll() = surface.cancelAll()

    companion object {
        /** What the user reads first. The action's own words are in the summary, exactly as on the card. */
        const val TITLE = "需要你确认的动作"

        /**
         * Fallback cleanup only: the plugin's window is what decides, and `hide` removes the notification the moment the
         * question settles. This just has to outlive the question (`SmartConfirmation`'s default window is 10 s) so a
         * killed process cannot leave a stale "approve" in the shade.
         */
        const val TTL_MILLIS = 12_000L

        const val ACTION_ALLOW = "edith.CONFIRM_ALLOW"
        const val ACTION_DENY = "edith.CONFIRM_DENY"
        const val ACTION_STOP = "edith.CONFIRM_STOP"

        val ACTIONS = ConfirmationActions(
            allow = ConfirmationAction(ACTION_ALLOW, "允许"),
            deny = ConfirmationAction(ACTION_DENY, "拒绝"),
            stop = ConfirmationAction(ACTION_STOP, "急停"),
        )
    }
}
