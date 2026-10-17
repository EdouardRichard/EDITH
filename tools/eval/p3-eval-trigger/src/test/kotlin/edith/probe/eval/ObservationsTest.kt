package edith.probe.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3.4 §6.4: the harness's observation file, and the one rule that keeps a score honest.
 *
 * A `harness_error` row means the campaign could not tell whether the row triggered - the injection never finished, the
 * log came back empty. Scoring it as "no trigger" would fold an instrument failure into the model's silence and quietly
 * lower recall, so it must stay **unresolved** and block the verdict instead.
 */
class ObservationsTest {

    private fun observations(vararg lines: String): Observations =
        parseObservations(lines.toList())

    @Test fun `triggered and none are the only scored verdicts`() {
        val parsed = observations("K-P-01=triggered", "K-N-01=none")

        assertEquals(2, parsed.observed.size)
        assertTrue(parsed.observed.first { it.id == "K-P-01" }.triggered)
        assertTrue(parsed.harnessErrors.isEmpty())
    }

    @Test fun `a harness error is not a model silence`() {
        val parsed = observations("K-P-01=harness_error")

        assertTrue(parsed.observed.isEmpty(), "仪器失败不得被当成'没触发'")
        assertEquals(listOf("K-P-01"), parsed.harnessErrors)
    }

    @Test fun `an unknown verdict is unresolved rather than a trigger or a silence`() {
        val parsed = observations("K-P-01=maybe")

        assertTrue(parsed.observed.isEmpty())
        assertEquals(listOf("K-P-01"), parsed.harnessErrors)
    }

    @Test fun `blank lines and comments are ignored`() {
        val parsed = observations("", "   ", "# a note", "K-P-01=triggered")

        assertEquals(1, parsed.observed.size)
        assertTrue(parsed.harnessErrors.isEmpty())
    }

    @Test fun `a later line for the same id wins so a re-run can settle a row`() {
        val parsed = observations("K-P-01=harness_error", "K-P-01=none")

        assertEquals(listOf("K-P-01"), parsed.observed.map { it.id })
        assertTrue(parsed.harnessErrors.isEmpty(), "重跑settle之后不得再算未决")
    }
}
