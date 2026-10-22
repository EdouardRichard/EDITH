package edith.probe.eval

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4 §M: the "why did this row reach no decision at all" classifier.
 *
 * These pin the **reading of our own log vocabulary**, one cause each, plus the honesty rule that matters most: with
 * P3.4's vocabulary a window that never says whether a final arrived must come back [NoDecisionCause.UNKNOWN] rather
 * than be guessed into one of the four causes. The diagnosis is what decides whether W4 changes code at all, so a
 * classifier that guesses would be worse than no classifier.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class TranscriptDiagnosisTest {

    @Test
    fun `a transcript that never became final is no final`() {
        val cause = classifyNoDecision(
            listOf("P3_ASR revision=3 final=false chars=4", "P3_INJECT state=end"),
        )

        assertEquals(NoDecisionCause.NO_FINAL, cause)
    }

    @Test
    fun `a noisy environment is its own cause`() {
        val cause = classifyNoDecision(
            listOf("P3_ASR revision=4 final=true chars=6", "P3_ACOUSTIC label=Noisy", "P3_INJECT state=end"),
        )

        assertEquals(NoDecisionCause.LABEL_NOT_SPEECH, cause)
    }

    @Test
    fun `a busy turn is a gate on this turn rather than a recogniser problem`() {
        val cause = classifyNoDecision(
            listOf("P3_ASR revision=4 final=true chars=9 usable=true", "P3_PROACTIVE skip reason=BUSY"),
        )

        assertEquals(NoDecisionCause.GATED, cause)
    }

    @Test
    fun `a final the decider called unusable is drift`() {
        val cause = classifyNoDecision(
            listOf("P3_ASR revision=4 final=true chars=2 usable=false"),
        )

        assertEquals(NoDecisionCause.ASR_DRIFT, cause)
    }

    @Test
    fun `a usable final with no decision line is drift`() {
        val cause = classifyNoDecision(
            listOf("P3_ASR revision=4 final=true chars=9 usable=true", "P3_INJECT state=end"),
        )

        assertEquals(NoDecisionCause.ASR_DRIFT, cause)
    }

    @Test
    fun `a normal end-of-speech silence is not a cause`() {
        // The pipeline emits `P3_ACOUSTIC label=Silence` at the end of EVERY utterance. Treating a trailing Silence as
        // "the environment was not speech" classified every real no-decision row as LABEL_NOT_SPEECH on the device - it
        // was reading the labeler's ordinary end-of-speech transition as a cause. Only `Noisy` is a cause.
        val cause = classifyNoDecision(
            listOf(
                "P3_ASR revision=6 final=true chars=15 usable=true",
                "P3_ACOUSTIC label=Speech",
                "P3_ACOUSTIC label=Silence",
            ),
        )

        assertTrue(
            cause != NoDecisionCause.LABEL_NOT_SPEECH,
            "an ordinary end-of-speech silence must not be read as a cause: $cause",
        )
    }

    @Test
    fun `without the asr line the cause is unknown rather than guessed`() {
        // This is the honest answer for the P3.4 evidence: no `P3_ASR` line existed, so "no final" and "no transcript
        // event reached the plugin at all" cannot be told apart from the log.
        val cause = classifyNoDecision(listOf("P3_INJECT state=end", "P3_ACOUSTIC label=Speech"))

        assertEquals(NoDecisionCause.UNKNOWN, cause)
    }

    @Test
    fun `the breakdown carries every cause including the zeroes`() {
        val breakdown = diagnosisBreakdown(listOf(NoDecisionCause.NO_FINAL, NoDecisionCause.NO_FINAL, NoDecisionCause.GATED))

        assertEquals(2, breakdown[NoDecisionCause.NO_FINAL])
        assertEquals(1, breakdown[NoDecisionCause.GATED])
        assertEquals(0, breakdown[NoDecisionCause.UNKNOWN], "a cause with no rows prints as zero, not as absent")
        assertEquals(NoDecisionCause.entries.size, breakdown.size, "every cause has an entry")
    }

    @Test
    fun `a window with no lines at all is unknown`() {
        assertEquals(NoDecisionCause.UNKNOWN, classifyNoDecision(emptyList()))
    }

    // --- the file format the reader actually meets (review C1) ---------------------------------
    //
    // VERBATIM lines out of a campaign evidence file, prefix included. This is the shape that was missing from these
    // tests: every fixture above is a bare log line, so nothing pinned what `readNoDecisionWindows` hands the
    // classifier. Three predicates used `contains` and kept working on prefixed lines; `sawAsr` used
    // `startsWith("P3_ASR")`, which no evidence line can ever satisfy (they all start with the row id), so `NO_FINAL`
    // was unreachable through `--diagnose` on every real artifact while this suite stayed green - the same
    // "assertion written from the author's format instead of the artifact's" defect as the trigger marker.
    private val criterionLine =
        "K-N-07\t10-12 02:29:56.420\t10-12 02:29:56.420 I/System.out(18428): EdithSmart P3_ASR revision=0 final=false chars=3 usable=true"

    private val summaryLine =
        "K-N-07\tharness_error\tno_decision_line_in_window\tnoDecisionLine=True\tcriteria=4\tlogLines=568"

    @Test
    fun `a prefixed window with no final is no final rather than unknown`() {
        val cause = classifyNoDecision(listOf(criterionLine, summaryLine))

        assertEquals(NoDecisionCause.NO_FINAL, cause)
    }

    @Test
    fun `a criterion line is reduced to the product line the classifier matches on`() {
        assertEquals(
            "10-12 02:29:56.420 I/System.out(18428): EdithSmart P3_ASR revision=0 final=false chars=3 usable=true",
            productLogLine(criterionLine),
        )
    }

    @Test
    fun `a verdict summary line is kept verbatim because it is the evidence the row was played`() {
        assertEquals(summaryLine, productLogLine(summaryLine))
    }

    @Test
    fun `the reader groups a real evidence file by row and finds its no-decision rows`() {
        val file = File.createTempFile("campaign-evidence", ".txt")
        try {
            file.writeText(
                listOf(
                    "# campaign start 2026-10-12T01:56:16+08:00 corpus=docs/x.md rows=210",
                    "# evidence carries ids, device timestamps, criterion lines and counts only",
                    criterionLine,
                    summaryLine,
                    "K-P-01\t10-12 01:57:18.239\t10-12 01:57:18.239 I/EdithP3 (18428): P3_ACOUSTIC label=Speech",
                    "K-P-01\ttriggered\tP3_PROACTIVE request\tnoDecisionLine=False\tcriteria=9\tlogLines=212",
                ).joinToString("\n"),
                Charsets.UTF_8,
            )

            val windows = readNoDecisionWindows(file)

            assertEquals(2, windows.size, "the comment header is not a row")
            assertEquals(listOf("K-N-07"), noDecisionRows(windows).map { it.id })
            assertTrue(
                windows.single { it.id == "K-N-07" }.lines.any { it.startsWith("10-12 02:29:56.420 I/System.out") },
                "the classifier must see the product line, not the id/timestamp prefix",
            )
        } finally {
            file.delete()
        }
    }
}
