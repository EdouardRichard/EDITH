package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ToolObservationCapture
import com.sheen.rokid.agent.ToolObservationSource
import com.sheen.rokid.output.PerceptionToolObservation
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** The two stable reasons a failed loop-level capture reports; wire values the loop and acceptance already know. */
private const val OBSERVATION_TIMEOUT = "observation_timeout"
private const val PROVIDER_THREW = "provider_threw"
/**
 * Log-only reasons for attempts that ended without a capture but **not** because the deadline expired. The observation
 * the model sees stays `observation_timeout` (the pre-M4 wire value); the `P3_OBSERVE` line must not claim a timeout
 * that never happened.
 */
private const val CLOSED = "closed"
private const val INTERRUPTED = "interrupted"
private const val CANCELLED = "cancelled"
/** The one worker a session's source owns. Daemon, so an interrupted platform read can never keep the process alive. */
private const val OBSERVE_THREAD_NAME = "perception-observe"
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * edith-0.3 Phase 3 Task 3 (M4): the plugin's loop-level [ToolObservationSource], backed by the session's
 * [PerceptionProvider].
 *
 * The agent loop asks it only after a **non-`Done`** result from a descriptor that declared `screenObservation = true`
 * and that came back without an observation of its own. This is the fallback for the paths a tool cannot observe from
 * inside (a policy/confirmation refusal, a confirmed or uncertain timeout, a thrown tool).
 *
 * **Bounded by this source's wait, not by the platform read.** One task runs `provider.refresh()` then
 * `provider.screenshot()` on a single-thread daemon executor, and the caller waits with
 * `Future.get(deadlineMillis, MILLISECONDS)`. The deadline bounds only that wait: when it expires the caller returns an
 * explicit `unavailable(observation_timeout)` and calls `future.cancel(true)`, but **cancellation is best-effort and
 * cooperative** — a read that does not check the interrupt (the Android provider's `refresh()` does not) keeps running.
 *
 * **Turn-level residual (Task 9/acceptance).** The 250 ms number is therefore source-local, not a turn-level bound.
 * The `AndroidPerceptionProvider`'s `refresh`/`snapshot`/`beginTurn`/`endTurn` are all `@Synchronized`, so an
 * overrunning worker holds the provider's monitor: this same turn's later provider reads (the request ≥ 1
 * `perceptionBlockFor`) and its `endTurn` in the run's `finally` can block until that read finishes. The overrun is
 * still paid by the turn, just after the 250 ms mark. A real turn-level bound needs a design decision (a provider whose
 * reads do not share one monitor), not a change here; acceptance must not read a `timeout=true` line as "the turn paid
 * nothing for the read".
 *
 * A provider throw becomes `unavailable(provider_threw)`, never a silent `null`.
 *
 * **At most one image.** The screenshot channel is the only image source: `Image` → the observation plus exactly that
 * one [com.sheen.rokid.plugin.ImageRef]; `Disabled` → `vision = disabled`; `Unavailable(reason)` → `vision = unavailable`
 * with the host's reason. The refreshed snapshot's screen text, foreground and coverage ride every answer (a blind
 * screen still renders `screen = null`, never an invented one).
 *
 * **Observability.** Each attempt emits one count-only `P3_OBSERVE source=loop ms=<n> timeout=<bool> images=<n>
 * reason=<stable|->` line: elapsed milliseconds, whether **the deadline expired**, the image count and one stable
 * attempt reason — the observation's vision reason when there is one, `-` when the picture channel has none, or a local
 * token (`observation_timeout`, `provider_threw`, `closed`, `interrupted`, `cancelled`) when no capture was produced.
 * `timeout=true` only ever accompanies an expired deadline; a closed/interrupted/cancelled attempt logs `timeout=false`
 * with its own reason instead of claiming `observation_timeout`. No screen text, foreground package or reason payload is
 * logged.
 *
 * **Lifecycle.** The plugin owns one source per session and calls [close] in `deactivate`; [close] shuts the executor
 * down idempotently, and a capture attempted afterwards answers `unavailable(observation_timeout)` without starting
 * another task. Like the timeout path, [close] cannot stop a read already in progress — the interrupt it sends is the
 * same best-effort one.
 */
internal class PerceptionObservationSource(
    private val provider: PerceptionProvider,
    private val log: (String) -> Unit = { P3Log.line(it) },
) : ToolObservationSource, AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, OBSERVE_THREAD_NAME).apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    /** The pre-M4 shape: the image-aware answer without its images. Existing callers keep this entry point. */
    override fun observe(deadlineMillis: Long): ToolObservation =
        observeWithImages(deadlineMillis).observation
            ?: PerceptionToolObservation.unavailable(OBSERVATION_TIMEOUT)

    override fun observeWithImages(deadlineMillis: Long): ToolObservationCapture {
        val startedNanos = System.nanoTime()
        // A closed session source cannot start another task; report the same explicit timeout fact the loop maps.
        if (closed.get()) return unavailableCapture(startedNanos, timedOut = false, logReason = CLOSED)
        val future = try {
            executor.submit(Callable {
                // Both reads are one attempt: refresh installs the current screen, screenshot observes the picture
                // channel the host exposes for it. A throw anywhere in here is the provider's failure.
                provider.refresh() to provider.screenshot()
            })
        } catch (_: RejectedExecutionException) {
            // `close()` raced the submit: the task was never accepted, which is still an explicit timeout fact.
            return unavailableCapture(startedNanos, timedOut = false, logReason = CLOSED)
        }
        return try {
            val (snapshot, screenshot) = future.get(deadlineMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            val capture = captureOf(snapshot, screenshot)
            logObserve(
                startedNanos,
                timedOut = false,
                images = capture.images.size,
                reason = capture.observation?.visionReason,
            )
            capture
        } catch (_: TimeoutException) {
            future.cancel(true)
            unavailableCapture(startedNanos, timedOut = true)
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            // An Error is the run owner's problem, not an observation failure; the loop keeps the same rule.
            if (cause is Error) throw cause
            logObserve(startedNanos, timedOut = false, images = 0, reason = PROVIDER_THREW)
            ToolObservationCapture(PerceptionToolObservation.unavailable(PROVIDER_THREW))
        } catch (_: InterruptedException) {
            future.cancel(true)
            // An interrupt is the caller's signal: restore it and report the attempt honestly (interrupted, not an
            // expired deadline).
            Thread.currentThread().interrupt()
            unavailableCapture(startedNanos, timedOut = false, logReason = INTERRUPTED)
        } catch (_: CancellationException) {
            // A future cancelled elsewhere is still an attempt that produced no capture; never let it escape the loop.
            unavailableCapture(startedNanos, timedOut = false, logReason = CANCELLED)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }

    /**
     * Unavailable/observation_timeout with no image, plus its one count-only line.
     *
     * The observation the model sees stays the stable `observation_timeout` wire value; [logReason] is what the
     * `P3_OBSERVE` line says actually ended the attempt, so a closed session or a caller interrupt is never logged as
     * an expired deadline.
     */
    private fun unavailableCapture(
        startedNanos: Long,
        timedOut: Boolean,
        logReason: String = OBSERVATION_TIMEOUT,
    ): ToolObservationCapture {
        logObserve(startedNanos, timedOut = timedOut, images = 0, reason = logReason)
        return ToolObservationCapture(PerceptionToolObservation.unavailable(OBSERVATION_TIMEOUT))
    }

    /**
     * One honest projection: the refreshed snapshot always rides, the screenshot decides the vision fact and whether
     * exactly one image is attached. Nothing here is invented — a host reason is passed through unchanged.
     */
    private fun captureOf(
        snapshot: PerceptionSnapshot,
        screenshot: PerceptionScreenshotResult,
    ): ToolObservationCapture = when (screenshot) {
        is PerceptionScreenshotResult.Image -> ToolObservationCapture(
            PerceptionToolObservation.render(snapshot, vision = ToolVision.Ok),
            images = listOf(screenshot.image),
        )
        PerceptionScreenshotResult.Disabled -> ToolObservationCapture(
            PerceptionToolObservation.render(snapshot, vision = ToolVision.Disabled),
        )
        is PerceptionScreenshotResult.Unavailable -> ToolObservationCapture(
            PerceptionToolObservation.render(
                snapshot,
                vision = ToolVision.Unavailable,
                visionReason = screenshot.reason,
            ),
        )
    }

    /** The one count-only line: no screen text, no package, no image bytes — only the attempt's counts and stable facts. */
    private fun logObserve(startedNanos: Long, timedOut: Boolean, images: Int, reason: String?) {
        val elapsedMillis = ((System.nanoTime() - startedNanos) / NANOS_PER_MILLI).coerceAtLeast(0L)
        log("P3_OBSERVE source=loop ms=$elapsedMillis timeout=$timedOut images=$images reason=${reason ?: "-"}")
    }
}

/**
 * The session's observation source, or `null` when the host granted no perception port — the loop then reports its own
 * explicit `no_source` rather than this plugin inventing one.
 *
 * [log] is a test seam only; production uses [P3Log].
 */
internal fun perceptionToolObservationSource(
    provider: PerceptionProvider?,
    log: (String) -> Unit = { P3Log.line(it) },
): PerceptionObservationSource? = provider?.let { PerceptionObservationSource(it, log) }
