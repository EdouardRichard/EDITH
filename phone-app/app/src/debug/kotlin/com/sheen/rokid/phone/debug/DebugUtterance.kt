package com.sheen.rokid.phone.debug
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.platform.P3Log

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.security.MessageDigest
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
 * - `wake` (default, when the extra is absent): calls the composition's production `emitWake`, which asks the glasses
 *   for the resident panel and submits the production `WakeDetected`, then submits the final transcript, i.e. the normal
 *   conversation turn the corpus exercises. `emitWake` is deliberately called **without** the wearer-facing chime lambda
 *   (production passes it from the detector's emission); the corpus judges model behaviour, not the acknowledgement tone.
 * - `help`: the final transcript followed by the production manual `Help` action, which starts the turn immediately
 *   from the last final transcript without a wake.
 * - `transcript` submits only the final `TranscriptRevision`, for checking the gate itself.
 * An explicitly blank route or an unknown route is refused **before** `emitWake`, and so are a blank utterance and a
 * missing session identity: a refused request must never leave a panel open with no turn behind it.
 *
 * **Evidence discipline.** Logcat receives
 * `P3_UTTERANCE state=… route=… turn=<id> rev=<n> chars=<n> textSha256=<hex>` only: a status, a route name, the fixed
 * turn id, the revision, the character count and the UTF-8 SHA-256 of the injected text. The text is never logged, and
 * the turn id is sanitised to ASCII `[A-Za-z0-9_-]` so the log line itself cannot become a channel for utterance text.
 * The digest is what lets the acceptance harness prove the frozen question it broadcast is the question the turn saw.
 */
class DebugUtterance : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? EdithApplication
        val composition = app?.composition
            ?: return report("state=refused reason=NO_COMPOSITION")
        val host = composition.host
        val revision = revisions.next()
        val turnId = intent.getStringExtra(EXTRA_TURN)?.takeIf { it.isNotBlank() } ?: "debug-utterance-$revision"
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        // The exact sequence this instrument runs: validate, ask for the production wake, then submit the transcript.
        // Binding the composition's own `emitWake` here (not a copy of its body) is what keeps the seam honest: the
        // panel request, `WakeDetected` and the plugin's wake admission stay production code.
        val injection = DebugUtteranceInjection.inject(
            host = host,
            text = text,
            rawRoute = intent.getStringExtra(EXTRA_ROUTE),
            turnId = turnId,
            revision = revision,
            nowNanos = SystemClock.elapsedRealtimeNanos(),
            wake = { composition.emitWake(host, DebugUtteranceInjection.WAKE_WORD, 1.0) },
        )
        // Counts, ids, a route, a digest and the App's own state/error code only. Never the text: a log must not carry it.
        val ui = composition.state.value
        report(DebugUtteranceLog.fields(
            state = injection.outcome.logName,
            route = injection.route?.logName,
            turnId = turnId,
            revision = revision,
            text = text,
            session = ui.session.name,
            error = ui.errorCode,
        ))
    }

    private fun report(fields: String) = P3Log.line("P3_UTTERANCE $fields")

    companion object {
        /** Explicit action so a stray broadcast cannot start a turn. */
        const val ACTION = "edith.DEBUG_UTTERANCE"

        /** The fixed utterance text, delivered as if ASR had just finalised it. Never logged. */
        const val EXTRA_TEXT = "text"

        /** `wake` (default when absent) / `help` / `transcript`; see the class KDoc. */
        const val EXTRA_ROUTE = "route"

        /** Optional fixed turn id; defaults to `debug-utterance-<revision>` so every injection is identifiable. */
        const val EXTRA_TURN = "turn"

        /**
         * One counter for the **process**, not the receiver instance: a manifest receiver is instantiated per broadcast,
         * so an instance field would restart the revision at 1 on every injection and break the monotonic contract.
         */
        internal val revisions = DebugUtteranceRevisions()
    }
}

/**
 * The `P3_UTTERANCE` field builder for the injection receiver.
 *
 * Split out so a JVM test can hold the two evidence promises without an Android runtime: the digest is the plain
 * lower-case SHA-256 of the **UTF-8** bytes (the same value the harness computes over the frozen question), and the
 * line carries the character count and the digest in place of the text. Nothing here can log the utterance itself.
 */
internal object DebugUtteranceLog {

