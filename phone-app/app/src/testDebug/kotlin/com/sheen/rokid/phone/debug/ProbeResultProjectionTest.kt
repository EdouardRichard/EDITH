package com.sheen.rokid.phone.debug

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The debug probes read a production [ActionResult], not a rendered envelope.
 *
 * Task 8 changed the probe source from a flat detail string to the structured result (`execution`/`observation`/`data`);
 * the probes were left parsing `detail` as if it still held the envelope, so they silently logged no screen. This test
 * pins the projection: `screen` comes from the observation, execution facts from `execution`, and `candidates` from the
 * tool-specific `data` (which is what `detail` now carries).
 */
class ProbeResultProjectionTest {

    private fun result(
        detail: String,
        observation: ToolObservation? = null,
        execution: ToolExecution = ToolExecution(outcome = ToolOutcome.Done),
        outcome: ActionOutcome = ActionOutcome.Done,
    ) = ActionResult(outcome, detail, execution = execution, observation = observation)

    @Test
    fun `screen is read from the structured observation`() {
        val facts = ProbeResultProjection.of(
            result(
                detail = """{"observation":{"screen":"#n1 envelope copy"}}""",
                observation = ToolObservation(screen = "#n9 button OK"),
            ),
        )

        assertEquals("#n9 button OK", facts.screen)
    }

    @Test
    fun `the old envelope-in-detail reading yields no screen`() {
        // A detail that still happens to look like an envelope is tool data; it must not be mistaken for the result.
        val facts = ProbeResultProjection.of(
            result(
                detail = """{"execution":{"code":"X"},"observation":{"screen":"#n1 stale"},"data":{}}""",
            ),
        )

        assertNull(facts.screen)
    }

    @Test
    fun `execution facts are read from the structured execution`() {
        val facts = ProbeResultProjection.of(
            result(
                detail = """{"code":"NOT-THE-CODE","applied":true}""",
                outcome = ActionOutcome.Refused,
                execution = ToolExecution(
                    outcome = ToolOutcome.Refused,
                    code = "APP_AMBIGUOUS",
                    applied = false,
                ),
            ),
        )

        assertEquals("APP_AMBIGUOUS", facts.executionCode)
        assertEquals(false, facts.applied)
    }

    @Test
    fun `the candidate count is read from the tool data`() {
        val facts = ProbeResultProjection.of(
            result(
                detail = """{"candidates":[{"label":"文件","packageName":"com.android.fileexplorer"},""" +
                    """{"label":"文件管理","packageName":"com.example.files"}]}""",
                outcome = ActionOutcome.Refused,
                execution = ToolExecution(outcome = ToolOutcome.Refused, code = "APP_AMBIGUOUS", applied = false),
            ),
        )

        assertEquals(2, facts.candidateCount)
    }

    @Test
    fun `a missing result projects to empty facts`() {
        val facts = ProbeResultProjection.of(null)

        assertNull(facts.screen)
        assertNull(facts.executionCode)
        assertNull(facts.applied)
        assertEquals(0, facts.candidateCount)
    }
}
