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
 * effect (the message says so). [cancel] also interrupts the in-flight operation and refuses every
 * later operation of the turn; [beginTurn] re-arms the kernel for the next turn. The worker task is
 * interrupted but the kernel cannot force a wedged port call to return — the adapter owns making
 * platform calls bounded.
 *
 * **What an action reports.** [act] performs the planned action and then *always* observes again
 * (success or refusal), so `accepted` is the platform's answer while `applied` is only true when
 * that independent observation differs from the snapshot taken before the action. A refused action
 * is never `applied = true`; an accepted action that changes nothing is
 * [DeviceErrorCodes.ACCEPTED_NO_EFFECT]; without a baseline observation `applied` stays null.
 * The baseline is the last snapshot this kernel observed — the e2e flow observes right before it
 * acts, so an unrelated change between that observation and the action can still be misread as
 * `applied` (spec §4.5's known fingerprint limitation).
 *
 * **Rendering.** The kernel deliberately does not render. [DeviceEnvelope.data] stays null and
 * [latestObservation] carries the snapshot; Task 10 projects the §4.6 `data.screen` (full/diff) and
 * Task 13 wires the artifact store for [screenshot]'s `ImageRef`.
 *
 * The kernel owns a daemon thread pool; callers must [close] it. A closed kernel reports
 * [DeviceErrorCodes.CANCELLED] instead of accepting work.
 */
class DeviceKernel(
    private val accessibility: AccessibilityPort,
    private val shell: ShellPort?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    val registry: RefRegistry = RefRegistry(),
) : AutoCloseable {

    private val planner = ActionPlanner(accessibility, shell)

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, KERNEL_THREAD_NAME).apply { isDaemon = true }
    }
    private val submissionLock = Any()
    private val cancelled = AtomicBoolean(false)

    /**
     * Futures submitted and not yet returned. The worker is single-threaded, so at most one runs and
     * the rest are queued; [cancel] must interrupt the running one *and* drop the queued ones, and
     * tracking only the newest future would miss the running write.
     */
    private val outstanding = LinkedHashSet<Future<*>>()

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
        runSerial(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, DEFAULT_OPERATION_TIMEOUT_MILLIS) {
            val started = clock()
            if (cancelled.get()) {
                return@runSerial cancelledEnvelope(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, started)
            }
            ensureReady(ReadyKind.OBSERVE)?.let {
                return@runSerial rejected(ACTION_OBSERVE, CHANNEL_ACCESSIBILITY, it, started)
            }
            val readStarted = clock()
            val result = observeLocked(filter)
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
     * Planning happens before the ready gate so a shell-only action (a key event) does not need a
     * bound accessibility service: the gate is [ReadyKind.SHELL] for shell-only kinds and
     * [ReadyKind.ACT] otherwise. `WAIT` is not a platform action; it runs as [waitFor] over an idle
     * screen and reports `action = "wait"`.
     *
     * On success the envelope carries `accepted`, the observed `applied` and — when the screen
     * changed — `verified = screen_change`. Every refused platform action still returns the new
     * observation (through [latestObservation]); an unsupported request is
     * [DeviceErrorCodes.INVALID_ARGUMENTS] and touches nothing.
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
        return runSerial(action, channel, budget) {
            val started = clock()
            if (cancelled.get()) return@runSerial cancelledEnvelope(action, channel, started)
            if (kind == ActKind.WAIT) {
                waitLocked(action, channel, started, text = null, foreground = null, idle = true, timeoutMillis)
            } else {
                actLocked(kind, ref, text, value, direction, key, channel, started, timeoutMillis)
            }
        }
    }

    /**
     * Takes one screenshot and reports it as an artifact reference, never as inline bytes.
     *
     * The `artifactId` is the placeholder [PENDING_ARTIFACT_ID] until Task 10/13 injects the real
     * artifact store (spec §4.6/§5.3); [Screenshot.bytes] stays inside the kernel and is not copied
     * into [DeviceEnvelope.data]. A channel that cannot take screenshots reports
     * [DeviceErrorCodes.UNSUPPORTED], which is distinct from [DeviceErrorCodes.CHANNEL_UNAVAILABLE].
     */
    fun screenshot(): DeviceEnvelope =
        runSerial(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, DEFAULT_OPERATION_TIMEOUT_MILLIS) {
            val started = clock()
            if (cancelled.get()) {
                return@runSerial cancelledEnvelope(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, started)
            }
            ensureReady(ReadyKind.SCREENSHOT)?.let {
                return@runSerial rejected(ACTION_SCREENSHOT, CHANNEL_ACCESSIBILITY, it, started)
            }
            val readStarted = clock()
            val shot = try {
                accessibility.screenshot()
            } catch (t: Throwable) {
                return@runSerial if (cancelled.get()) {
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
            if (cancelled.get()) {
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
                DeviceEnvelope.success(
                    action = ACTION_SCREENSHOT,
                    channel = CHANNEL_ACCESSIBILITY,
                    accepted = true,
                    applied = null,
                    verified = null,
                    images = listOf(ImageRef(PENDING_ARTIFACT_ID, shot.mime, shot.width, shot.height, "")),
                    timing = timing(started, observeMs = readMs),
                )
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
        return runSerial(ACTION_WAIT, CHANNEL_ACCESSIBILITY, budget) {
            val started = clock()
            if (cancelled.get()) {
                return@runSerial cancelledEnvelope(ACTION_WAIT, CHANNEL_ACCESSIBILITY, started)
            }
            waitLocked(ACTION_WAIT, CHANNEL_ACCESSIBILITY, started, text, foreground, idle, timeoutMillis)
        }
    }

    /**
     * Reports the foreground app plus [health] as one line of `key=value` text.
     *
     * This is diagnostic and therefore not gated by [ensureReady]: it must be able to say
     * `interactive=false` when the screen is off, which is exactly when the model needs it.
     */
    fun status(): DeviceEnvelope =
        runSerial(ACTION_STATUS, CHANNEL_ACCESSIBILITY, DEFAULT_OPERATION_TIMEOUT_MILLIS) {
            val started = clock()
            if (cancelled.get()) {
                return@runSerial cancelledEnvelope(ACTION_STATUS, CHANNEL_ACCESSIBILITY, started)
            }
            val readStarted = clock()
            val foreground = try {
                accessibility.foreground()
            } catch (t: Throwable) {
                return@runSerial if (cancelled.get()) {
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
        cancelled.set(true)
        cancelOutstanding()
    }

    /** Re-arms the kernel at the start of a new agent turn, undoing [cancel]. */
    fun beginTurn() {
        cancelled.set(false)
    }

    /** Stops the worker; a closed kernel reports [DeviceErrorCodes.CANCELLED] for every operation. */
    override fun close() {
        cancelled.set(true)
        cancelOutstanding()
        writer.shutdownNow()
        runCatching { writer.awaitTermination(CLOSE_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun cancelOutstanding() {
        val pending = synchronized(submissionLock) { outstanding.toList() }
        pending.forEach { it.cancel(true) }
    }

    // ── serialization ───────────────────────────────────────────────────────

    /**
     * Submits one operation to the single worker and waits for it with [timeoutMillis].
     *
     * The timeout covers queueing plus execution, so a wedged channel fails fast instead of
     * accumulating work. The caller's wall time is written into [Timing.totalMs] so queueing is
     * visible in the envelope.
     */
    private fun runSerial(
        action: String,
        channel: String,
        timeoutMillis: Long,
        block: () -> DeviceEnvelope,
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
        val future = try {
            synchronized(submissionLock) {
                writer.submit(Callable { block() }).also { outstanding += it }
            }
        } catch (e: RejectedExecutionException) {
            return cancelledEnvelope(action, channel, callerStarted)
        }
        return try {
            val envelope = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val wall = elapsed(callerStarted)
            envelope.copy(timing = envelope.timing.copy(totalMs = maxOf(envelope.timing.totalMs, wall)))
        } catch (e: TimeoutException) {
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
            cancelledEnvelope(action, channel, callerStarted)
        } catch (e: ExecutionException) {
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
            Thread.currentThread().interrupt()
            cancelledEnvelope(action, channel, callerStarted)
        } finally {
            synchronized(submissionLock) {
                outstanding -= future
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
     */
    private fun observeLocked(filter: ScreenFilter): ObserveResult {
        val observation = try {
            accessibility.observe(filter)
        } catch (t: Throwable) {
            return ObserveResult.Failed(
                if (cancelled.get()) cancelledError() else channelError("the observation failed", t),
            )
        }
        if (cancelled.get()) return ObserveResult.Failed(cancelledError())
        val next = revisionCounter + 1
        revisionCounter = next
        val stamped = observation.copy(revision = next)
        latestSnapshot = stamped
        latestFilter = filter
        runCatching { registry.assign(stamped) }
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
    ): DeviceEnvelope {
        val plan = planner.plan(kind, ref, text, value, direction, key)
        val readyKind = if (kind.isShellKeyAction) ReadyKind.SHELL else ReadyKind.ACT
        ensureReady(readyKind)?.let {
            return rejected(ACTION_ACT, channel, it, started)
        }
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
        generationCounter += 1
        val actStarted = clock()
        val execution = execute(plan, timeoutMillis, started)
        val actMs = elapsed(actStarted)
        if (execution.cancelled) return cancelledEnvelope(ACTION_ACT, channel, started)
        val observeStarted = clock()
        val observed = observeLocked(autoFilter)
        val observeMs = elapsed(observeStarted)
        return when (observed) {
            is ObserveResult.Failed -> DeviceEnvelope.failure(
                action = ACTION_ACT,
                channel = channel,
                error = execution.error ?: observed.error,
                accepted = execution.accepted,
                applied = null,
                timing = timing(started, actMs = actMs, observeMs = observeMs),
            )
            is ObserveResult.Ok -> {
                val applied = when {
                    before == null -> null
                    !execution.accepted -> false
                    else -> !sameScreen(before, observed.observation)
                }
                val verified = if (applied == true) {
                    Verified(VERIFIED_SCREEN_CHANGE, observed.observation.revision)
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
                if (error == null) {
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
        }
    }

    private fun execute(plan: PlannedAction, timeoutMillis: Long, started: Long): Execution = when (plan) {
        is PlannedAction.Node -> invokePort("the accessibility node action failed") {
            accessibility.performNodeAction(plan.ref, DeviceActions.encode(plan.action, plan.arguments))
        }.refusedAs {
            DeviceError(
                DeviceErrorCodes.NODE_NOT_CLICKABLE,
                "the platform refused ${plan.action} on ${plan.ref.value}",
                recoverable = true,
            )
        }
        is PlannedAction.GestureAction -> invokePort("the accessibility gesture failed") {
            accessibility.dispatchGesture(plan.gesture)
        }.refusedAs {
            DeviceError(
                DeviceErrorCodes.NODE_NOT_CLICKABLE,
                "the platform refused the gesture",
                recoverable = true,
            )
        }
        is PlannedAction.ShellKey -> runShellKey(plan.key, timeoutMillis, started)
        PlannedAction.Unsupported -> Execution(
            accepted = false,
            error = DeviceError(
                DeviceErrorCodes.INVALID_ARGUMENTS,
                "the request has no target, or no channel this kernel has can express it",
                recoverable = false,
            ),
        )
    }

    private fun invokePort(what: String, call: () -> Boolean): Execution = try {
        val accepted = call()
        if (cancelled.get()) Execution(accepted = accepted, cancelled = true) else Execution(accepted = accepted)
    } catch (t: Throwable) {
        if (cancelled.get()) {
            Execution(accepted = false, cancelled = true)
        } else {
            Execution(accepted = false, error = channelError(what, t))
        }
    }

    /** Gives a refused plan the layer-appropriate error instead of an unexplained `accepted=false`. */
    private fun Execution.refusedAs(refusal: () -> DeviceError): Execution =
        if (error == null && !accepted) copy(error = refusal()) else this

    private fun runShellKey(key: String, timeoutMillis: Long, started: Long): Execution {
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
            return if (cancelled.get()) {
                Execution(accepted = false, cancelled = true)
            } else {
                Execution(accepted = false, error = channelError("the shell command failed", t))
            }
        }
        if (cancelled.get()) return Execution(accepted = false, cancelled = true)
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

    // ── wait ────────────────────────────────────────────────────────────────

    private fun waitLocked(
        action: String,
        channel: String,
        started: Long,
        text: String?,
        foreground: String?,
        idle: Boolean,
        timeoutMillis: Long,
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
        val deadline = started + timeoutMillis
        var observeMs = 0L
        var previous: Observation? = null
        var stableSince = started
        while (true) {
            if (cancelled.get()) return cancelledEnvelope(action, channel, started)
            val observeStarted = clock()
            val result = observeLocked(ScreenFilter.ALL)
            observeMs += elapsed(observeStarted)
            val current = when (result) {
                is ObserveResult.Failed ->
                    return DeviceEnvelope.failure(
                        action = action,
                        channel = channel,
                        error = result.error,
                        timing = timing(started, observeMs = observeMs),
                    )
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
            if (clock() >= deadline) {
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

        private const val SHELL_INPUT_KEYEVENT = "input keyevent"

        /** Extra budget for a wait, so the wait's own VERIFY_TIMEOUT wins over ACTION_TIMEOUT. */
        private const val WAIT_GRACE_MILLIS = 1_000L

        private const val CLOSE_GRACE_MILLIS = 1_000L

        private const val MAX_ERROR_MESSAGE_CHARS = 300
    }
}