    /** Lower-case hex SHA-256 of [text]'s UTF-8 bytes; the only form in which the utterance reaches the log. */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** The `P3_UTTERANCE` fields, in the order the corpus harness greps; [text] enters only as [chars] and [textSha256]. */
    fun fields(
        state: String,
        route: String?,
        turnId: String,
        revision: Long,
        text: String,
        session: String,
        error: String?,
    ): String =
        "state=$state route=${route ?: "none"} turn=${DebugUtteranceInjection.ident(turnId)} rev=$revision " +
            "chars=${text.length} textSha256=${sha256(text)} session=$session error=${error ?: "none"}"
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
 * The pure half of the injection seam: validates the caller's request, builds the production events from the host's
 * current identity and submits them with `HostRuntime.onEvent`, and answers a status value instead of throwing.
 *
 * Separate from the receiver so the event shape (identity, turn id, final flag, revision, monotonic timestamps, route
 * order) is pinned by JVM tests while the Android half stays "resolve the composition and read the intent extras".
 * The `wake` sink is the composition's own `emitWake`, passed by the receiver; [inject] calls it only after every
 * validation has passed, so a refused request cannot open a panel with no turn behind it.
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
        UNKNOWN_ROUTE("unknown_route"),
        HOST_REFUSED("host_refused"),
    }

    /** What one receiver injection did, and under which route (null when the route itself was refused). */
    data class Injection(val route: Route?, val outcome: Outcome)

    /**
     * `null` for an explicitly blank or unknown route; an **absent** extra (`null`) keeps the documented `wake` default.
     *
     * The absent-vs-blank distinction is deliberate: omitting the extra means "the default turn entry", while supplying
     * an empty value is a caller error that must be refused before the panel half runs.
     */
    fun routeOf(raw: String?): Route? = when (raw?.trim()?.lowercase()) {
        null -> Route.WAKE
        "wake" -> Route.WAKE
        "help" -> Route.HELP
        "transcript" -> Route.TRANSCRIPT
        else -> null
    }

    /**
     * A log-safe identity: the turn id is caller-chosen and must not be able to inject text into the log.
     *
     * `Char.isLetterOrDigit()` accepts CJK, so the filter is the explicit ASCII alphabet the KDoc promises - otherwise
     * a Chinese turn id would carry utterance text straight into logcat.
     */
    fun ident(raw: String): String =
        raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
            .take(MAX_TURN_CHARS)
            .ifEmpty { "none" }

    /**
     * The receiver's whole sequence, in the order that matters:
     * route (blank/unknown refused) → text (blank refused) → session identity → `wake()` → final transcript.
     *
     * The envelope is built from `HostRuntime.currentIdentity()` rather than from a caller-supplied session: an event
     * under any other identity is dropped by the host's gate, so asking the host is the only honest way to be sure the
     * injection reaches the active plugin. A null identity (nothing active) is refused without submitting anything.
     *
     * [wake] is the production wake half (`HostCompositionRoot.emitWake` in the receiver). It is invoked **after** the
     * checks above so a blank/unknown request never leaves an overlay open with no turn to close it; for the non-wake
     * routes it is not invoked at all.
     */
    fun inject(
        host: HostRuntime,
        text: String,
        rawRoute: String?,
        turnId: String,
        revision: Long,
        nowNanos: Long,
        wake: () -> Unit,
    ): Injection {
        val route = routeOf(rawRoute) ?: return Injection(null, Outcome.UNKNOWN_ROUTE)
        if (text.isBlank()) return Injection(route, Outcome.BLANK_TEXT)
        require(revision >= 0) { "revision must be non-negative" }
        val identity = runCatching { host.currentIdentity() }.getOrNull()
            ?: return Injection(route, Outcome.NO_SESSION)
        return when (route) {
            Route.WAKE -> {
                wake()
                Injection(route, submit(host, identity, text, Route.TRANSCRIPT, turnId, revision, nowNanos))
            }
            Route.HELP, Route.TRANSCRIPT ->
                Injection(route, submit(host, identity, text, route, turnId, revision, nowNanos))
        }
    }

    /** Builds and submits the transcript (plus the production manual action for `help`). */
    private fun submit(
        host: HostRuntime,
        identity: Pair<SessionId, Generation>,
        text: String,
        route: Route,
        turnId: String,
        revision: Long,
        nowNanos: Long,
    ): Outcome {
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
            Route.HELP -> listOf(
                TranscriptEvent(transcript),
                UserAction(envelope.copy(eventId = "debug-help-$revision"), UserAction.Action.Help),
            )
            Route.TRANSCRIPT -> listOf(TranscriptEvent(transcript))
            Route.WAKE -> error("the wake half is the composition's emitWake; inject submits its transcript")
        }
        return if (events.all { host.onEvent(it) }) Outcome.ACCEPTED else Outcome.HOST_REFUSED
    }

    private const val MAX_TURN_CHARS = 64
}
