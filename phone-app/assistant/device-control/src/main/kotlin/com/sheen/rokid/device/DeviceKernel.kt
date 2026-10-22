package com.sheen.rokid.device

import com.sheen.rokid.plugin.ImageRef
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
/**
 * The device-control kernel: the one owner of device state, channel access and serialization
 * (spec §4.1/§4.3).
 *
 * **Channels.** All device access goes through [AccessibilityPort] and the optional [ShellPort].
 * This class (and the whole module) never imports Android; Task 9 implements the ports.
 *
 * **State.** [revision] advances on every successful observation and is stamped onto
 * [Observation.revision]; [generation] advances when a mutating action is about to reach the
 * platform, so a write is never invisible to the next read. [latestObservation] is the newest
 * snapshot; [registry] is the [RefRegistry] every observation is assigned into, so the renderer
 * Task 10 wires in resolves exactly the refs the kernel reports.
 *
 * **Serialization.** Every operation — read and write — runs as one task on a single daemon worker
 * in submission order, and the caller waits with its own timeout. That is stricter than spec §4.3
 * (which allows up to three parallel reads across channels): serial reads make a read unable to
 * straddle a write, so the generation barrier is structural and no stale retry is needed. The cost
 * is that a slow wait blocks queued actions; a single agent loop never issues those concurrently.
 *
 * **Timeouts and cancellation.** A timed-out operation is interrupted; the envelope says
 * [DeviceErrorCodes.ACTION_TIMEOUT], because an interrupted platform call may still have taken
 * effect (the message says so). [cancel] also refuses every later operation of the turn;
 * [beginTurn] re-arms the kernel for the next turn. Every operation additionally owns a
 * cancellation token: a timeout, a caller interrupt or [cancel] sets it, and the worker checks it
 * after planning/ready probing and immediately before each port write, so an operation whose
 * caller already saw ACTION_TIMEOUT/CANCELLED cannot still send a tap, scroll or shell key. The
 * worker task is interrupted but the kernel cannot force a wedged port call to return — the
 * adapter owns making platform calls bounded.
 *
 * **What an action reports.** [act] performs the planned action and then observes again (success
 * or refusal), so `accepted` is the platform's answer while `applied` is only true when that
 * independent observation differs from the snapshot taken before the action. The observation is a
 * bounded settle (spec §4.4): the kernel polls until the screen leaves the pre-action shape or the
 * per-kind change wait expires, and taps/scrolls/keys additionally wait for two agreeing captures,
 * so an animated or network-backed screen is not misreported as [DeviceErrorCodes.ACCEPTED_NO_EFFECT].
 * A refused action is never `applied = true`; an accepted action that changes nothing is
 * [DeviceErrorCodes.ACCEPTED_NO_EFFECT]; without a baseline observation there is nothing to compare
 * against, so one look is the whole settle and `applied` stays null. A successful shell write is
 * not turned into a failure when the accessibility channel cannot observe: the envelope succeeds
 * with `applied = null` and a `diagnostic=...` line in `data` (R32). The baseline is the last
 * snapshot this kernel observed — the e2e flow observes right before it acts, so an unrelated
 * change between that observation and the action can still be misread as `applied` (spec §4.5's
 * known fingerprint limitation).
 *
 * **Rendering.** The kernel deliberately does not render. [DeviceEnvelope.data] stays null and
 * [latestObservation] carries the snapshot; Task 10 projects the §4.6 `data.screen` (full/diff) and Task 13 wires the
 * artifact store for [screenshot]'s `ImageRef`. A screenshot's bytes never enter [DeviceEnvelope.data]: they are handed
 * to the injected [ArtifactStore] and only the resulting reference travels.
 *
 * The kernel owns a daemon thread pool; callers must [close] it. A closed kernel reports
 * [DeviceErrorCodes.CANCELLED] instead of accepting work.
 */
