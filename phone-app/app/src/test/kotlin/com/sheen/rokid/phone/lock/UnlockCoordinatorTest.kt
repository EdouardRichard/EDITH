package com.sheen.rokid.phone.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockCoordinatorTest {

    private class FakeState(var locked: Boolean, var secure: Boolean) : LockState {
        override fun isLocked() = locked
        override fun isSecure() = secure
    }

    private class FakeStore(var pin: String? = null) : LockPinStore {
        override fun hasPin() = pin != null
        override fun read(): CharArray? = pin?.toCharArray()
        override fun save(pin: String) { this.pin = pin }
        override fun clear() { pin = null }
    }

    private class FakeShell(
        private val state: FakeState,
        private val unlockOn: String? = null,
        private val unlockPinOn: String? = null,
        private val interactive: () -> Boolean = { true },
        private val pinVisible: () -> Boolean? = { null },
    ) : LockShell {
        val commands = mutableListOf<String>()
        var bouncerShownWhileInteractive = false
        var pinTypedWhilePinVisible = false
        override fun run(command: String): Boolean {
            commands += command
            if (command == "input keyevent 82") bouncerShownWhileInteractive = interactive()
            val keyCode = command.removePrefix("input keyevent ").toIntOrNull()
            if (keyCode != null && keyCode in 7..16 && command == "input keyevent $keyCode") {
                pinTypedWhilePinVisible = pinVisible() == true
            }
            if (command == unlockOn) state.locked = false
            if (command == unlockPinOn) state.locked = false
            return true
        }
    }

    private fun coordinator(
        state: FakeState,
        shell: LockShell,
        store: LockPinStore = FakeStore(),
        a11y: Boolean = true,
        keepAlive: MutableList<String> = mutableListOf(),
        interactive: () -> Boolean = { true },
        pinVisible: () -> Boolean? = { null },
        trace: MutableList<String> = mutableListOf(),
        sleeps: MutableList<Long> = mutableListOf(),
    ): UnlockCoordinator {
        var now = 0L
        return UnlockCoordinator(
            lockState = state,
            shell = shell,
            pins = store,
            accessibilityReady = { a11y },
            keepAlive = { keepAlive += "acquire" },
            nowMillis = { now },
            sleepMillis = { sleeved -> now += sleeved; sleeps += sleeved },
            interactive = interactive,
            keyguardPinVisible = pinVisible,
            trace = { trace += it },
        )
    }

    @Test
    fun anUnlockedDeviceNeedsNoShellCommand() {
        val state = FakeState(locked = false, secure = false)
        val shell = FakeShell(state)
        val outcome = coordinator(state, shell).ensureUnlocked(requireAccessibility = true)

        assertEquals(UnlockOutcome.ALREADY_UNLOCKED, outcome)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun aSwipeLockIsDismissedWithoutACredential() {
        val state = FakeState(locked = true, secure = false)
        val shell = FakeShell(state, unlockOn = "wm dismiss-keyguard")
        val outcome = coordinator(state, shell).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertTrue(shell.commands.contains("input keyevent 224"))
        assertTrue(shell.commands.contains("wm dismiss-keyguard"))
        assertFalse("a swipe lock must never ask for a PIN", shell.commands.any { it.contains("input text") })
    }

    @Test
    fun aSecureLockTypesTheConfiguredPinOneDigitPerCommandAndEnter() {
        val state = FakeState(locked = true, secure = true)
        val shell = FakeShell(state, unlockPinOn = "input keyevent 66")
        val store = FakeStore(pin = "123456")
        val keepAlive = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val outcome = coordinator(
            state, shell, store, keepAlive = keepAlive, sleeps = sleeps,
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertEquals(
            listOf(
                "input keyevent 224",
                "input keyevent 82",
                "input keyevent 8",
                "input keyevent 9",
                "input keyevent 10",
                "input keyevent 11",
                "input keyevent 12",
                "input keyevent 13",
                "input keyevent 66",
            ),
            shell.commands,
        )
        assertFalse("the PIN must never be sent as text", shell.commands.any { it.contains("input text") })
        assertEquals(listOf("acquire"), keepAlive)
        assertEquals(
            "each digit is followed by its own short settle so the keyguard is not flooded",
            6,
            sleeps.count { it == 20L },
        )
    }

    @Test
    fun aSecureLockWaitsForTheScreenBeforeAskingForKeyguard() {
        val state = FakeState(locked = true, secure = true)
        var polls = 0
        val interactive = { polls++ > 0 }
        val shell = FakeShell(
            state,
            unlockPinOn = "input keyevent 66",
            interactive = interactive,
            pinVisible = { true },
        )
        val outcome = coordinator(
            state, shell, FakeStore(pin = "123456"), interactive = interactive, pinVisible = { true },
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertTrue("the keyguard must only be asked for once the screen is interactive",
            shell.bouncerShownWhileInteractive)
    }

    @Test
    fun aSecureLockWaitsForThePinViewBeforeTypingThePin() {
        val state = FakeState(locked = true, secure = true)
        var probes = 0
        val pinVisible = { probes++ > 0 }
        val shell = FakeShell(
            state,
            unlockPinOn = "input keyevent 66",
            pinVisible = pinVisible,
        )
        val outcome = coordinator(
            state, shell, FakeStore(pin = "123456"), pinVisible = pinVisible,
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertTrue("digits must only be injected once the PIN view is up", shell.pinTypedWhilePinVisible)
    }

    @Test
    fun aScreenThatNeverComesOnIsReportedWithoutTypingThePin() {
        val state = FakeState(locked = true, secure = true)
        val shell = FakeShell(state, unlockPinOn = "input keyevent 66", interactive = { false })
        val outcome = coordinator(
            state, shell, FakeStore(pin = "123456"), interactive = { false }, pinVisible = { true },
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.SCREEN_NOT_INTERACTIVE, outcome)
        assertEquals(listOf("input keyevent 224"), shell.commands)
    }

    @Test
    fun aProbeThatCannotSeeTheKeyguardStillTypesAfterTheMeasuredSettle() {
        val state = FakeState(locked = true, secure = true)
        val shell = FakeShell(state, unlockPinOn = "input keyevent 66", pinVisible = { null })
        val outcome = coordinator(
            state, shell, FakeStore(pin = "123456"), pinVisible = { null },
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertTrue("an unavailable probe must not block a secure unlock",
            shell.commands.contains("input keyevent 8") && shell.commands.contains("input keyevent 13"))
    }

    @Test
    fun anUnavailableProbeWaitsTenTwoHundredMillisecondStepsAtMost() {
        val state = FakeState(locked = true, secure = true)
        val sleeps = mutableListOf<Long>()
        val shell = FakeShell(state, unlockPinOn = "input keyevent 66", pinVisible = { null })
        val outcome = coordinator(
            state, shell, FakeStore(pin = "123456"), pinVisible = { null }, sleeps = sleeps,
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertEquals("200 ms is the retry step", 10, sleeps.count { it == 200L })
        assertEquals("the whole probe-unavailable settle is bounded at 2 s", 2_000L, sleeps.filter { it == 200L }.sum())
    }

    @Test
    fun aShellCommandTheChannelRejectsIsTracedWithoutAbortingTheAttempt() {
        val state = FakeState(locked = true, secure = true)
        val trace = mutableListOf<String>()
        val shell = object : LockShell {
            override fun run(command: String): Boolean {
                if (command == "input keyevent 66") state.locked = false
                return command != "input keyevent 82"
            }
        }
        val outcome = coordinator(
            state, shell, FakeStore(pin = "123456"), pinVisible = { true }, trace = trace,
        ).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.UNLOCKED, outcome)
        assertTrue(
            "a rejected show-bouncer command must be observable, not silent: $trace",
            trace.any { it.contains("command_failed") && it.contains("show_bouncer") },
        )
    }

    @Test
    fun aSecureLockWithoutAPinRefusesBeforeTouchingTheShell() {
        val state = FakeState(locked = true, secure = true)
        val shell = FakeShell(state)
        val outcome = coordinator(state, shell).ensureUnlocked(requireAccessibility = true)

        assertEquals(UnlockOutcome.NO_PIN, outcome)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun aPinThatDoesNotDismissTheKeyguardIsReportedAndNotRetried() {
        val state = FakeState(locked = true, secure = true)
        val shell = FakeShell(state, unlockPinOn = "never")
        val outcome = coordinator(state, shell, FakeStore(pin = "123456")).ensureUnlocked(requireAccessibility = false)

        assertEquals(UnlockOutcome.STILL_LOCKED, outcome)
        assertEquals(1, shell.commands.count { it == "input keyevent 66" })
        assertEquals(1, shell.commands.count { it == "input keyevent 82" })
    }

    @Test
    fun anUnlockedDeviceStillWaitsForAccessibilityWhenRequired() {
        val state = FakeState(locked = false, secure = true)
        val shell = FakeShell(state)
        val outcome = coordinator(state, shell, a11y = false).ensureUnlocked(requireAccessibility = true)

        assertEquals(UnlockOutcome.A11Y_NOT_READY, outcome)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun twoConcurrentCallersShareOnePinAttempt() {
        val state = FakeState(locked = true, secure = true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val shell = object : LockShell {
            val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
            override fun run(command: String): Boolean {
                commands += command
                if (command == "input keyevent 224") {
                    entered.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                if (command == "input keyevent 66") state.locked = false
                return true
            }
        }
        val coordinator = coordinator(state, shell, FakeStore(pin = "123456"), pinVisible = { true })
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<UnlockOutcome> { coordinator.ensureUnlocked(requireAccessibility = false) }
            assertTrue("the first caller must reach the shell", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<UnlockOutcome> { coordinator.ensureUnlocked(requireAccessibility = false) }
            release.countDown()
            val outcomes = listOf(
                first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                second.get(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertTrue("both waiters must observe a usable phone: $outcomes", outcomes.all { it.usable })
            assertEquals(1, shell.commands.count { it == "input keyevent 82" })
            assertEquals(1, shell.commands.count { it == "input keyevent 66" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theAccessibilityServiceMustComeBackBeforeADeviceActionRuns() {
        val state = FakeState(locked = true, secure = false)
        val shell = FakeShell(state, unlockOn = "wm dismiss-keyguard")
        val outcome = coordinator(state, shell, a11y = false).ensureUnlocked(requireAccessibility = true)

        assertEquals(UnlockOutcome.A11Y_NOT_READY, outcome)
    }
}
