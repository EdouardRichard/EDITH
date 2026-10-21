package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.ToolVision
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * edith-0.3 Phase 3 Task 3 (M4): the plugin's bounded loop-level observation source.
 *
 * The loop's failure budget is 250 ms. The source runs `refresh()` then `screenshot()` on one daemon worker and waits
 * with `Future.get`, so the caller returns by the deadline even when the platform read blocks; the overrunning read
 * itself is only cancelled cooperatively, and it can still delay this turn's later provider calls (see the source's
 * KDoc for that turn-level residual). A timeout is an explicit `unavailable/observation_timeout`, a provider throw is
 * `unavailable/provider_threw`, and at most one image is attached to a failure result. The first half pins that source;
 * the second half runs the real plugin loop and proves the request really carried it, and that the session closes it on
 * deactivation.
 */
class PerceptionObservationSourceTest {

    private val image = ImageRef("artifact-shot-1", "image/jpeg", 768, 1664, "sha-256-shot-1")

    /**
     * A perception port that distinguishes the three reads the source may make, so a test can prove the source
     * refreshed the device (never the turn cache) and asked for a screenshot.
     */
    private class ObservationPerception(
        private val snapshot: PerceptionSnapshot = smartPerceptionSnapshot(),
        private val screenshot: PerceptionScreenshotResult = PerceptionScreenshotResult.Unavailable("test"),
        private val refreshFailure: RuntimeException? = null,
        private val refreshDelayMillis: Long = 0L,
    ) : PerceptionProvider {
        val refreshes = AtomicInteger()
        val screenshots = AtomicInteger()

        override fun beginTurn(): PerceptionSnapshot =
            error("the loop source must refresh the device, not open a turn boundary")

        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot = error("the loop source must refresh the device, not read the turn cache")

        override fun refresh(): PerceptionSnapshot {
            refreshes.incrementAndGet()
            if (refreshDelayMillis > 0L) Thread.sleep(refreshDelayMillis)
            refreshFailure?.let { throw it }
            return snapshot
        }

        override fun afterAction(): PerceptionSnapshot = snapshot

        override fun screenshot(): PerceptionScreenshotResult {
            screenshots.incrementAndGet()
            return screenshot
        }

        override fun endTurn() = Unit
    }

    @Test
    fun `the loop source refreshes the device and carries at most one captured image`() {
        val perception = ObservationPerception(
            screenshot = PerceptionScreenshotResult.Image(image, "image/jpeg", 768, 1664),
        )
        val lines = mutableListOf<String>()
        val source = requireNotNull(perceptionToolObservationSource(perception, lines::add)) {
            "a session with a perception port must have an observation source"
        }

        val capture = source.observeWithImages(250L)
        val observation = requireNotNull(capture.observation) { "the source must answer an observation" }

        assertEquals("snap-0042", observation.snapshotId)
        assertEquals(1_000_000L, observation.capturedAtEpochMillis)
        assertEquals("com.miui.player", observation.foregroundPackage)
        assertEquals(".PlayerActivity", observation.foregroundActivity)
        assertEquals("#n12 button \"搜索\" center=(540,320) [clickable]", observation.screen)
        val coverage = requireNotNull(observation.coverage)
        assertTrue(coverage.startsWith("screen=ok"), "the coverage token line must be the host's: $coverage")
        assertEquals(ToolVision.Ok, observation.vision)
        assertNull(observation.visionReason)
        assertEquals(listOf(image), capture.images, "exactly the screenshot the host returned")
        assertEquals(1, perception.refreshes.get(), "the source must take a real read")
        assertEquals(1, perception.screenshots.get(), "the picture is requested once")
        assertEquals(
            1,
            lines.count { it.startsWith("P3_OBSERVE") },
            "one attempt emits one count-only observation line: $lines",
        )
        assertTrue(
            Regex("^P3_OBSERVE source=loop ms=\\d+ timeout=false images=1 reason=-$").matches(lines.single()),
            lines.single(),
        )
    }

    @Test
    fun `a screenshot the host disabled keeps the refreshed observation and claims no picture`() {
        val source = requireNotNull(
            perceptionToolObservationSource(
                ObservationPerception(screenshot = PerceptionScreenshotResult.Disabled),
            ),
        )

        val capture = source.observeWithImages(250L)
        val observation = requireNotNull(capture.observation)

        assertEquals("com.miui.player", observation.foregroundPackage)
        assertNotNull(observation.screen, "the refreshed screen is still a real observation")
        assertEquals(ToolVision.Disabled, observation.vision)
        assertNull(observation.visionReason)
        assertTrue(capture.images.isEmpty(), "a disabled screenshot can never invent an image")
    }

