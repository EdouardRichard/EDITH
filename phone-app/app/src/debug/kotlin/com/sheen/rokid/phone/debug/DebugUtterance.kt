package com.sheen.rokid.phone.debug
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.contracts.WakeDetected
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.platform.P3Log

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.25 Task 15 (R82 / D116): debug-only **text turn injection**, the input path the acceptance corpus is frozen against.
 *
 * The corpus judgement is about the model acting on a fixed request. A real wake → TTS → ASR path rewrites the request
 * (the project measured 18 injections → 8 wake hits and an ASR final ~5.5 s after the request), so a failed task could
 * not be attributed to the model. This receiver therefore delivers the fixed text as a **final `TranscriptRevision`**
 * through `HostRuntime.onEvent` - the same entry the production ASR pipeline uses - and opens the production turn
 * around it without running KWS or ASR at all. Nothing in the production path changes: release builds do not contain
 * this source set (see `src/debug/AndroidManifest.xml`), and the real wake path still runs for T5.
 *
 * ```
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_UTTERANCE -n com.sheen.rokid.phone/.debug.DebugUtterance \
 *     --es text '把屏幕亮度调到最低' --es route wake --es turn corpus-t2-dry-1
 * ```
 *
 * `route` names which **production turn entry** the final transcript enters through (it is not a bypass):
 * - `wake` (default): a synthetic `WakeDetected` (the event the keyword spotter emits - not the spotter itself)
 *   followed by the final transcript, i.e. the normal conversation turn the corpus exercises;
 * - `help`: the final transcript followed by the production manual `Help` action, which starts the turn immediately
 *   from the last final transcript without a wake.
 * `transcript` submits only the final `TranscriptRevision`, for checking the gate itself.
 *
 * **Evidence discipline.** Logcat receives `P3_UTTERANCE state=… route=… turn=<id> rev=<n> chars=<n>` only: a status, a
 * route name, the fixed turn id, the revision and the character count. The text is never logged, and the turn id is
 * sanitised to `[A-Za-z0-9_-]` so the log line itself cannot become a channel for utterance text.
 */
class DebugUtterance : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? EdithApplication
        val composition = app?.composition
            ?: return report("state=refused reason=NO_COMPOSITION")
        val host = composition.host
        val route = DebugUtteranceInjection.routeOf(intent.getStringExtra(EXTRA_ROUTE))
            ?: return report("state=refused reason=UNKNOWN_ROUTE")
        val revision = revisions.next()
        val turnId = intent.getStringExtra(EXTRA_TURN)?.takeIf { it.isNotBlank() } ?: "debug-utterance-$revision"
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        var outcome: DebugUtteranceInjection.Outcome
        if (route == DebugUtteranceInjection.Route.WAKE) {
            // The production wake path, not a bare host event: `emitWake` is what requests the glasses panel and plays
            // the chime before the host sees the wake. Without that half the conversation cycle's panel wait expires
            // and the turn is cancelled after ~3 s - measured on the K40 at 23:24:59 (`panel_not_shown`, CANCELLED).
            // The detector's own acoustic half is still not run; only its emission sink is.
            composition.emitWake(host, DebugUtteranceInjection.WAKE_WORD, 1.0)
            outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = text,
                route = DebugUtteranceInjection.Route.TRANSCRIPT,
                turnId = turnId,
                revision = revision,
                nowNanos = SystemClock.elapsedRealtimeNanos(),
            )
        } else {
            outcome = DebugUtteranceInjection.deliver(
                host = host,
                text = text,
                route = route,
                turnId = turnId,
                revision = revision,
                nowNanos = SystemClock.elapsedRealtimeNanos(),
            )
        }
        // Counts, ids, a route and the App's own state/error code only. Never the text: a log must not carry it.
        val ui = composition.state.value
        report("state=${outcome.logName} route=${route.logName} turn=${ident(turnId)} rev=$revision " +
            "chars=${text.length} session=${ui.session.name} error=${ui.errorCode ?: "none"}")
    }

    private fun report(fields: String) = P3Log.line("P3_UTTERANCE $fields")

    /** A log-safe identity: the turn id is caller-chosen and must not be able to inject text into the log. */
    private fun ident(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_TURN_CHARS).ifEmpty { "none" }

    companion object {
        /** Explicit action so a stray broadcast cannot start a turn. */
        const val ACTION = "edith.DEBUG_UTTERANCE"

        /** The fixed utterance text, delivered as if ASR had just finalised it. Never logged. */
        const val EXTRA_TEXT = "text"

        /** `wake` (default) / `help` / `transcript`; see the class KDoc. */
        const val EXTRA_ROUTE = "route"

        /** Optional fixed turn id; defaults to `debug-utterance-<revision>` so every injection is identifiable. */
        const val EXTRA_TURN = "turn"

        private const val MAX_TURN_CHARS = 64

        /**
         * One counter for the **process**, not the receiver instance: a manifest receiver is instantiated per broadcast,
         * so an instance field would restart the revision at 1 on every injection and break the monotonic contract.
         */
        internal val revisions = DebugUtteranceRevisions()
    }
}

