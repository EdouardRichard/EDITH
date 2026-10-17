package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.app.HostCompositionRoot

/**
 * What the notification's buttons may reach, and nothing more.
 *
 * The receiver is registered in the manifest, so it is reachable from outside this App's own process: the narrowest
 * useful port is deliberate - it can answer one question and stop the session, and it can do nothing else, because that
 * is the entire vocabulary of a confirmation tap (spec §17.4). The composition installs itself here in `create`, and
 * clears it on close.
 */
internal interface ConfirmationActionPort {
    /** The on-screen card's own answer path; true when this settled the question that is actually up. */
    fun answer(requestId: String, approved: Boolean): Boolean

    /** 急停: withdraw every open question as denied and cancel the turn that is in flight. */
    fun stop()
}

/** The narrow port the manifest receiver routes into: the composition's own answer path, and nothing else. */
internal fun confirmationActionPort(composition: HostCompositionRoot): ConfirmationActionPort =
    object : ConfirmationActionPort {
        override fun answer(requestId: String, approved: Boolean): Boolean =
            composition.answerConfirmation(requestId, approved)

        override fun stop() = composition.emergencyStop()
    }

/** What a tap did, so the caller knows what has to come off the shade. */
internal enum class ConfirmationActionOutcome {
    /** One question was answered; that notification comes down. */
    SETTLED,

    /** 急停 ran; every question (and its notification) comes down. */
    STOPPED,

    /** Nothing was settled - the tap was malformed, unknown, or arrived with no composition alive. */
    IGNORED,
}

/**
 * Routes one notification tap.
 *
 * The three-way answer is the safety property: [ConfirmationActionOutcome.IGNORED] is what a tap gets when there is no
 * composition to answer with, and an ignored tap is emphatically **not** an approval - the question it belonged to
 * expired with its 10 s window. [log] carries the action, the request id and the outcome, never the summary (§17.5).
 */
internal fun routeConfirmationAction(
    action: String?,
    requestId: String?,
    port: ConfirmationActionPort?,
    log: (String) -> Unit = { P3Log.line(it) },
): ConfirmationActionOutcome = when (action) {
    ConfirmationNotifier.ACTION_STOP -> {
        if (port == null) {
            log("P3_CONFIRM_NOTIFY action=stop request=${requestId.orEmpty()} delivered=false reason=NO_COMPOSITION")
            ConfirmationActionOutcome.IGNORED
        } else {
            port.stop()
            log("P3_CONFIRM_NOTIFY action=stop request=${requestId.orEmpty()} delivered=true")
            ConfirmationActionOutcome.STOPPED
        }
    }

    ConfirmationNotifier.ACTION_ALLOW, ConfirmationNotifier.ACTION_DENY -> {
        val id = requestId?.trim()
        when {
            id.isNullOrEmpty() -> {
                log("P3_CONFIRM_NOTIFY action=answer request=- delivered=false reason=MISSING_REQUEST")
                ConfirmationActionOutcome.IGNORED
            }
            port == null -> {
                log("P3_CONFIRM_NOTIFY action=answer request=$id delivered=false reason=NO_COMPOSITION")
                ConfirmationActionOutcome.IGNORED
            }
            else -> {
                val approved = action == ConfirmationNotifier.ACTION_ALLOW
                val settled = port.answer(id, approved)
                log("P3_CONFIRM_NOTIFY action=${if (approved) "allow" else "deny"} request=$id delivered=$settled")
                if (settled) ConfirmationActionOutcome.SETTLED else ConfirmationActionOutcome.IGNORED
            }
        }
    }

    else -> {
        log("P3_CONFIRM_NOTIFY action=${action.orEmpty()} request=${requestId.orEmpty()} delivered=false reason=UNKNOWN_ACTION")
        ConfirmationActionOutcome.IGNORED
    }
}
