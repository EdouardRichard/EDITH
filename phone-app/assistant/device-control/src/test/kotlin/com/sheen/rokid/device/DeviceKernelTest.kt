package com.sheen.rokid.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kernel behaviour against fake ports: channel gating, the serial write path, automatic
 * observation after every action, `accepted`/`applied`/`verified` semantics, timeout and cancel.
 *
 * The tests never touch Android: the ports are the only device-facing surface.
 */
class DeviceKernelTest {

    private lateinit var accessibility: FakeAccessibilityPort
    private lateinit var kernel: DeviceKernel
    private val kernels = mutableListOf<DeviceKernel>()

    @BeforeTest
    fun setUp() {
        accessibility = FakeAccessibilityPort()
        kernel = newKernel()
    }

    @AfterTest
    fun tearDown() {
        kernels.forEach { runCatching { it.close() } }
        kernels.clear()
    }

    private fun newKernel(shell: ShellPort? = null): DeviceKernel =
        DeviceKernel(accessibility, shell, settlePolicy = NO_SETTLE).also { kernels += it }

    // ── observe ─────────────────────────────────────────────────────────────

    @Test
    fun `observe stamps a kernel revision and hands the filter to the port`() {
        val envelope = kernel.observe(ScreenFilter.ALL)

        assertEquals(DeviceEnvelope.TOOL_NAME, envelope.tool)
        assertEquals("observe", envelope.action)
        assertEquals("accessibility", envelope.channel)
        assertTrue(envelope.ok)
        assertTrue(envelope.accepted)
        assertNull(envelope.applied)
        assertNull(envelope.verified)
        assertNull(envelope.error)
        assertEquals(ScreenFilter.ALL, accessibility.filters.single())
        assertEquals(1L, kernel.revision)
        assertTrue(envelope.timing.observeMs >= 0)
        assertEquals(1L, kernel.latestObservation?.revision)
        // The kernel assigns refs to the shared registry, so Task 9/10 can resolve what it reports.
        assertIs<RefResult.Ok>(kernel.registry.resolve(kernel.latestObservation!!, Ref("#n0")))
    }

    // ── ensureReady ─────────────────────────────────────────────────────────

    @Test
    fun `ensureReady reports the device state before the channel kind`() {
        assertNull(kernel.ensureReady(ReadyKind.OBSERVE))
        assertNull(kernel.ensureReady(ReadyKind.ACT))
        assertNull(kernel.ensureReady(ReadyKind.SCREENSHOT))
        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, kernel.ensureReady(ReadyKind.SHELL)?.code)

        accessibility.interactive = false
        assertEquals(DeviceErrorCodes.DEVICE_SCREEN_OFF, kernel.ensureReady(ReadyKind.ACT)?.code)

        accessibility.interactive = true
        accessibility.keyguardLocked = true
        assertEquals(DeviceErrorCodes.DEVICE_LOCKED, kernel.ensureReady(ReadyKind.OBSERVE)?.code)
        assertEquals(DeviceErrorCodes.DEVICE_LOCKED, kernel.ensureReady(ReadyKind.SHELL)?.code)