class DeviceKernel(
    private val accessibility: AccessibilityPort,
    private val shell: ShellPort?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    val registry: RefRegistry = RefRegistry(),
    private val settlePolicy: SettlePolicy = SettlePolicy(),
    /**
     * 0.25 Task 13 (R67): where [screenshot]'s encoded bytes go. `null` — the default, so every pre-Task-13 caller
     * keeps compiling and behaving — leaves the `pending` placeholder with a diagnostic; a store that throws degrades
     * the same way. A `pending` reference is never mistaken for a stored artifact: its `sha256` is empty.
     */
    private val artifactStore: ArtifactStore? = null,
) : AutoCloseable {

    private val planner = ActionPlanner(accessibility, shell)

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, KERNEL_THREAD_NAME).apply { isDaemon = true }
    }
    private val submissionLock = Any()
    private val cancelled = AtomicBoolean(false)

    /**
     * Futures submitted and not yet returned, each with the operation's own cancellation token.
     * The worker is single-threaded, so at most one runs and the rest are queued; [cancel] must
     * interrupt the running one *and* drop the queued ones, and tracking only the newest future
     * would miss the running write.
     *
     * The token is needed in addition to the turn-level [cancelled] flag: a timed-out operation is
     * cancelled without ending the turn, and [beginTurn] clears the turn flag while an old
     * operation may still be unwinding inside an uninterruptible port call. The token keeps that
     * operation dead, so it can never execute a write after the caller was told CANCELLED or
     * ACTION_TIMEOUT.
     */
    private val outstanding = LinkedHashMap<Future<*>, OperationToken>()

    @Volatile
    private var revisionCounter = 0L

    @Volatile
    private var generationCounter = 0L

    @Volatile
    private var latestFilter: ScreenFilter = ScreenFilter.ALL

    @Volatile
    private var latestSnapshot: Observation? = null

    /** The newest snapshot this kernel observed, revision-stamped. Null before the first read. */
    val latestObservation: Observation?
        get() = latestSnapshot

    /** Monotonic observation counter (spec §4.3): +1 per successful observation. */
    val revision: Long
        get() = revisionCounter

    /** Monotonic write counter (spec §4.3): +1 per action that reaches the platform. */
    val generation: Long
        get() = generationCounter

    /**
     * Returns the reason [kind] cannot run right now, or null when it can.
     *
     * This version never unlocks: the screen being off is [DeviceErrorCodes.DEVICE_SCREEN_OFF], the
     * keyguard being locked is [DeviceErrorCodes.DEVICE_LOCKED], a missing accessibility binding or
     * shell is [DeviceErrorCodes.CHANNEL_UNAVAILABLE]. The device-level checks run first for every
     * kind; a port that throws is treated as "not available" rather than crashing the kernel.
     */
    fun ensureReady(kind: ReadyKind): DeviceError? {
        if (!interactive()) {
            return DeviceError(
                DeviceErrorCodes.DEVICE_SCREEN_OFF,
                "the screen is off; ask the user to turn it on",
                recoverable = true,
            )
        }
        if (keyguardLocked()) {
            return DeviceError(
                DeviceErrorCodes.DEVICE_LOCKED,
                "the device is locked; ask the user to unlock it",
                recoverable = true,
            )
        }
        return when (kind) {
            ReadyKind.OBSERVE, ReadyKind.ACT, ReadyKind.SCREENSHOT ->
                if (bound()) {
                    null
                } else {
                    DeviceError(
                        DeviceErrorCodes.CHANNEL_UNAVAILABLE,
                        "the accessibility service is not bound",
                        recoverable = true,
                    )
                }
            ReadyKind.SHELL ->
                if (shell != null) {
                    null
                } else {
                    DeviceError(
                        DeviceErrorCodes.CHANNEL_UNAVAILABLE,
                        "the shell channel (Shizuku) is not available",
                        recoverable = true,
                    )
                }
        }
    }

    /**
     * Reads one screen snapshot.
     *
     * The kernel stamps [Observation.revision], caches the snapshot as [latestObservation], assigns
     * refs into [registry] and reports `accepted = true` (the read itself succeeded; `applied` is
     * null because a read applies nothing). [data] is left for Task 10 to render.
     */
    fun observe(filter: ScreenFilter = ScreenFilter.INTERACTIVE): DeviceEnvelope =
        runSerial(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, DEFAULT_OPERATION_TIMEOUT_MILLIS) { token ->
            val started = clock()
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, started)
            }
            ensureReady(ReadyKind.OBSERVE)?.let {
                return@runSerial rejected(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, it, started)
            }
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, started)
            }
            val readStarted = clock()
            val result = observeLocked(filter, token)
            val observeMs = elapsed(readStarted)
            when (result) {
                is ObserveResult.Ok -> DeviceEnvelope.success(
                    action = ACTION_OBSERVE,
                    channel = CHANNEL_ACCESSIBILITY,
                    accepted = true,
                    applied = null,
                    verified = null,
                    timing = timing(started, observeMs = observeMs),
                )
                is ObserveResult.Failed -> DeviceEnvelope.failure(
                    action = ACTION_OBSERVE,
                    channel = CHANNEL_ACCESSIBILITY,
                    error = result.error,
                    timing = timing(started, observeMs = observeMs),
                )
            }
        }

    /**
     * Executes one semantic action and then observes the screen.
     *
     * The ready gate runs before planning: the gate is [ReadyKind.SHELL] for shell-only kinds and
     * [ReadyKind.ACT] otherwise, so a shell-only action (a key event) does not need a bound
     * accessibility service while a text or swipe target never reads the screen on a device that
     * is off or locked. `WAIT` is not a platform action; it runs as [waitFor] over an idle screen
     * and reports `action = "wait"`.
     *
     * On success the envelope carries `accepted`, the observed `applied` and — when the screen
     * changed — `verified = screen_change`. The observation is the bounded settle of spec §4.4
     * (per-kind change wait, held-still for taps/scrolls/keys, first change for a type). Every
     * refused platform action still returns the new observation (through [latestObservation]); an
     * unsupported request is [DeviceErrorCodes.INVALID_ARGUMENTS] and touches nothing.
     */
    fun act(
        kind: ActKind,
        ref: Ref? = null,
        text: String? = null,
        value: String? = null,
        direction: String? = null,
        key: String? = null,
        timeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    ): DeviceEnvelope {
        val channel = if (kind.isShellKeyAction) CHANNEL_SHELL else CHANNEL_ACCESSIBILITY
        val action = if (kind == ActKind.WAIT) ACTION_WAIT else ACTION_ACT
        val budget = if (kind == ActKind.WAIT) {
            timeoutMillis.coerceAtMost(Long.MAX_VALUE - WAIT_GRACE_MILLIS) + WAIT_GRACE_MILLIS
        } else {
            timeoutMillis
        }
        return runSerial(action, channel, budget) { token ->
            val started = clock()
            if (isCancelled(token)) return@runSerial cancelledEnvelope(action, channel, started)
            if (kind == ActKind.WAIT) {
                waitLocked(action, channel, started, text = null, foreground = null, idle = true, timeoutMillis, token)
            } else {
                actLocked(kind, ref, text, value, direction, key, channel, started, timeoutMillis, token)
            }
        }
    }

    /**
     * Takes one screenshot and reports it as an artifact reference, never as inline bytes.
     *
     * With an [ArtifactStore] the captured bytes are stored and the envelope carries the real reference (its artifact
     * id, mime, dimensions and sha256). Without one — or when the store throws — the envelope still succeeds with the
     * `pending` placeholder and a `diagnostic=screenshot_artifact_pending; reason=…` line in [DeviceEnvelope.data], so
     * a caller can tell "the screenshot worked, the store did not" from "the screenshot failed". The bytes themselves
     * are never copied into [DeviceEnvelope.data].
     *
     * A channel that cannot take screenshots reports [DeviceErrorCodes.UNSUPPORTED], which is distinct from
     * [DeviceErrorCodes.CHANNEL_UNAVAILABLE].
     */
    fun screenshot(): DeviceEnvelope =
        runSerial(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, DEFAULT_OPERATION_TIMEOUT_MILLIS) { token ->
            val started = clock()
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, started)
            }
            ensureReady(ReadyKind.SCREENSHOT)?.let {
                return@runSerial rejected(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, it, started)
            }
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, started)
            }
            val readStarted = clock()
            val shot = try {
                accessibility.screenshot()
            } catch (t: Throwable) {
                return@runSerial if (isCancelled(token)) {
                    cancelledEnvelope(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, started)
                } else {
                    DeviceEnvelope.failure(
                        action = ACTION_SCREENSHOT,
                        channel = CHANNEL_ACCESSIBILITY,
                        error = channelError("the screenshot failed", t),
                        timing = timing(started, observeMs = elapsed(readStarted)),
                    )
                }
            }
            val readMs = elapsed(readStarted)
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, started)
            }
            if (shot == null) {
                DeviceEnvelope.failure(
                    action = ACTION_SCREENSHOT,
                    channel = CHANNEL_ACCESSIBILITY,
                    error = DeviceError(
                        DeviceErrorCodes.UNSUPPORTED,
                        "the accessibility service returned no screenshot; it may not support takeScreenshot",
                        recoverable = false,
                    ),
                    timing = timing(started, observeMs = readMs),
                )
            } else {
                val image = storeScreenshot(shot)
                DeviceEnvelope.success(
                    action = ACTION_SCREENSHOT,
                    channel = CHANNEL_ACCESSIBILITY,
                    accepted = true,
                    applied = null,
                    verified = null,
                    data = image.diagnostic,
                    images = listOf(image.reference),
                    timing = timing(started, observeMs = readMs),
                )
            }
        }

    /** One screenshot's artifact outcome: a real reference, or the placeholder plus the reason it stayed pending. */
    private data class ScreenshotArtifact(val reference: ImageRef, val diagnostic: String? = null)

    /**
     * Stores the captured bytes and answers the reference to report (R67).
     *
     * Failure is a data problem, never a failed screenshot: the capture succeeded either way, so the envelope keeps
     * `ok = true` and only the reference degrades to [PENDING_ARTIFACT_ID] with a diagnostic. The exception's message is
     * not copied into the diagnostic — a file path is not something a model or a log line needs.
     */
    private fun storeScreenshot(shot: Screenshot): ScreenshotArtifact {
        val pending = ImageRef(PENDING_ARTIFACT_ID, shot.mime, shot.width, shot.height, "")
        val store = artifactStore
            ?: return ScreenshotArtifact(pending, "$SCREENSHOT_PENDING_DIAGNOSTIC; reason=NO_STORE")
        return try {
            ScreenshotArtifact(store.put(shot.bytes, shot.mime, shot.width, shot.height))
        } catch (t: Exception) {
            ScreenshotArtifact(pending, "$SCREENSHOT_PENDING_DIAGNOSTIC; reason=STORE_FAILED")
        }
    }

    /**
     * Waits, with bounded polling, until the screen shows [text], the foreground app matches
     * [foreground] (package name or `package/activity`), and/or the screen is [idle].
     *
     * All supplied conditions must hold on the same observation. `idle` additionally requires the
     * screen to stay unchanged for [IDLE_STABLE_MILLIS]. Success reports `verified = expected` (or
     * `idle`) with the final observation revision; the timeout is
     * [DeviceErrorCodes.VERIFY_TIMEOUT] and [latestObservation] is the last observation taken.
     * Passing no condition is [DeviceErrorCodes.INVALID_ARGUMENTS].
     */
    fun waitFor(text: String?, foreground: String?, idle: Boolean, timeoutMillis: Long): DeviceEnvelope {
        val budget = timeoutMillis.coerceAtMost(Long.MAX_VALUE - WAIT_GRACE_MILLIS) + WAIT_GRACE_MILLIS
        return runSerial(ACTION_WAIT, CHANNEL_ACCESSIBILITY, budget) { token ->
            val started = clock()
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_WAIT, CHANNEL_ACCESSIBILITY, started)
            }
            waitLocked(ACTION_WAIT, CHANNEL_ACCESSIBILITY, started, text, foreground, idle, timeoutMillis, token)
        }
    }

    /**
     * Reports the foreground app plus [health] as one line of `key=value` text.
     *
     * This is diagnostic and therefore not gated by [ensureReady]: it must be able to say
     * `interactive=false` when the screen is off, which is exactly when the model needs it.
     */
    fun status(): DeviceEnvelope =
        runSerial(ACTION_STATUS, CHANNEL_ACCESSIBILITY, DEFAULT_OPERATION_TIMEOUT_MILLIS) { token ->
            val started = clock()
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_STATUS, CHANNEL_ACCESSIBILITY, started)
            }
            val readStarted = clock()
            val foreground = try {
                accessibility.foreground()
            } catch (t: Throwable) {
                return@runSerial if (isCancelled(token)) {
                    cancelledEnvelope(ACTION_STATUS, CHANNEL_ACCESSIBILITY, started)
                } else {
                    DeviceEnvelope.failure(
                        action = ACTION_STATUS,
                        channel = CHANNEL_ACCESSIBILITY,
                        error = channelError("the foreground app could not be read", t),
                        timing = timing(started, observeMs = elapsed(readStarted)),
                    )
                }
            }
            val readMs = elapsed(readStarted)
            if (isCancelled(token)) {
                return@runSerial cancelledEnvelope(ACTION_STATUS, CHANNEL_ACCESSIBILITY, started)
            }
            val health = health()
            val data = buildString {
                append("foreground=").append(foreground.packageName ?: "?").append('/').append(foreground.activity ?: "?")
                append(" interactive=").append(health.interactive)
                append(" locked=").append(health.keyguardLocked)
                append(" accessibility=").append(health.accessibilityBound)
                append(" shell=").append(health.shellAvailable)
            }
            DeviceEnvelope.success(
                action = ACTION_STATUS,
                channel = CHANNEL_ACCESSIBILITY,
                accepted = true,
                applied = null,
                verified = null,
                data = data,
                timing = timing(started, observeMs = readMs),
            )
        }

    /** What the kernel can prove about its channels right now (see [ChannelHealth]). */
    fun health(): ChannelHealth = ChannelHealth(
        accessibilityBound = bound(),
        interactive = interactive(),
        keyguardLocked = keyguardLocked(),
        shellAvailable = shell != null,
    )

    /**
     * Cancels the current turn: the in-flight operation is interrupted, queued operations are
     * dropped, and every later operation returns [DeviceErrorCodes.CANCELLED] until [beginTurn].
     */
    fun cancel() {
        markTurnCancelled()
        cancelOutstanding()
    }

    /** Re-arms the kernel at the start of a new agent turn, undoing [cancel]. */
    fun beginTurn() {
        cancelled.set(false)
    }

    /** Stops the worker; a closed kernel reports [DeviceErrorCodes.CANCELLED] for every operation. */
    override fun close() {
        markTurnCancelled()
        cancelOutstanding()
        writer.shutdownNow()
        runCatching { writer.awaitTermination(CLOSE_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    /**
     * Sets the turn-level flag under the submission lock, so a concurrent [runSerial] either
     * submits (and is seen by [cancelOutstanding]) or observes the flag and refuses; there is no
     * window in which an operation is accepted after cancel returned.
     */
    private fun markTurnCancelled() {
        synchronized(submissionLock) {
            cancelled.set(true)
        }
    }

    private fun cancelOutstanding() {
        val pending = synchronized(submissionLock) { outstanding.toList() }
        pending.forEach { (future, token) ->
            token.cancel()
            future.cancel(true)
        }
    }

    // ── serialization ───────────────────────────────────────────────────────

    /**
     * Submits one operation to the single worker and waits for it with [timeoutMillis].
     *
     * The timeout covers queueing plus execution, so a wedged channel fails fast instead of
     * accumulating work. The caller's wall time is written into [Timing.totalMs] so queueing is
     * visible in the envelope.
     *
     * A timeout, a caller interrupt and [cancel] all cancel the operation's own token before they
     * release the caller, so the worker cannot execute a write after the caller already saw
     * ACTION_TIMEOUT/CANCELLED even when the interrupted port call keeps running.
     */
    private fun runSerial(
        action: String,
        channel: String,
        timeoutMillis: Long,
        block: (OperationToken) -> DeviceEnvelope,
    ): DeviceEnvelope {
        val callerStarted = clock()
        if (cancelled.get()) return cancelledEnvelope(action, channel, callerStarted)
        if (timeoutMillis <= 0L) {
            return rejected(
                action,
                channel,
                DeviceError(DeviceErrorCodes.INVALID_ARGUMENTS, "timeout_ms must be positive", recoverable = false),
                callerStarted,
            )
        }
        val token = OperationToken()
        val future = try {
            synchronized(submissionLock) {
                // Re-check under the same lock [markTurnCancelled] uses, so a cancel that lands
                // between the fast-path check above and this submission still wins.
                if (cancelled.get()) return cancelledEnvelope(action, channel, callerStarted)
                writer.submit(Callable { block(token) }).also { outstanding[it] = token }
            }
        } catch (e: RejectedExecutionException) {
            return cancelledEnvelope(action, channel, callerStarted)
        }
        return try {
            val envelope = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val wall = elapsed(callerStarted)
            envelope.copy(timing = envelope.timing.copy(totalMs = maxOf(envelope.timing.totalMs, wall)))
        } catch (e: TimeoutException) {
            token.cancel()
            future.cancel(true)
            DeviceEnvelope.failure(
                action = action,
                channel = channel,
                error = DeviceError(
                    DeviceErrorCodes.ACTION_TIMEOUT,
                    "the operation did not finish within $timeoutMillis ms; it may still have taken effect",
                    recoverable = true,
                ),
                timing = timing(callerStarted),
            )
        } catch (e: CancellationException) {
            token.cancel()
            cancelledEnvelope(action, channel, callerStarted)
        } catch (e: ExecutionException) {
            token.cancel()
            DeviceEnvelope.failure(
                action = action,
                channel = channel,
                error = DeviceError(
                    DeviceErrorCodes.CHANNEL_UNAVAILABLE,
                    "the device channel failed: ${e.cause?.message ?: e.cause?.javaClass?.simpleName ?: "unknown error"}",
                    recoverable = true,
                ),
                timing = timing(callerStarted),
            )
        } catch (e: InterruptedException) {
            // The caller stopped waiting; the work it was waiting for must stop too.
            token.cancel()
            future.cancel(true)
            Thread.currentThread().interrupt()
            cancelledEnvelope(action, channel, callerStarted)
        } finally {
            synchronized(submissionLock) {
                outstanding.remove(future)
            }
        }
    }

    // ── observation ─────────────────────────────────────────────────────────

    private sealed interface ObserveResult {
        data class Ok(val observation: Observation) : ObserveResult
        data class Failed(val error: DeviceError) : ObserveResult
    }

    /**
     * Reads and caches one observation. Must run on the worker (the only writer of [revisionCounter],
     * [latestSnapshot] and [latestFilter]).
     *
     * Publishing happens after [RefRegistry.assign], so a caller that sees the new
     * [latestObservation] can always resolve its refs.
     */
    private fun observeLocked(filter: ScreenFilter, token: OperationToken): ObserveResult {
        if (isCancelled(token)) return ObserveResult.Failed(cancelledError())
        val observation = try {
            accessibility.observe(filter)
        } catch (t: Throwable) {
            return ObserveResult.Failed(
                if (isCancelled(token)) cancelledError() else channelError("the observation failed", t),
            )
        }
        if (isCancelled(token)) return ObserveResult.Failed(cancelledError())
        val next = revisionCounter + 1
        revisionCounter = next
        val stamped = observation.copy(revision = next)
        runCatching { registry.assign(stamped) }
        latestSnapshot = stamped
        latestFilter = filter
        return ObserveResult.Ok(stamped)
    }

    /**
     * The screen fingerprint: everything an observation says except its [Observation.revision],
     * which the kernel itself bumps on every read. Comparing the node lists (rather than hashing
     * them) cannot collide, so `applied` never claims a change that is not there.
     */
    private fun sameScreen(left: Observation, right: Observation): Boolean =
        left.truncated == right.truncated && left.windows == right.windows && left.nodes == right.nodes

    // ── action execution ────────────────────────────────────────────────────

    private data class Execution(
        val accepted: Boolean,
        val error: DeviceError? = null,
        val cancelled: Boolean = false,
    )

    private fun actLocked(
        kind: ActKind,
        ref: Ref?,
        text: String?,
        value: String?,
        direction: String?,
        key: String?,
        channel: String,
        started: Long,
        timeoutMillis: Long,
        token: OperationToken,
    ): DeviceEnvelope {
        // The readiness gate runs before planning. A text target and a screen swipe both make the
        // planner read the screen, so a screen-off or locked device must be refused before that
        // read; a shell-only key needs no accessibility binding, which is why its gate is SHELL.
        val readyKind = if (kind.isShellKeyAction) ReadyKind.SHELL else ReadyKind.ACT
        ensureReady(readyKind)?.let {
            return rejected(ACTION_ACT, channel, it, started)
        }
        if (isCancelled(token)) return cancelledEnvelope(ACTION_ACT, channel, started)
        val plan = planner.plan(kind, ref, text, value, direction, key)
        if (isCancelled(token)) return cancelledEnvelope(ACTION_ACT, channel, started)
        if (plan is PlannedAction.Unsupported) {
            return rejected(
                ACTION_ACT,
                channel,
                DeviceError(
                    DeviceErrorCodes.INVALID_ARGUMENTS,
                    "the request has no target, or no channel this kernel has can express it",
                    recoverable = false,
                ),
                started,
            )
        }
        // The baseline is captured before the write; the auto-observation uses the same filter, so
        // `applied` compares two views of the same kind and a filter change never looks like a
        // screen change.
        val before = latestSnapshot
        val autoFilter = latestFilter
        // Last boundary before the write: planning may have blocked on a lookup and ensureReady on
        // a channel probe, so a cancel/timeout up to here must still leave the platform untouched.
        if (isCancelled(token)) return cancelledEnvelope(ACTION_ACT, channel, started)
        generationCounter += 1
        val actStarted = clock()
        val execution = execute(plan, token, timeoutMillis, started)
        val actMs = elapsed(actStarted)
        if (execution.cancelled || isCancelled(token)) return cancelledEnvelope(ACTION_ACT, channel, started)
        val observeStarted = clock()
        // A shell write does not need the accessibility service. Reporting a successful write as a
        // failure because the observer is missing would be a lie about the write, so the envelope
        // succeeds with `applied = null` and a diagnostic instead (R32).
        if (execution.accepted && channel == CHANNEL_SHELL && !bound()) {
            return DeviceEnvelope.success(
                action = ACTION_ACT,
                channel = channel,
                accepted = true,
                applied = null,
                verified = null,
                data = unobservedShellDiagnostic("the accessibility service is not bound"),
                timing = timing(started, actMs = actMs, observeMs = elapsed(observeStarted)),
            )
        }
        val settled = settleLocked(kind, before, autoFilter, execution.accepted, token)
        val observeMs = elapsed(observeStarted)
        if (settled is SettleResult.Failed) {
            if (execution.accepted && channel == CHANNEL_SHELL) {
                return DeviceEnvelope.success(
                    action = ACTION_ACT,
                    channel = channel,
                    accepted = true,
                    applied = null,
                    verified = null,
                    data = unobservedShellDiagnostic("the screen observation failed: ${settled.error.code}"),
                    timing = timing(started, actMs = actMs, observeMs = observeMs),
                )
            }
            if (isCancelled(token)) return cancelledEnvelope(ACTION_ACT, channel, started)
            return DeviceEnvelope.failure(
                action = ACTION_ACT,
                channel = channel,
                error = execution.error ?: settled.error,
                accepted = execution.accepted,
                applied = null,
                timing = timing(started, actMs = actMs, observeMs = observeMs),
            )
        }
        if (settled is SettleResult.Cancelled) return cancelledEnvelope(ACTION_ACT, channel, started)
        val observation = (settled as SettleResult.Observed).observation
        val applied = when {
            before == null -> null
            !execution.accepted -> false
            else -> !sameScreen(before, observation)
        }
        val verified = if (applied == true) {
            Verified(VERIFIED_SCREEN_CHANGE, observation.revision)
        } else {
            null
        }
        val error = execution.error ?: if (applied == false && execution.accepted) {
            DeviceError(
                DeviceErrorCodes.ACCEPTED_NO_EFFECT,
                "the platform accepted the action but the screen did not change",
                recoverable = true,
            )
        } else {
            null
        }
        return if (error == null) {
            DeviceEnvelope.success(
                action = ACTION_ACT,
                channel = channel,
                accepted = execution.accepted,
                applied = applied,
                verified = verified,
                timing = timing(started, actMs = actMs, observeMs = observeMs),
            )
        } else {
            DeviceEnvelope.failure(
                action = ACTION_ACT,
                channel = channel,
                error = error,
                accepted = execution.accepted,
                applied = applied,
                timing = timing(started, actMs = actMs, observeMs = observeMs),
            )
        }
    }

    private fun execute(plan: PlannedAction, token: OperationToken, timeoutMillis: Long, started: Long): Execution =
        when (plan) {
            is PlannedAction.Node -> {
                val encoded = DeviceActions.encode(plan.action, plan.arguments)
                if (encoded == null) {
                    Execution(
                        accepted = false,
                        error = DeviceError(
                            DeviceErrorCodes.INVALID_ARGUMENTS,
                            "the action arguments cannot be encoded safely",
                            recoverable = false,
                        ),
                    )
                } else {
                    invokePort(token, "the accessibility node action failed") {
                        accessibility.performNodeAction(plan.ref, encoded)
                    }.refusedAs {
                        DeviceError(
                            DeviceErrorCodes.NODE_NOT_CLICKABLE,
                            "the platform refused ${plan.action} on ${plan.ref.value}",
                            recoverable = true,
                        )
                    }
                }
            }
            is PlannedAction.GestureAction -> invokePort(token, "the accessibility gesture failed") {
                accessibility.dispatchGesture(plan.gesture)
            }.refusedAs {
                DeviceError(
                    DeviceErrorCodes.GESTURE_FAILED,
                    "the platform refused the gesture",
                    recoverable = true,
                )
            }
            is PlannedAction.ShellKey -> runShellKey(plan.key, token, timeoutMillis, started)
            PlannedAction.Unsupported -> Execution(
                accepted = false,
                error = DeviceError(
                    DeviceErrorCodes.INVALID_ARGUMENTS,
                    "the request has no target, or no channel this kernel has can express it",
                    recoverable = false,
                ),
            )
        }

    /**
     * Runs one accessibility write. The pre-call cancellation check is what keeps an operation that
     * was timed out or cancelled during planning/ready probing from reaching the platform.
     */
    private fun invokePort(token: OperationToken, what: String, call: () -> Boolean): Execution {
        if (isCancelled(token)) return Execution(accepted = false, cancelled = true)
        return try {
            val accepted = call()
            if (isCancelled(token)) Execution(accepted = accepted, cancelled = true) else Execution(accepted = accepted)
        } catch (t: Throwable) {
            if (isCancelled(token)) {
                Execution(accepted = false, cancelled = true)
            } else {
                Execution(accepted = false, error = channelError(what, t))
            }
        }
    }

    /** Gives a refused plan the layer-appropriate error instead of an unexplained `accepted=false`. */
    private fun Execution.refusedAs(refusal: () -> DeviceError): Execution =
        if (error == null && !accepted) copy(error = refusal()) else this

    private fun runShellKey(key: String, token: OperationToken, timeoutMillis: Long, started: Long): Execution {
        // Defence in depth: the planner already refuses hostile keys, but the key is interpolated
        // into a shell command here, so the last line before the port repeats the check.
        if (!DeviceKeyCodes.isSafeNormalized(key)) {
            return Execution(
                accepted = false,
                error = DeviceError(
                    DeviceErrorCodes.INVALID_ARGUMENTS,
                    "the key is not a safe key event code",
                    recoverable = false,
                ),
            )
        }
        if (isCancelled(token)) return Execution(accepted = false, cancelled = true)
        val port = shell ?: return Execution(
            accepted = false,
            error = DeviceError(
                DeviceErrorCodes.CHANNEL_UNAVAILABLE,
                "the shell channel (Shizuku) is not available",
                recoverable = true,
            ),
        )
        val remaining = (timeoutMillis - elapsed(started)).coerceAtLeast(1L)
        val outcome = try {
            port.run("$SHELL_INPUT_KEYEVENT $key", remaining, SHELL_MAX_BYTES)
        } catch (t: Throwable) {
            return if (isCancelled(token)) {
                Execution(accepted = false, cancelled = true)
            } else {
                Execution(accepted = false, error = channelError("the shell command failed", t))
            }
        }
        if (isCancelled(token)) return Execution(accepted = false, cancelled = true)
        val failure = outcome.failure?.takeIf { it.isNotBlank() }
        val detail = shellDetail(outcome.stderr)
        val error = when {
            failure != null -> DeviceError(failure, detail.ifEmpty { "the shell command failed with $failure" }, recoverable = true)
            outcome.exitCode != 0 -> DeviceError(
                DeviceErrorCodes.SHELL_FAILED,
                "the command exited with ${outcome.exitCode}${if (detail.isEmpty()) "" else ": $detail"}",
                recoverable = true,
            )
            else -> null
        }
        return Execution(accepted = error == null, error = error)
    }

    private fun unobservedShellDiagnostic(reason: String): String =
        "diagnostic=shell_accepted_unobserved; $reason"

    // ── settle ──────────────────────────────────────────────────────────────

    private sealed interface SettleResult {
        data class Observed(val observation: Observation) : SettleResult
        data class Failed(val error: DeviceError) : SettleResult
        data object Cancelled : SettleResult
    }

    /**
     * Observes the screen after a write and waits for it to settle (spec §4.4).
     *
     * A refused write has no effect to wait for, and without a pre-action baseline there is
     * nothing to compare against, so both cases take exactly one look. Otherwise the kernel polls
     * the accessibility tree until it leaves the baseline's shape or the per-kind change wait
     * expires; a change then needs [SettlePolicy.requiresHeldStill] to see two agreeing captures
     * (bounded by [SettlePolicy.heldStillMillis]) so a list caught mid-update is not returned as
     * settled. `type` reads at the first change instead, because its effect is confined to the
     * field it acted on. Every wait is difference-based and bounded; cancellation is checked before
     * each look and each sleep.
     */
    private fun settleLocked(
        kind: ActKind,
        before: Observation?,
        filter: ScreenFilter,
        accepted: Boolean,
        token: OperationToken,
    ): SettleResult {
        if (!accepted || before == null) return observeOnce(filter, token)
        val changeWait = settlePolicy.changeWaitMillis(kind)
        if (changeWait <= 0L) return observeOnce(filter, token)
        val changeStarted = clock()
        var last: Observation
        while (true) {
            if (isCancelled(token)) return SettleResult.Cancelled
            when (val result = observeLocked(filter, token)) {
                is ObserveResult.Failed -> return settleFailure(result.error, token)
                is ObserveResult.Ok -> last = result.observation
            }
            if (!sameScreen(before, last)) {
                return if (settlePolicy.requiresHeldStill(kind)) {
                    holdStill(last, filter, token)
                } else {
                    SettleResult.Observed(last)
                }
            }
            if (elapsed(changeStarted) >= changeWait) return SettleResult.Observed(last)
            if (!awaitNextPoll(token)) return SettleResult.Cancelled
        }
    }

    /** Waits for a capture that agrees with [firstChanged], bounded by the held-still budget. */
    private fun holdStill(firstChanged: Observation, filter: ScreenFilter, token: OperationToken): SettleResult {
        val heldStill = settlePolicy.heldStillMillis
        if (heldStill <= 0L) return SettleResult.Observed(firstChanged)
        val stillStarted = clock()
        var previous = firstChanged
        while (true) {
            if (elapsed(stillStarted) >= heldStill) return SettleResult.Observed(previous)
            if (!awaitNextPoll(token)) return SettleResult.Cancelled
            when (val result = observeLocked(filter, token)) {
                is ObserveResult.Failed -> return settleFailure(result.error, token)
                is ObserveResult.Ok -> {
                    val current = result.observation
                    if (sameScreen(previous, current)) return SettleResult.Observed(current)
                    previous = current
                }
            }
        }
    }

    private fun observeOnce(filter: ScreenFilter, token: OperationToken): SettleResult =
        when (val result = observeLocked(filter, token)) {
            is ObserveResult.Failed -> settleFailure(result.error, token)
            is ObserveResult.Ok -> SettleResult.Observed(result.observation)
        }

    private fun settleFailure(error: DeviceError, token: OperationToken): SettleResult =
        if (error.code == DeviceErrorCodes.CANCELLED || isCancelled(token)) {
            SettleResult.Cancelled
        } else {
            SettleResult.Failed(error)
        }

    /** Sleeps one bounded poll interval; false when the operation was cancelled. */
    private fun awaitNextPoll(token: OperationToken): Boolean {
        if (isCancelled(token)) return false
        return try {
            Thread.sleep(settlePolicy.pollIntervalMillis.coerceAtLeast(1L))
            !isCancelled(token)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun isCancelled(token: OperationToken): Boolean = cancelled.get() || token.isCancelled

    /** One operation's cancellation flag; independent of the turn-level flag (see [outstanding]). */
    private class OperationToken {
        private val cancelled = AtomicBoolean(false)

        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            cancelled.set(true)
        }
    }

    // ── wait ────────────────────────────────────────────────────────────────

    private fun waitLocked(
        action: String,
        channel: String,
        started: Long,
        text: String?,
        foreground: String?,
        idle: Boolean,
        timeoutMillis: Long,
        token: OperationToken,
    ): DeviceEnvelope {
        val query = text?.trim()?.takeIf { it.isNotEmpty() }
        val foregroundQuery = foreground?.trim()?.takeIf { it.isNotEmpty() }
        if (query == null && foregroundQuery == null && !idle) {
            return rejected(
                action,
                channel,
                DeviceError(
                    DeviceErrorCodes.INVALID_ARGUMENTS,
                    "wait needs text, foreground or idle",
                    recoverable = false,
                ),
                started,
            )
        }
        if (timeoutMillis <= 0L) {
            return rejected(
                action,
                channel,
                DeviceError(DeviceErrorCodes.INVALID_ARGUMENTS, "timeout_ms must be positive", recoverable = false),
                started,
            )
        }
        ensureReady(ReadyKind.OBSERVE)?.let { return rejected(action, channel, it, started) }
        var observeMs = 0L
        var previous: Observation? = null
        var stableSince = started
        while (true) {
            if (isCancelled(token)) return cancelledEnvelope(action, channel, started)
            val observeStarted = clock()
            val result = observeLocked(ScreenFilter.ALL, token)
            observeMs += elapsed(observeStarted)
            val current = when (result) {
                is ObserveResult.Failed ->
                    return if (isCancelled(token)) {
                        cancelledEnvelope(action, channel, started)
                    } else {
                        DeviceEnvelope.failure(
                            action = action,
                            channel = channel,
                            error = result.error,
                            timing = timing(started, observeMs = observeMs),
                        )
                    }
                is ObserveResult.Ok -> result.observation
            }
            val last = previous
            val textReady = query == null || current.nodes.any { nodeMatches(it, query) }
            val foregroundReady = foregroundQuery == null || foregroundMatches(foregroundQuery)
            val unchangedSinceLast = last != null && sameScreen(last, current)
            if (textReady && foregroundReady) {
                if (!idle) {
                    return DeviceEnvelope.success(
                        action = action,
                        channel = channel,
                        accepted = true,
                        applied = null,
                        verified = Verified(VERIFIED_EXPECTED, current.revision),
                        timing = timing(started, observeMs = observeMs),
                    )
                }
                if (unchangedSinceLast && elapsed(stableSince) >= IDLE_STABLE_MILLIS) {
                    return DeviceEnvelope.success(
                        action = action,
                        channel = channel,
                        accepted = true,
                        applied = null,
                        verified = Verified(VERIFIED_IDLE, current.revision),
                        timing = timing(started, observeMs = observeMs),
                    )
                }
            }
            if (!unchangedSinceLast) stableSince = clock()
            previous = current
            // A difference-based deadline cannot overflow when the caller passes a huge timeout.
            if (elapsed(started) >= timeoutMillis) {
                return DeviceEnvelope.failure(
                    action = action,
                    channel = channel,
                    error = DeviceError(
                        DeviceErrorCodes.VERIFY_TIMEOUT,
                        "the wait condition was not met within $timeoutMillis ms",
                        recoverable = true,
                    ),
                    timing = timing(started, observeMs = observeMs),
                )
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return cancelledEnvelope(action, channel, started)
            }
        }
    }

    private fun nodeMatches(node: DeviceNode, query: String): Boolean =
        node.text?.trim().equals(query, ignoreCase = true) ||
            node.contentDescription?.trim().equals(query, ignoreCase = true)

    private fun foregroundMatches(query: String): Boolean {
        val current = runCatching { accessibility.foreground() }.getOrNull() ?: return false
        val packageName = current.packageName ?: return false
        if (packageName.equals(query, ignoreCase = true)) return true
        val activity = current.activity
        return activity != null && "$packageName/$activity".equals(query, ignoreCase = true)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun interactive(): Boolean = runCatching { accessibility.isInteractive() }.getOrDefault(false)

    private fun keyguardLocked(): Boolean = runCatching { accessibility.isKeyguardLocked() }.getOrDefault(false)

    private fun bound(): Boolean = runCatching { accessibility.isBound() }.getOrDefault(false)

    private fun elapsed(started: Long): Long = (clock() - started).coerceAtLeast(0L)

    private fun timing(started: Long, actMs: Long = 0L, observeMs: Long = 0L): Timing =
        Timing(actMs = actMs, observeMs = observeMs, totalMs = elapsed(started))

    private fun cancelledError(): DeviceError = DeviceError(
        DeviceErrorCodes.CANCELLED,
        "the turn was cancelled",
        recoverable = true,
    )

    private fun channelError(what: String, throwable: Throwable): DeviceError = DeviceError(
        DeviceErrorCodes.CHANNEL_UNAVAILABLE,
        "$what: ${throwable.message ?: throwable.javaClass.simpleName}",
        recoverable = true,
    )

    private fun cancelledEnvelope(action: String, channel: String, started: Long): DeviceEnvelope =
        DeviceEnvelope.failure(
            action = action,
            channel = channel,
            error = cancelledError(),
            timing = timing(started),
        )

    private fun rejected(action: String, channel: String, error: DeviceError, started: Long): DeviceEnvelope =
        DeviceEnvelope.failure(
            action = action,
            channel = channel,
            error = error,
            timing = timing(started),
        )

    private fun shellDetail(stderr: String): String = stderr.trim().take(MAX_ERROR_MESSAGE_CHARS)

    companion object {
        /** Default per-operation budget; a model action inherits it unless it passes `timeout_ms`. */
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 30_000L

        /** Spec §4.6: shell replies are targeted at 64 KiB. */
        const val SHELL_MAX_BYTES = 65_536

        /** Spec §4.4: the screen is treated as settled after roughly half a second of stillness. */
        const val IDLE_STABLE_MILLIS = 500L

        /** Polling granularity for [waitFor] (bounded polling, no fixed sleeps in the caller). */
        const val POLL_INTERVAL_MILLIS = 50L

        private const val KERNEL_THREAD_NAME = "edith-device-kernel"

        private const val ACTION_OBSERVE = "observe"
        private const val ACTION_ACT = "act"
        private const val ACTION_SCREENSHOT = "screenshot"
        private const val ACTION_WAIT = "wait"
        private const val ACTION_STATUS = "status"

        private const val CHANNEL_ACCESSIBILITY = "accessibility"
        private const val CHANNEL_SHELL = "shell"

        private const val VERIFIED_SCREEN_CHANGE = "screen_change"
        private const val VERIFIED_EXPECTED = "expected"
        private const val VERIFIED_IDLE = "idle"

        private const val PENDING_ARTIFACT_ID = "pending"

        /** R67: the one-line reason a screenshot's reference is the placeholder rather than a stored artifact. */
        private const val SCREENSHOT_PENDING_DIAGNOSTIC = "diagnostic=screenshot_artifact_pending"

        private const val SHELL_INPUT_KEYEVENT = "input keyevent"

        /** Extra budget for a wait, so the wait's own VERIFY_TIMEOUT wins over ACTION_TIMEOUT. */
        private const val WAIT_GRACE_MILLIS = 1_000L

        private const val CLOSE_GRACE_MILLIS = 1_000L

        private const val MAX_ERROR_MESSAGE_CHARS = 300
    }
}
