package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.ToolVision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * edith-0.3 Phase 2.2 Task 6: the production plugin must not leave `AgentRequest.observationSource` as dead code.
 *
 * The first half pins the loop-level source's shape (text/foreground/coverage plus an honest vision reason, never an
 * image-carrying claim). The second half runs the real plugin loop with a failing screen tool and asserts that the
 * observation it attaches came from the session's [com.sheen.rokid.plugin.PerceptionProvider] - i.e. that the built
 * `AgentRequest` really carried the source.
 */
class PerceptionObservationSourceTest {

    @Test
    fun `the loop observation source renders the snapshot and never claims a picture`() {
        val source = requireNotNull(perceptionToolObservationSource(SmartFakePerception())) {
            "a session with a perception port must have an observation source"
        }

        val observation = requireNotNull(source.observe(250L)) { "the source must answer an observation" }

        assertEquals("snap-0042", observation.snapshotId)
        assertEquals(1_000_000L, observation.capturedAtEpochMillis)
        assertEquals("com.miui.player", observation.foregroundPackage)
        assertEquals(".PlayerActivity", observation.foregroundActivity)
        assertEquals("#n12 button \"搜索\" center=(540,320) [clickable]", observation.screen)
        val coverage = requireNotNull(observation.coverage)
        assertTrue(
            coverage.startsWith("screen=ok"),
            "the coverage token line must be the host's: $coverage",
        )
        // This channel carries no ImageRef, so it can never claim a picture.
        assertEquals(ToolVision.NotRequested, observation.vision)
        assertNull(observation.visionReason)
    }

    @Test
    fun `a blind snapshot keeps the unavailable reason and fabricates no screen text`() {
        val blind = smartPerceptionSnapshot(
            elements = emptyList(),
            coverage = smartPerceptionCoverage(screen = "unavailable(no_accessibility)", vision = "disabled"),
        )
        val source = requireNotNull(
            perceptionToolObservationSource(SmartFakePerception(initial = blind, ambient = blind)),
        )

        val observation = requireNotNull(source.observe(250L))

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
    fun `a provider that throws yields no observation so the loop records its timeout reason`() {
        val source = requireNotNull(
            perceptionToolObservationSource(SmartFakePerception(ambientFailure = IllegalStateException("boom"))),
        )

        assertNull(source.observe(250L))
    }

    @Test
    fun `a session without a perception port reports no source instead of inventing one`() {
        assertNull(perceptionToolObservationSource(null))
    }

    @Test
    fun `the loop observation source asks only for the screen and takes the deadline as advisory`() {
        val perception = SmartFakePerception()
        val source = requireNotNull(perceptionToolObservationSource(perception))

        // The loop's failure budget is 250 ms. This source is cache-only and cannot preempt the platform read, so the
        // deadline is advisory here (Task 9 measures it; no compliance is claimed) - but the call must still receive
        // the deadline and answer, and it must not widen the read beyond the compact screen.
        assertNotNull(source.observe(250L))
        // An already-expired budget still answers: non-enforcement is the documented residual, not an accident.
        assertNotNull(source.observe(0L))

        assertEquals(
            listOf(setOf(PerceptionSource.SCREEN), setOf(PerceptionSource.SCREEN)),
            perception.requestedSources,
            "the loop fallback must never ask for the notification/calendar/... bulk",
        )
    }

    @Test
    fun `a failed screen tool in the production loop gets the perception observation`() {
        val perception = SmartFakePerception()
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
        val output = rig.model.requests[1].history
            .filterIsInstance<ModelHistoryItem.ToolOutput>()
            .single()
            .output
        // The failed tool supplied no observation of its own, so this text can only come from the request's
        // observationSource; a null source would have produced `unavailable/no_source` instead.
        assertTrue("com.miui.player" in output, "the observation must carry the perceived foreground: $output")
        assertTrue("#n12" in output, "the observation must carry the compact screen: $output")
        assertTrue("screen=ok" in output, "the observation must carry the coverage line: $output")
        assertTrue(
            "\"vision\":\"not_requested\"" in output,
            "the loop-level channel never claims a picture: $output",
        )
        assertFalse("no_source" in output, "a supplied observation must not be replaced with no_source: $output")
        assertEquals(1, rig.output.hints.size, "the turn still answers")
    }
}
