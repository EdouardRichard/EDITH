package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.HostServices
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The App-side environment that finally gives the host a real output path.
 *
 * `HostRuntime(1)` falls back to `MissingHostEnvironment`, whose output throws
 * `CapabilityMissing("display unavailable")` by design, and no production `HostEnvironment` existed anywhere in
 * `main` - so display could never reach the glasses however correct the hint code was. This environment supplies one.
 *
 * The rule the tests below pin is the one that keeps that from becoming a lie: **`availableCapabilities` must name
 * only what is genuinely wired and present right now.** The host refuses a plugin whose requirements are not in that
 * set, so an optimistic set would not "enable more" - it would let a plugin start and then fail confusingly later.
 * Since the display is only real once the CXR connection is up, the capability has to follow the connection rather
 * than the environment's existence.
 */
class CxrHostEnvironmentTest {

    private class FakeInput : InputPort {
        var started = 0
        override fun start(epoch: com.sheen.rokid.contracts.InputEpoch) { started++ }
        override fun stop(epoch: com.sheen.rokid.contracts.InputEpoch) = Unit
    }

    private class FakeHintDevice : HintDevice {
        var shown = 0
        override fun show(assistance: Assistance, revision: Long, listener: (com.sheen.rokid.contracts.OutputStatus) -> Unit): CancellationHandle {
            shown++
            return object : CancellationHandle { override fun cancel() = Unit }
        }
        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }

    private val clock = object : MonotonicClock { override fun nowNanos() = 1L }
    private val scheduler = object : DeadlineScheduler { override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle = object : CancellationHandle { override fun cancel() = Unit } }
    private val input = FakeInput()

    @Test
    fun displayIsNotAdvertisedUntilTheOutputPathIsActuallyLive() {
        // Claiming TextOutput while nothing can display would let a hint plugin start and fail later, which is worse
        // than refusing it up front.
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { null }, scheduler = scheduler)

