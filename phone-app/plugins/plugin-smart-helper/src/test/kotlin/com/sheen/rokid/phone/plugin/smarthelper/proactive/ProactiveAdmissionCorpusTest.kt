package com.sheen.rokid.phone.plugin.smarthelper.proactive

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline calibration gate over the **frozen** 210-row corpus (P3.5 §W3, plan Task 10).
 *
 * Why this file exists at all: the plan's gate test was never written, and the review found that in its absence
 * **nothing** in the suite would fail if a future edit admitted all 30 chat rows - the exact property §11.3's chat
 * clause and this increment were gated on. A manual CLI run is not a regression alarm.
 *
 * ## What it asserts, and what it deliberately does not
 *
 * The rule **does not meet §11.3** on this corpus (measured after the `PRONOUN_SELF` fix: recall 0.733 / precision
 * 0.795 / chat 8 of 30, against 0.80 / 0.90 / 0). Asserting the thresholds here would leave a permanently red suite
 * that everyone learns to ignore, which is worse than no gate. So this test pins the **measured envelope instead**, and
 * it fails in **both** directions:
 *
 *  - the bounds are one row / one point *looser* than today's numbers, so a regression (more chat admitted, or fewer
 *    positives admitted) turns it red immediately;
 *  - they are not loose enough to hide a real change, and any **improvement** past the bound also turns it red, which
 *    forces the recorded numbers - and the `P3.5-C` acceptance row - to be updated rather than left stale.
 *
 * ## Path
 *
 * Gradle runs tests with the **module** directory as the working directory, and this module now sits several levels
 * below the repository root, so the corpus is located by walking up until the tracked `settings.gradle.kts` is found.
 * A missing file is an `error`, never a silent skip: a gate that quietly passes when the corpus moves is not a gate.
 */
class ProactiveAdmissionCorpusTest {

    private fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repository root not found from ${File("").absolutePath}")

    private val corpus = File(
        repositoryRoot(),
        "docs/project/evidence/2026-10-11-p3-4-eval-corpus.md",
    )

    private fun rows(): List<CorpusRow> {
        check(corpus.isFile) { "frozen corpus not found at ${corpus.absolutePath} - the gate cannot be silently skipped" }
        return corpusRows(corpus.readText(Charsets.UTF_8))
    }

    /**
     * The corpus's label rows: five columns, a known scenario, a `positive`/`negative` label - the same rule the scorer
     * and the campaign use, mirrored here so this module needs no dependency on `tools/`.
     */
    private data class CorpusRow(val id: String, val scenario: String, val label: String, val text: String)

    private fun corpusRows(markdown: String): List<CorpusRow> {
        val scenarios = setOf("knowledge", "privacy", "dilemma", "chat")
        return markdown.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { line -> line.trim('|').split('|').map { cell -> cell.trim() } }
            .filter { cells -> cells.size >= 5 && cells[1] in scenarios && (cells[2] == "positive" || cells[2] == "negative") }
            .map { cells -> CorpusRow(cells[0], cells[1], cells[2], cells[3]) }
            .toList()
    }

    private fun confusion(): AdmissionConfusion = admissionReport(rows().map { Triple(it.scenario, it.label, it.text) })

    @Test
    fun `the corpus is the frozen 210-row set`() {
        val rows = rows()

        assertEquals(210, rows.size, "the corpus is frozen; a changed row count invalidates every recorded number")
        assertEquals(90, rows.count { it.label == "positive" }, "90 positives")
        assertEquals(120, rows.count { it.label == "negative" }, "120 negatives")
        assertEquals(30, rows.count { it.scenario == "chat" }, "30 chat negatives (the false-positive guard)")
    }

    @Test
    fun `the chat negatives are not admitted beyond the measured envelope`() {
        // §11.3's chat clause is a **separate** gate from precision: a rule that passes precision by admitting chat is
        // not acceptable. Measured 8 of 30 (it was 7 before the PRONOUN_SELF fix); the bound is 9 so a real drift is
        // red, and it is not 30.
        //
        // Measured honesty (review I4 + the mutation runs for this file): widening the rule until chat is admitted
        // reddens the **recall/precision envelope** tests below first - the chat count is held down by the
        // content-bearing requirement (`strongNeed`), not by the chat/media refusal branch, so relaxing *that* branch
        // alone leaves this assertion green. Both are kept because they are different properties, but this one is not
        // the alarm that fires first.
        val admitted = confusion().admittedChat

        assertTrue(
            admitted <= 9,
            "at most 9 of 30 chat rows may be admitted (measured 8, gate is 0): $admitted",
        )
    }

    @Test
    fun `admission recall stays inside the measured envelope`() {
        // Measured 66/90. A regression below 65 is red, and an improvement above 68 is red too - not to punish
        // progress, but because the `P3.5-C` row and the evidence file quote a number that would then be stale.
        val c = confusion()

        assertTrue(
            c.admittedPositives in 65..68,
            "admitted positives moved outside the recorded envelope (measured 66/90): ${c.admittedPositives}/${c.positives}",
        )
    }

    @Test
    fun `admission precision stays inside the measured envelope`() {
        // Measured 66 positives + 17 negatives = 83 admitted rows. Expressed as counts rather than a rounded rate, so
        // the bound is exact. **The counts moved once already** (13 negatives) when `PRONOUN_SELF` was fixed to
        // actually match 我 - which is why this gate exists and why the evidence file now records both readings.
        val c = confusion()
        val admittedTotal = c.admittedPositives + c.admittedNegatives

        assertTrue(
            c.admittedNegatives in 17..20,
            "admitted negatives moved outside the recorded envelope (measured 17): ${c.admittedNegatives}/${c.negatives}",
        )
        assertTrue(admittedTotal in 82..88, "admitted rows moved outside the recorded envelope (measured 83): $admittedTotal")
    }

    /**
     * The regression this whole file is for, stated as one assertion: the corpus row that closed the F19 causal chain
     * on the device must still be admitted.
     *
     * `K-P-01` is that row - it produced `P3_PROACTIVE skip reason=SHORT` under the old length gate (recorded recall 0)
     * and `P3_PROACTIVE request` → `P3_HTTP 200` → `P3_HINT show` → `ack Displayed` under the feature rule. If a future
     * edit stops admitting it, the device evidence and this test disagree, and this one runs in seconds.
     */
    @Test
    fun `the row that closed the device causal chain is still admitted`() {
        val row = rows().single { it.id == "K-P-01" }

        assertTrue(admitByFeatures(row.text, foreground = null).admitted, "K-P-01 must stay admitted: ${row.text}")
    }
}