    @Test
    fun `a screenshot the host could not take reports its reason and no image`() {
        val source = requireNotNull(
            perceptionToolObservationSource(
                ObservationPerception(screenshot = PerceptionScreenshotResult.Unavailable("no_accessibility")),
            ),
        )

        val capture = source.observeWithImages(250L)
        val observation = requireNotNull(capture.observation)

        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("no_accessibility", observation.visionReason)
        assertTrue(capture.images.isEmpty())
    }

    @Test
    fun `a blind snapshot keeps the unavailable reason and fabricates no screen text`() {
        val blind = smartPerceptionSnapshot(
            elements = emptyList(),
            coverage = smartPerceptionCoverage(screen = "unavailable(no_accessibility)", vision = "disabled"),
        )
        val source = requireNotNull(
            perceptionToolObservationSource(
                ObservationPerception(
                    snapshot = blind,
                    screenshot = PerceptionScreenshotResult.Unavailable("no_accessibility"),
                ),
            ),
        )

        val capture = source.observeWithImages(250L)
        val observation = requireNotNull(capture.observation)

        assertNull(observation.screen, "a blind phone has no screen text to report")
        val blindCoverage = requireNotNull(observation.coverage)
        assertTrue(
            blindCoverage.contains("screen=unavailable(no_accessibility)"),
            "the coverage must say why: $blindCoverage",
        )
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("no_accessibility", observation.visionReason)
        assertEquals("com.miui.player", observation.foregroundPackage)
    }

    @Test
    fun `a provider that throws reports provider_threw instead of a silent null`() {
        val perception = ObservationPerception(refreshFailure = IllegalStateException("boom"))
        val lines = mutableListOf<String>()
        val source = requireNotNull(perceptionToolObservationSource(perception, lines::add))

        val observation = requireNotNull(source.observe(250L)) {
            "a throwing provider must still be reported, never a silent null"
        }

        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("provider_threw", observation.visionReason)
        assertTrue(
            Regex("^P3_OBSERVE source=loop ms=\\d+ timeout=false images=0 reason=provider_threw$")
                .matches(lines.single()),
            lines.single(),
        )
    }

    @Test
    fun `a provider that blocks is bounded by the deadline and reports observation timeout`() {
        val perception = ObservationPerception(
            screenshot = PerceptionScreenshotResult.Image(image, "image/jpeg", 768, 1664),
            refreshDelayMillis = 5_000L,
        )
        val lines = mutableListOf<String>()
        val source = requireNotNull(perceptionToolObservationSource(perception, lines::add))

        val startedNanos = System.nanoTime()
        val capture = source.observeWithImages(250L)
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

        assertTrue(elapsedMillis >= 200L, "the task must get its whole budget: ${elapsedMillis} ms")
        assertTrue(elapsedMillis < 2_000L, "a blocking provider must not outlive the 250 ms budget: ${elapsedMillis} ms")
        val observation = requireNotNull(capture.observation)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("observation_timeout", observation.visionReason)
        assertTrue(capture.images.isEmpty(), "a timed-out capture must not carry half a picture")
        assertEquals(1, perception.refreshes.get(), "the attempt really ran on the worker")
        assertTrue(
            Regex("^P3_OBSERVE source=loop ms=\\d+ timeout=true images=0 reason=observation_timeout$")
                .matches(lines.single()),
            lines.single(),
        )
    }

    @Test
    fun `a closed source logs its own reason instead of a false timeout`() {
        val lines = mutableListOf<String>()
        val source = requireNotNull(perceptionToolObservationSource(ObservationPerception(), lines::add))

        source.close()
        val capture = source.observeWithImages(250L)

        assertEquals(ToolVision.Unavailable, capture.observation?.vision)
        assertEquals(
            "observation_timeout",
            capture.observation?.visionReason,
            "the model-facing wire reason is unchanged; only the attempt's log line tells close from expiry",
        )
        assertTrue(
            Regex("^P3_OBSERVE source=loop ms=\\d+ timeout=false images=0 reason=closed$")
                .matches(lines.single()),
            lines.single(),
        )
    }