        assertFalse(Capability.TextOutput in env.availableCapabilities)
    }

    @Test
    fun displayIsAdvertisedOnceTheOutputPathIsLive() {
        val device = FakeHintDevice()
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { device }, scheduler = scheduler)

        assertTrue(Capability.TextOutput in env.availableCapabilities)
    }

    @Test
    fun theAdvertisedSetTracksTheOutputPathRatherThanBeingFrozenAtConstruction() {
        // The CXR connection arrives after this environment is built, so a snapshot taken in the constructor would
        // permanently under-report and the host would keep refusing a display that is in fact ready.
        var device: HintDevice? = null
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { device }, scheduler = scheduler)
        assertFalse(Capability.TextOutput in env.availableCapabilities)

        device = FakeHintDevice()

        assertTrue(Capability.TextOutput in env.availableCapabilities)
    }

    @Test
    fun aProvidedContextProviderIsAdvertisedAndAnswerable() {
        // P3.2 Task 6: the first real `Context` provider. The rule above still holds in both directions - with no
        // provider the capability stays unwired, which is why the negative list below still names what this rig does
        // not supply - and with one it must be advertised *and* answerable, or a plugin would activate and then fail
        // at `require`.
        val signals = object : ContextSignals {
            override fun snapshot() = ContextSnapshot(1L, mapOf("time.local" to "09:00"))
            override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle =
                object : CancellationHandle { override fun cancel() = Unit }
        }
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { FakeHintDevice() },
            scheduler = scheduler, providers = mapOf(Capability.Context to signals))

        assertTrue(Capability.Context in env.availableCapabilities)
        val services = env.services(ResourceScope("p-1", SessionId("s-1"), Generation(1)),
            setOf(Capability.Context))
        // The host hands a scope-bound wrapper over the provider (so a revoked scope stops answering), so the
        // assertion is on what it returns rather than on identity.
        val answered = services.require(Capability.Context) as ContextSignals
        assertEquals("an advertised capability must be answerable", signals.snapshot(), answered.snapshot())
    }

    @Test
    fun aProvidedPerceptionProviderIsAdvertisedAndAnswerable() {
        // edith-0.3 Phase 2 Task 7: the composition registers the Android perception provider. The environment's rule
        // is unchanged - a provider that is present is advertised, and the same scope can answer it.
        val perception = FakePerception()
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { FakeHintDevice() },
            scheduler = scheduler, providers = mapOf(Capability.Perception to perception))

        assertTrue(Capability.Perception in env.availableCapabilities)
        val services = env.services(scope(), setOf(Capability.Perception))
        assertEquals(perception, services.require(Capability.Perception))
    }

    @Test
    fun capabilitiesThisEnvironmentCannotHonourAreNotAdvertised() {
        // Audio, ASR, speaker, model, storage, speech and perception all need services this environment does not
        // provide. If any were advertised, a plugin asking for them would pass the host's check and then fail at
        // require().
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { FakeHintDevice() },
            scheduler = scheduler)

        listOf(Capability.Audio, Capability.Asr, Capability.Speaker, Capability.Model, Capability.Storage,
            Capability.SpeechOutput, Capability.Perception).forEach { capability ->
            assertFalse("$capability is not wired and must not be advertised", capability in env.availableCapabilities)
        }
    }

    @Test
    fun anUnwiredOutputIsRefusedRatherThanSilentlyDroppingAHint() {
        // Before the connection is up there is nowhere to show a hint. Throwing keeps the existing contract (the
        // missing environment behaves the same way); a silent no-op would look like success and lose the hint.
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { null }, scheduler = scheduler)
        val thrown = runCatching { env.output.show(hint(), revision = 1) { } }.exceptionOrNull()

        assertTrue("an unwired display must be refused, was $thrown", thrown != null)
    }

    @Test
    fun onceLiveTheOutputDelegatesToTheRealDevice() {
        val device = FakeHintDevice()
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { device }, scheduler = scheduler)

        env.output.show(hint(), revision = 1) { }

        assertEquals(1, device.shown)
    }

    @Test
    fun inputIsTheLiveTransportPortSoAudioUsesTheOneWorkingPath() {
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { null }, scheduler = scheduler)

        assertEquals(input, env.input)
    }

    @Test
    fun servicesRefuseCapabilitiesThisEnvironmentDoesNotProvide() {
        // Losing the capability set's honesty would change plugin behaviour, so it is pinned here. The display is the
        // only thing wired so far, so plugin-facing services must still refuse rather than pretend.
        val env = CxrHostEnvironment(clock = clock, input = input, liveOutput = { null }, scheduler = scheduler)
        val services: HostServices = env.services(scope(), emptySet())

        val thrown = runCatching { services.require(Capability.Model) }.exceptionOrNull()
        assertTrue("an unwired capability must not be resolvable, was $thrown", thrown != null)
    }

    private class FakePerception : PerceptionProvider {
        private fun snapshot(): PerceptionSnapshot = PerceptionSnapshot(
            snapshotId = "snap",
            capturedAtEpochMillis = 0L,
            coverage = PerceptionCoverage(
                screen = "ok",
                notifications = "ok",
                media = "ok",
                calendar = "ok",
                acoustic = "ok",
                clipboard = "ok",
                vision = "ok",
                glasses = "ok",
                device = "ok",
            ),
        )

        override fun beginTurn(): PerceptionSnapshot = snapshot()

        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot = snapshot()

        override fun afterAction(): PerceptionSnapshot = snapshot()

        override fun screenshot(): PerceptionScreenshotResult = PerceptionScreenshotResult.Disabled

        override fun endTurn() = Unit
    }

    private fun scope() = ResourceScope("test-plugin", SessionId("s-1"), Generation(1))

    private fun hint() = Assistance(
        hintId = "h-1",
        sessionId = SessionId("s-1"),
        generation = Generation(1),
        sourceTurnId = null,
        priority = 1,
        text = "先讲项目背景",
        optionalSpeech = null,
        expiresAtNanos = 0L,
    )
}