/**
 * The strictly monotonic revision source for injected transcripts.
 *
 * `TranscriptRevision.revision` has to increase for successive revisions of the same turn; the corpus records the
 * revision of the injected final so the turn can be matched in the evidence. Kept as a tiny value so a JVM test can pin
 * the monotonicity without an Android runtime.
 */
internal class DebugUtteranceRevisions {
    private val counter = AtomicLong()

    fun next(): Long = counter.incrementAndGet()
}

/**
 * The pure half of the injection seam: builds the production events from the host's current identity and submits them
 * with `HostRuntime.onEvent`, and answers a status value instead of throwing.
 *
 * Separate from the receiver so the event shape (identity, turn id, final flag, revision, monotonic timestamps, route
 * order) is pinned by JVM tests while the Android half stays "resolve the composition and read the intent extras".
 */
internal object DebugUtteranceInjection {

    /** The configured wake word, used only for the synthetic wake event's word field. */
    const val WAKE_WORD = "伊迪丝"

    enum class Route(val logName: String) {
        WAKE("wake"),
        HELP("help"),
        TRANSCRIPT("transcript"),
    }

    enum class Outcome(val logName: String) {
        ACCEPTED("accepted"),
        NO_SESSION("no_session"),
        BLANK_TEXT("blank_text"),
        HOST_REFUSED("host_refused"),
    }

    /** `null` when the caller supplied a route this seam does not know; absent/blank means the default `wake`. */
    fun routeOf(raw: String?): Route? = when (raw?.trim()?.lowercase()) {
        null, "" -> Route.WAKE
        "wake" -> Route.WAKE
        "help" -> Route.HELP
        "transcript" -> Route.TRANSCRIPT
        else -> null
    }

    /**
     * Submits one synthetic final utterance and the production entry that turns it into a run.
     *
     * The envelope is built from `HostRuntime.currentIdentity()` rather than from a caller-supplied session: an event
     * under any other identity is dropped by the host's gate, so asking the host is the only honest way to be sure the
     * injection reaches the active plugin. A null identity (nothing active) is refused without submitting anything.
     *
     * [wake] exists because the production `wake` route must go through the composition's own emission sink (it also
     * asks for the glasses panel); when it is null the synthetic `WakeDetected` is submitted directly, which is the
     * shape the JVM tests pin.
     */
    fun deliver(
        host: HostRuntime,
        text: String,
        route: Route,
        turnId: String,
        revision: Long,
        nowNanos: Long,
        wake: (() -> Unit)? = null,
    ): Outcome {
        if (text.isBlank()) return Outcome.BLANK_TEXT
        require(revision >= 0) { "revision must be non-negative" }
        val identity = runCatching { host.currentIdentity() }.getOrNull() ?: return Outcome.NO_SESSION
        val envelope = EventEnvelope(
            sessionId = identity.first,
            generation = identity.second,
            eventId = "debug-utterance-$revision",
            startNanos = nowNanos,
            endNanos = nowNanos,
        )
        val transcript = TranscriptRevision(
            envelope = envelope,
            turnId = turnId,
            revision = revision,
            text = text,
            final = true,
            startNanos = nowNanos,
            endNanos = nowNanos,
        )
        val events: List<HostEvent> = when (route) {
            Route.WAKE -> buildList {
                if (wake != null) wake.invoke() else add(WakeDetected(envelope.copy(eventId = "debug-wake-$revision"), WAKE_WORD, 1.0))
                add(TranscriptEvent(transcript))
            }
            Route.HELP -> listOf(
                TranscriptEvent(transcript),
                UserAction(envelope.copy(eventId = "debug-help-$revision"), UserAction.Action.Help),
            )
            Route.TRANSCRIPT -> listOf(TranscriptEvent(transcript))
        }
        return if (events.all { host.onEvent(it) }) Outcome.ACCEPTED else Outcome.HOST_REFUSED
    }
}