        accessibility.keyguardLocked = false
        accessibility.bound = false
        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, kernel.ensureReady(ReadyKind.OBSERVE)?.code)
        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, kernel.ensureReady(ReadyKind.ACT)?.code)
        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, kernel.ensureReady(ReadyKind.SCREENSHOT)?.code)
    }

    @Test
    fun `a screen-off device is refused before any observation is attempted`() {
        accessibility.interactive = false

        val observed = kernel.observe()
        val acted = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertEquals(DeviceErrorCodes.DEVICE_SCREEN_OFF, observed.error?.code)
        assertEquals(DeviceErrorCodes.DEVICE_SCREEN_OFF, acted.error?.code)
        assertFalse(observed.ok)
        assertFalse(acted.ok)
        assertFalse(acted.accepted)
        assertEquals(0L, kernel.revision)
        assertEquals(0L, kernel.generation)
        assertTrue(accessibility.calls.isEmpty(), "no channel call may be attempted: ${accessibility.calls}")
    }

    @Test
    fun `a locked device is refused with DEVICE_LOCKED`() {
        accessibility.keyguardLocked = true

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertEquals(DeviceErrorCodes.DEVICE_LOCKED, envelope.error?.code)
        assertTrue(accessibility.calls.isEmpty())
        assertEquals(0L, kernel.generation)
    }

    // ── act ─────────────────────────────────────────────────────────────────

    @Test
    fun `act performs the node action, then observes the screen change`() {
        assertTrue(kernel.observe().ok)
        accessibility.resetCalls()
        accessibility.onNodeAction = { _, _ ->
            accessibility.screen = testObservation(listOf(testNode(text = "Wi-Fi, connected")))
        }

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertEquals(listOf("node:#n0:click", "observe:INTERACTIVE"), accessibility.calls)
        assertTrue(envelope.ok)
        assertTrue(envelope.accepted)
        assertEquals(true, envelope.applied)
        assertEquals("screen_change", envelope.verified?.kind)
        assertEquals(2L, envelope.verified?.revision)
        assertNull(envelope.error)
        assertEquals(1L, kernel.generation)
        assertEquals(2L, kernel.revision)
        assertEquals("Wi-Fi, connected", kernel.latestObservation?.nodes?.single()?.text)
        assertTrue(
            envelope.timing.totalMs >= envelope.timing.actMs + envelope.timing.observeMs,
            "total time must cover the action and the observation: ${envelope.timing}",
        )
    }

    @Test
    fun `a refused node action still returns the observation taken after it`() {
        assertTrue(kernel.observe().ok)
        accessibility.resetCalls()
        accessibility.nodeActionResult = false

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertEquals(listOf("node:#n0:click", "observe:INTERACTIVE"), accessibility.calls)
        assertFalse(envelope.ok)
        assertFalse(envelope.accepted)
        assertEquals(false, envelope.applied)
        assertEquals(DeviceErrorCodes.NODE_NOT_CLICKABLE, envelope.error?.code)
        assertNull(envelope.verified)
        assertEquals(1L, kernel.generation)
        assertEquals(2L, kernel.revision)
        assertEquals(2L, kernel.latestObservation?.revision)
    }

    @Test
    fun `an accepted action with no visible change is ACCEPTED_NO_EFFECT`() {
        assertTrue(kernel.observe().ok)

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertFalse(envelope.ok)
        assertTrue(envelope.accepted)
        assertEquals(false, envelope.applied)
        assertNull(envelope.verified)
        assertEquals(DeviceErrorCodes.ACCEPTED_NO_EFFECT, envelope.error?.code)
    }

    @Test
    fun `an act without a baseline reports applied unknown instead of true`() {
        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertEquals(listOf("node:#n0:click", "observe:ALL"), accessibility.calls)
        assertTrue(envelope.ok)
        assertTrue(envelope.accepted)
        assertNull(envelope.applied)
        assertNull(envelope.verified)
        assertNull(envelope.error)
        assertEquals(1L, kernel.generation)
        assertEquals(1L, kernel.revision)
    }

    @Test
    fun `an act with no target is refused without touching the device`() {
        val envelope = kernel.act(ActKind.TAP)

        assertFalse(envelope.ok)
        assertFalse(envelope.accepted)
        assertEquals(DeviceErrorCodes.INVALID_ARGUMENTS, envelope.error?.code)
        assertEquals(0L, kernel.generation)
        assertEquals(0L, kernel.revision)
        assertTrue(accessibility.calls.isEmpty(), "planning a target-less tap must not read the screen")
    }

    @Test
    fun `a type action carries its text to the node action`() {
        assertTrue(kernel.observe().ok)
        accessibility.resetCalls()

        val envelope = kernel.act(ActKind.TYPE, ref = Ref("#n1"), value = "hello")

        assertEquals("set_text\u0000text=hello", accessibility.rawNodeActions.single())
        assertEquals(
            "set_text" to mapOf("text" to "hello"),
            DeviceActions.decode(accessibility.rawNodeActions.single()),
            "the adapter can split the port action string back into the token and its arguments",
        )
        assertFalse(envelope.ok, "the unchanged fake screen is ACCEPTED_NO_EFFECT")
    }

    // ── serialization / generation ──────────────────────────────────────────

    @Test
    fun `concurrent writes run one at a time in submission order`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        accessibility.onNodeAction = { ref, _ ->
            if (ref == Ref("#n0")) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        var first: DeviceEnvelope? = null
        var second: DeviceEnvelope? = null

        val one = thread { first = kernel.act(ActKind.TAP, ref = Ref("#n0")) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the first write must reach the port")
        val requesting = CountDownLatch(1)
        val two = thread {
            requesting.countDown()
            second = kernel.act(ActKind.TAP, ref = Ref("#n1"))
        }
        assertTrue(requesting.await(5, TimeUnit.SECONDS))
        // Let the second caller enqueue while the first write is still blocked at the port. A serial
        // worker keeps it queued (its node action is not in the call log yet); a concurrent worker
        // would start it now, which the concurrency and order assertions below catch.
        Thread.sleep(150)
        assertEquals(
            listOf("node:#n0:click"),
            accessibility.calls,
            "the second write must still be queued behind the blocked first one",
        )
        release.countDown()
        one.join(10_000)
        two.join(10_000)

        assertFalse(one.isAlive, "the first write must finish")
        assertFalse(two.isAlive, "the second write must finish")
        assertEquals(
            listOf("node:#n0:click", "observe:ALL", "node:#n1:click", "observe:ALL"),
            accessibility.calls,
        )
        assertEquals(1, accessibility.maxConcurrentWrites.get(), "writes must never overlap")
        assertEquals(2L, kernel.generation)
        assertEquals(2L, kernel.revision)
    }

    // ── timeout / cancel ────────────────────────────────────────────────────

    @Test
    fun `a stuck accessibility call times out instead of hanging`() {
        accessibility.onNodeAction = { _, _ -> Thread.sleep(60_000) }
        val startedAt = System.nanoTime()

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"), timeoutMillis = 250)

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertFalse(envelope.ok)
        assertFalse(envelope.accepted)
        assertNull(envelope.applied)
        assertEquals(DeviceErrorCodes.ACTION_TIMEOUT, envelope.error?.code)
        assertTrue(elapsedMillis < 5_000, "the timeout must bound the call, took $elapsedMillis ms")
    }

    @Test
    fun `cancel aborts the in-flight action and refuses the rest of the turn`() {
        val started = CountDownLatch(1)
        accessibility.onNodeAction = { _, _ ->
            started.countDown()
            Thread.sleep(60_000)
        }
        var inFlight: DeviceEnvelope? = null

        val worker = thread { inFlight = kernel.act(ActKind.TAP, ref = Ref("#n0"), timeoutMillis = 30_000) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the action must reach the port")
        kernel.cancel()
        worker.join(5_000)

        assertFalse(worker.isAlive, "cancel must release the waiting call")
        assertEquals(DeviceErrorCodes.CANCELLED, inFlight?.error?.code)
        assertFalse(inFlight?.ok ?: true)

        val afterCancel = kernel.act(ActKind.TAP, ref = Ref("#n0"))
        assertEquals(DeviceErrorCodes.CANCELLED, afterCancel.error?.code)
        assertEquals(1, accessibility.nodeActions().size, "a cancelled turn must not execute another write")

        kernel.beginTurn()
        accessibility.onNodeAction = null
        val nextTurn = kernel.act(ActKind.TAP, ref = Ref("#n0"))
        assertNotEquals(DeviceErrorCodes.CANCELLED, nextTurn.error?.code)
    }

    @Test
    fun `cancel also drops an operation queued behind the in-flight one`() {
        val started = CountDownLatch(1)
        accessibility.onNodeAction = { _, _ ->
            started.countDown()
            Thread.sleep(60_000)
        }
        var acted: DeviceEnvelope? = null
        var observed: DeviceEnvelope? = null

        val one = thread { acted = kernel.act(ActKind.TAP, ref = Ref("#n0"), timeoutMillis = 30_000) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the action must reach the port")
        val two = thread { observed = kernel.observe() }
        // Give the queued read time to be submitted behind the running write.
        Thread.sleep(150)
        kernel.cancel()
        one.join(5_000)
        two.join(5_000)

        assertFalse(one.isAlive, "the running action must be interrupted")
        assertFalse(two.isAlive, "the queued read must be dropped")
        assertEquals(DeviceErrorCodes.CANCELLED, acted?.error?.code)
        assertEquals(DeviceErrorCodes.CANCELLED, observed?.error?.code)
    }

    // ── shell channel ──────────────────────────────────────────────────────

    @Test
    fun `press key runs through the shell channel and observes afterwards`() {
        val shell = FakeShellPort()
        val shellKernel = newKernel(shell)
        assertTrue(shellKernel.observe().ok)
        accessibility.resetCalls()
        shell.onRun = { accessibility.screen = testObservation(listOf(testNode(text = "Home"))) }

        val envelope = shellKernel.act(ActKind.PRESS_KEY, key = "home")

        val call = shell.calls.single()
        assertEquals("input keyevent KEYCODE_HOME", call.command)
        assertTrue(call.timeoutMillis in 1..30_000, "remaining budget: ${call.timeoutMillis}")
        assertEquals(65_536, call.maxBytes)
        assertEquals("shell", envelope.channel)
        assertTrue(envelope.accepted)
        assertEquals(true, envelope.applied)
        assertEquals("screen_change", envelope.verified?.kind)
        assertEquals(listOf("observe:INTERACTIVE"), accessibility.calls)
        assertEquals(1L, shellKernel.generation)
    }

    @Test
    fun `a failed shell command is reported and still observed`() {
        val shell = FakeShellPort()
        shell.outcome = ShellOutcome(
            exitCode = 1,
            stdout = "",
            stderr = "shizuku is not bound",
            truncated = false,
            failure = DeviceErrorCodes.SHELL_NOT_BOUND,
        )
        val shellKernel = newKernel(shell)
        assertTrue(shellKernel.observe().ok)
        accessibility.resetCalls()

        val envelope = shellKernel.act(ActKind.PRESS_KEY, key = "BACK")

        assertFalse(envelope.ok)
        assertFalse(envelope.accepted)
        assertEquals(DeviceErrorCodes.SHELL_NOT_BOUND, envelope.error?.code)
        assertEquals(2L, shellKernel.revision)
        assertEquals(listOf("observe:INTERACTIVE"), accessibility.calls)
    }

    @Test
    fun `a shell-only action without a shell is CHANNEL_UNAVAILABLE`() {
        val envelope = kernel.act(ActKind.PRESS_KEY, key = "HOME")

        assertFalse(envelope.ok)
        assertFalse(envelope.accepted)
        assertEquals("shell", envelope.channel)
        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, envelope.error?.code)
        assertEquals(0L, kernel.generation)
        assertTrue(accessibility.calls.isEmpty())
    }

    // ── screenshot / status ────────────────────────────────────────────────

    @Test
    fun `screenshot returns an artifact reference, never inline bytes`() {
        val envelope = kernel.screenshot()

        assertTrue(envelope.ok)
        assertEquals("screenshot", envelope.action)
        assertEquals("accessibility", envelope.channel)
        assertTrue(envelope.accepted)
        assertNull(envelope.applied)
        val image = envelope.images.single()
        // R67: without an artifact store the honest answer is still the pending placeholder, but it now carries a
        // diagnostic instead of silently looking like a real reference.
        assertEquals("pending", image.artifactId)
        assertEquals("image/jpeg", image.mime)
        assertEquals(768, image.width)
        assertEquals(1664, image.height)
        assertEquals("", image.sha256)
        val diagnostic = requireNotNull(envelope.data) { "a pending reference must explain itself" }
        assertTrue(diagnostic.contains("screenshot_artifact_pending"), diagnostic)
        assertTrue(diagnostic.contains("NO_STORE"), diagnostic)
        assertEquals(false, diagnostic.contains("1, 2, 3"), "image bytes must never be inlined into data")
        assertEquals(0L, kernel.revision, "a screenshot is not an observation")
    }

    @Test
    fun `screenshot reports an unavailable capability when the channel returns nothing`() {
        accessibility.screenshotResult = null

        val envelope = kernel.screenshot()

        assertFalse(envelope.ok)
        assertTrue(envelope.images.isEmpty())
        assertEquals(DeviceErrorCodes.UNSUPPORTED, envelope.error?.code)
    }

    @Test
    fun `status reports the foreground app and channel health even when the screen is off`() {
        accessibility.interactive = false
        accessibility.foregroundResult = Foreground("com.example.app", ".MainActivity")

        val envelope = kernel.status()

        assertTrue(envelope.ok, "status is diagnostic and must not be gated by the screen state")
        val data = requireNotNull(envelope.data)
        assertTrue(data.contains("com.example.app/.MainActivity"), "foreground data: $data")
        assertTrue(data.contains("interactive=false"), "health data: $data")
        assertTrue(data.contains("accessibility=true"), "health data: $data")
        assertTrue(data.contains("shell=false"), "health data: $data")

        val health = kernel.health()
        assertTrue(health.accessibilityBound)
        assertFalse(health.interactive)
        assertFalse(health.keyguardLocked)
        assertFalse(health.shellAvailable)
        assertFalse(health.observeReady, "a non-interactive screen cannot be observed")
        assertFalse(health.actReady)
        assertFalse(health.shellReady, "no shell port is wired")
    }

    // ── wait ───────────────────────────────────────────────────────────────

    @Test
    fun `wait for text stops at the observation containing it`() {
        accessibility.screen = testObservation(listOf(testNode(text = "Loading")))
        var polls = 0
        accessibility.onObserve = {
            polls += 1
            if (polls == 2) {
                accessibility.screen = testObservation(listOf(testNode(text = "Done")))
            }
        }

        val envelope = kernel.waitFor(text = "Done", foreground = null, idle = false, timeoutMillis = 5_000)

        assertTrue(envelope.ok)
        assertEquals("wait", envelope.action)
        assertEquals("expected", envelope.verified?.kind)
        assertEquals(kernel.revision, envelope.verified?.revision)
        assertEquals("Done", kernel.latestObservation?.nodes?.single()?.text)
        assertTrue(polls >= 2, "the wait must poll until the text is on screen, polls=$polls")
    }

    @Test
    fun `wait for the foreground app accepts a package name`() {
        accessibility.foregroundResult = Foreground("com.example.app", ".MainActivity")

        val envelope = kernel.waitFor(text = null, foreground = "com.example.app", idle = false, timeoutMillis = 2_000)

        assertTrue(envelope.ok)
        assertEquals("expected", envelope.verified?.kind)
    }

    @Test
    fun `wait times out with VERIFY_TIMEOUT and keeps the last observation`() {
        accessibility.screen = testObservation(listOf(testNode(text = "Loading")))
        val startedAt = System.nanoTime()

        val envelope = kernel.waitFor(text = "Never", foreground = null, idle = false, timeoutMillis = 250)

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertFalse(envelope.ok)
        assertNull(envelope.applied)
        assertNull(envelope.verified)
        assertEquals(DeviceErrorCodes.VERIFY_TIMEOUT, envelope.error?.code)
        assertEquals("Loading", kernel.latestObservation?.nodes?.single()?.text)
        assertTrue(elapsedMillis < 5_000, "the wait must be bounded, took $elapsedMillis ms")
    }

    @Test
    fun `act wait returns once the screen stops changing`() {
        val envelope = kernel.act(ActKind.WAIT, timeoutMillis = 3_000)

        assertTrue(envelope.ok)
        assertEquals("wait", envelope.action)
        assertEquals("idle", envelope.verified?.kind)
        assertNull(envelope.applied)
        assertEquals(0L, kernel.generation, "waiting is not a write")
        assertTrue(kernel.revision >= 2, "idle needs at least two observations")
    }

    // ── cancellation gaps and input validation ─────────────────────────────

    @Test
    fun `a cancel during a planner lookup prevents the planned gesture`() {
        accessibility.screen = testObservation(
            listOf(testNode(text = "Wi-Fi", bounds = Bounds(0, 0, 100, 100))),
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        accessibility.onObserve = {
            entered.countDown()
            // The planner lookup is a platform read that may ignore interrupts. Once cancel()
            // returns, the caller has been told CANCELLED, so no later write may run.
            awaitIgnoringInterrupts(release)
        }
        var envelope: DeviceEnvelope? = null

        val worker = thread { envelope = kernel.act(ActKind.TAP, text = "Wi-Fi", timeoutMillis = 30_000) }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the planner lookup must reach the port")
        kernel.cancel()
        worker.join(5_000)
        assertFalse(worker.isAlive, "cancel must release the waiting caller")
        assertEquals(DeviceErrorCodes.CANCELLED, envelope?.error?.code)

        // Resume the old operation after the next turn is armed: its per-operation token must keep
        // it dead even though the turn-level flag has been cleared again.
        kernel.beginTurn()
        release.countDown()
        accessibility.onObserve = null
        assertTrue(kernel.observe().ok, "the worker must be free again")
        assertTrue(
            accessibility.calls.none { it.startsWith("gesture") || it.startsWith("node:") },
            "a cancelled action must not reach the platform: ${accessibility.calls}",
        )
        assertEquals(0L, kernel.generation)
    }

    @Test
    fun `a timeout during a planner lookup cannot dispatch the gesture afterwards`() {
        accessibility.screen = testObservation(
            listOf(testNode(text = "Wi-Fi", bounds = Bounds(0, 0, 100, 100))),
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        accessibility.onObserve = {
            entered.countDown()
            awaitIgnoringInterrupts(release)
        }
        var envelope: DeviceEnvelope? = null

        val worker = thread { envelope = kernel.act(ActKind.TAP, text = "Wi-Fi", timeoutMillis = 250) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(DeviceErrorCodes.ACTION_TIMEOUT, envelope?.error?.code)

        release.countDown()
        accessibility.onObserve = null
        assertTrue(kernel.observe().ok, "the worker must be free again")
        assertTrue(
            accessibility.calls.none { it.startsWith("gesture") || it.startsWith("node:") },
            "a timed-out action must not reach the platform: ${accessibility.calls}",
        )
        assertEquals(0L, kernel.generation)
    }

    @Test
    fun `a timeout during the shell ready probe cannot run the key event afterwards`() {
        val shell = FakeShellPort()
        val shellKernel = newKernel(shell)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        accessibility.onIsInteractive = {
            entered.countDown()
            awaitIgnoringInterrupts(release)
        }
        var envelope: DeviceEnvelope? = null

        val worker = thread { envelope = shellKernel.act(ActKind.PRESS_KEY, key = "HOME", timeoutMillis = 250) }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the ready probe must reach the port")
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(DeviceErrorCodes.ACTION_TIMEOUT, envelope?.error?.code)

        release.countDown()
        accessibility.onIsInteractive = null
        assertTrue(shellKernel.observe().ok, "the worker must be free again")
        assertTrue(shell.calls.isEmpty(), "a timed-out key event must not reach the shell: ${shell.calls}")
        assertEquals(0L, shellKernel.generation)
    }

    @Test
    fun `an interrupted caller cancels the operation it was waiting for`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        accessibility.onIsInteractive = {
            entered.countDown()
            awaitIgnoringInterrupts(release)
        }
        var envelope: DeviceEnvelope? = null

        val caller = thread { envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"), timeoutMillis = 30_000) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(5_000)
        assertFalse(caller.isAlive)
        assertEquals(DeviceErrorCodes.CANCELLED, envelope?.error?.code)

        release.countDown()
        accessibility.onIsInteractive = null
        assertTrue(kernel.observe().ok, "the worker must be free again")
        assertTrue(
            accessibility.nodeActions().isEmpty(),
            "the interrupted caller's write must not run: ${accessibility.calls}",
        )
        assertEquals(0L, kernel.generation)
    }

    @Test
    fun `a key with shell metacharacters is rejected before the shell runs`() {
        val shell = FakeShellPort()
        val shellKernel = newKernel(shell)
        val hostile = listOf("HOME; \$0", "HOME && reboot", "KEYCODE_HOME;input tap 1 1", "\$(reboot)", "HOME\nHOME")

        hostile.forEach { key ->
            val envelope = shellKernel.act(ActKind.PRESS_KEY, key = key)
            assertFalse(envelope.ok, "a hostile key must be refused: $key")
            assertEquals(DeviceErrorCodes.INVALID_ARGUMENTS, envelope.error?.code, "key: $key")
        }

        assertTrue(shell.calls.isEmpty(), "no hostile key may reach the shell: ${shell.calls}")
        assertEquals(0L, shellKernel.generation)
    }

    @Test
    fun `a type value containing the argument separator is refused before the device is touched`() {
        val envelope = kernel.act(ActKind.TYPE, ref = Ref("#n1"), value = "hello\u0000text=evil")

        assertFalse(envelope.ok)
        assertEquals(DeviceErrorCodes.INVALID_ARGUMENTS, envelope.error?.code)
        assertEquals(0L, kernel.generation)
        assertTrue(accessibility.calls.isEmpty(), "a malformed text argument must not reach the port: ${accessibility.calls}")
    }

    // ── readiness gate before the planner ──────────────────────────────────

    @Test
    fun `a screen-off device refuses text and swipe targets before reading the screen`() {
        accessibility.interactive = false

        val textTarget = kernel.act(ActKind.TAP, text = "Wi-Fi")
        val swipe = kernel.act(ActKind.SWIPE, direction = "up")

        assertEquals(DeviceErrorCodes.DEVICE_SCREEN_OFF, textTarget.error?.code)
        assertEquals(DeviceErrorCodes.DEVICE_SCREEN_OFF, swipe.error?.code)
        assertTrue(accessibility.calls.isEmpty(), "no screen read may happen before the gate: ${accessibility.calls}")
        assertEquals(0L, kernel.generation)
    }

    @Test
    fun `a locked device refuses text and swipe targets before reading the screen`() {
        accessibility.keyguardLocked = true

        val textTarget = kernel.act(ActKind.TAP, text = "Wi-Fi")
        val swipe = kernel.act(ActKind.SWIPE, direction = "up")

        assertEquals(DeviceErrorCodes.DEVICE_LOCKED, textTarget.error?.code)
        assertEquals(DeviceErrorCodes.DEVICE_LOCKED, swipe.error?.code)
        assertTrue(accessibility.calls.isEmpty(), "no screen read may happen before the gate: ${accessibility.calls}")
        assertEquals(0L, kernel.generation)
    }

    @Test
    fun `an unbound channel refuses reads before the port is called`() {
        accessibility.bound = false

        val observed = kernel.observe()
        val acted = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, observed.error?.code)
        assertEquals(DeviceErrorCodes.CHANNEL_UNAVAILABLE, acted.error?.code)
        assertTrue(accessibility.calls.isEmpty(), "an unbound channel must be gated before the port: ${accessibility.calls}")
    }

    @Test
    fun `a successful shell key is not turned into a failure when accessibility is unbound`() {
        val shell = FakeShellPort()
        val shellKernel = newKernel(shell)
        accessibility.bound = false

        val envelope = shellKernel.act(ActKind.PRESS_KEY, key = "HOME")

        assertTrue(envelope.ok, "a successful write must not be failed by the unavailable observer")
        assertTrue(envelope.accepted)
        assertNull(envelope.applied)
        assertNull(envelope.verified)
        assertNull(envelope.error)
        assertEquals("shell", envelope.channel)
        assertEquals(1, shell.calls.size, "the shell write itself must still run: ${shell.calls}")
        assertTrue(accessibility.calls.isEmpty(), "no observation may be attempted while unbound: ${accessibility.calls}")
        assertTrue(envelope.data?.contains("diagnostic") == true, "the envelope must say the write was not observed: ${envelope.data}")
    }

    @Test
    fun `a refused gesture reports a gesture failure instead of a non-clickable node`() {
        assertTrue(kernel.observe().ok)
        accessibility.gestureResult = false

        val envelope = kernel.act(ActKind.SWIPE, direction = "up")

        assertFalse(envelope.ok)
        assertFalse(envelope.accepted)
        assertEquals(DeviceErrorCodes.GESTURE_FAILED, envelope.error?.code)
    }

    @Test
    fun `a wait with a huge timeout does not overflow its deadline`() {
        accessibility.screen = testObservation(listOf(testNode(text = "Loading")))
        var polls = 0
        accessibility.onObserve = {
            polls += 1
            if (polls == 2) accessibility.screen = testObservation(listOf(testNode(text = "Done")))
        }

        val envelope = kernel.waitFor(text = "Done", foreground = null, idle = false, timeoutMillis = Long.MAX_VALUE)

        assertTrue(envelope.ok)
        assertEquals("expected", envelope.verified?.kind)
        assertEquals("Done", kernel.latestObservation?.nodes?.single()?.text)
    }

    /** Simulates a platform call that cannot be interrupted, so the post-boundary check is the guard. */
    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                if (latch.await(10, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                // Keep waiting: the port call does not honour interruption.
            }
        }
    }

    private companion object {
        /**
         * These tests are not about settle, so their kernels use a zero change wait: the act still
         * observes once (and `applied` still compares that look), but does not spend the production
         * budgets. [DeviceKernelSettleTest] owns the settle behaviour.
         */
        val NO_SETTLE = SettlePolicy(
            tapChangeWaitMillis = 0L,
            scrollChangeWaitMillis = 0L,
            typeChangeWaitMillis = 0L,
            keyChangeWaitMillis = 0L,
            heldStillMillis = 0L,
            pollIntervalMillis = 5L,
        )
    }
}
