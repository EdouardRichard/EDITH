package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.phone.app.EdithApplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Where the notification's three buttons land (spec §17.4).
 *
 * `android:exported="false"` in the manifest is load-bearing and not a formality: an exported receiver would let any
 * App on the phone answer a confirmation on the user's behalf. The tap is routed through [routeConfirmationAction] into
 * the composition's **own** answer path, so a notification answer and an on-screen tap settle the question identically.
 *
 * When no composition is alive the tap settles nothing - the question expired with its window long ago - and the
 * notification comes down so the shade cannot keep offering a button that does nothing.
 */
class ConfirmationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        // `liveComposition`, not `composition`: a tap must never build the App's graph, and with nothing alive there is
        // nothing to answer (the question expired with its window).
        val port = (context.applicationContext as? EdithApplication)?.liveComposition?.let(::confirmationActionPort)
        val outcome = routeConfirmationAction(intent.action, requestId, port)

        val surface = AndroidNotificationSurface(context)
        when {
            outcome == ConfirmationActionOutcome.STOPPED -> surface.cancelAll()
            // Settled: the question is answered, so its notification comes down. No composition at all: the tap names a
            // question that expired long ago, so the shade must stop offering buttons that cannot work.
            outcome == ConfirmationActionOutcome.SETTLED || port == null -> surface.cancel(requestId.orEmpty())
            // Anything else (a stale tap, a malformed one) settles nothing - and must **not** touch the shade: the one
            // notification there belongs to whatever question is live now, which may not be the one this tap named.
            else -> Unit
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "edith.requestId"
    }
}