    @Test
    fun `an interrupted wait logs interrupted instead of a false timeout`() {
        val lines = mutableListOf<String>()
        // The refresh blocks long enough that the caller's interrupt must be what ends the wait, never a completed task.
        val source = requireNotNull(
            perceptionToolObservationSource(ObservationPerception(refreshDelayMillis = 5_000L), lines::add),
        )
        try {
            Thread.currentThread().interrupt()
            val capture = source.observeWithImages(5_000L)

            assertTrue(Thread.currentThread().isInterrupted, "the caller's interrupt must be restored")
            assertEquals("observation_timeout", capture.observation?.visionReason)
            assertTrue(
                Regex("^P3_OBSERVE source=loop ms=\\d+ timeout=false images=0 reason=interrupted$")
                    .matches(lines.single()),
                lines.single(),
            )
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `close is idempotent and starts no further observation task`() {
        val perception = ObservationPerception(
            screenshot = PerceptionScreenshotResult.Image(image, "image/jpeg", 768, 1664),
        )
        val source = requireNotNull(perceptionToolObservationSource(perception))
        assertNotNull(source.observe(250L))
        assertEquals(1, perception.refreshes.get())

        source.close()
        source.close()

        val afterClose = source.observeWithImages(250L)
        assertEquals(ToolVision.Unavailable, afterClose.observation?.vision)
        assertEquals("observation_timeout", afterClose.observation?.visionReason)
        assertTrue(afterClose.images.isEmpty())
        assertEquals(1, perception.refreshes.get(), "a closed source must not start another task")
    }

    @Test
    fun `the loop source refreshes the compact screen instead of reading the turn cache`() {
        val perception = SmartFakePerception()
        val source = requireNotNull(perceptionToolObservationSource(perception))

        assertNotNull(source.observeWithImages(250L).observation)

        assertEquals(
            listOf(setOf(PerceptionSource.SCREEN)),
            perception.requestedSources,
            "the loop fallback must never ask for the notification/calendar/... bulk",
        )
    }

    @Test
    fun `a session without a perception port reports no source instead of inventing one`() {
        assertNull(perceptionToolObservationSource(null))
    }

    @Test
    fun `the plugin closes its session observation source on deactivate`() {
        val perception = SmartFakePerception()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Perception to perception))
        val source = requireNotNull(rig.plugin.observationSourceForTest()) {
            "a session with a perception port must hold one observation source"
        }
        assertNotNull(source.observe(250L))
        val readsBefore = perception.requestedSources.size

        rig.plugin.deactivate(DeactivationReason.Stopped)

        assertNull(rig.plugin.observationSourceForTest(), "the session's source is dropped with the session")
        source.observe(250L)
        assertEquals(
            readsBefore,
            perception.requestedSources.size,
            "a source closed by deactivate must not start another task",
        )
    }

    @Test
    fun `a failed screen tool in the production loop gets the perception observation and its image`() {
        val perception = SmartFakePerception(
            screenshotResult = PerceptionScreenshotResult.Image(image, "image/jpeg", 768, 1664),
        )
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Perception to perception))
        rig.actions.descriptors += smartAction("shell", screenObservation = true)
        rig.actions.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "SHELL_FAILED") }
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            when (request++) {
                0 -> {
                    listener(
                        smartToolCall(
                            "c1",
                            "shell",
                            """{"command":"id"}""",
                            requestId = modelRequest.requestId,
                        ),
                    )
                    listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
                }
                else -> streamText(modelRequest, listener, "看到了")
            }
        }

        rig.wakeAndFinal("试着执行一下")

        assertEquals(2, rig.model.requests.size, "the tool round must ask the model a second time")
        val sent = rig.model.requests[1].history
            .filterIsInstance<ModelHistoryItem.ToolOutput>()
            .single()
        val output = sent.output
        // The failed tool supplied no observation of its own, so this can only come from the request's source; a null
        // source would have produced `unavailable/no_source` instead.
        assertTrue("com.miui.player" in output, "the observation must carry the perceived foreground: $output")
        assertTrue("#n12" in output, "the observation must carry the compact screen: $output")
        assertTrue("screen=ok" in output, "the observation must carry the coverage line: $output")
        assertTrue("\"vision\":\"ok\"" in output, "the picture channel is real: $output")
        assertEquals(listOf(image), sent.images, "the failure capture's image must ride the next model request")
        assertFalse("no_source" in output, "a supplied observation must not be replaced with no_source: $output")
        assertEquals(1, rig.output.hints.size, "the turn still answers")
    }
}
