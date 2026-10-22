package edith.probe.eval

import java.io.File

/**
 * W4 (P3.5 §M): why a row reached **no decision at all**.
 *
 * The measured facts this exists to explain: of 210 corpus rows in configuration A, **39** produced no `P3_PROACTIVE`
 * line of any kind. W2 proved those rows are an **instrument-unresolved** outcome and must not be scored as a model
 * "no" - but *why* they produced nothing is a different question, and §M is explicit that the answer must be diagnosed
 * **before** anything is changed ("先诊断根因再定改法"). This classifier is the diagnosis, not a fix.
 *
 * ## What it can and cannot conclude, and why the honest answer is mostly `UNKNOWN`
 *
 * It reads **only our own log vocabulary** - never a transcript - and with the vocabulary P3.4 shipped there is no line
 * that distinguishes "the recogniser never produced a final" from "a final arrived but was dropped". `P3_ASR` (added in
 * task 13) is what makes those two separable, so a run against P3.4 evidence is expected to land largely on [UNKNOWN].
 * That is a **finding**, not a failure of the classifier: it says the question was not answerable with the instrument
 * of the day, which is exactly why the instrument had to change.
 */
enum class NoDecisionCause {
    /** No `final=true` revision arrived inside the window: the recogniser never finished an utterance. */
    NO_FINAL,

    /** A final arrived but the acoustic layer called the environment noise, or the decider refused it as `NOISY`. */
    LABEL_NOT_SPEECH,

    /** A final arrived and was refused by a gate that is about *this* turn - `BUSY` or `DISMED`. */
    GATED,

    /** A final arrived, was usable, and still produced nothing: the text the recogniser returned was not the corpus's. */
    ASR_DRIFT,

    /** The window carries nothing that separates the four causes above. Expected without `P3_ASR`. */
    UNKNOWN,
}

/**
 * Classifies one row's window.
 *
 * Order matters and is deliberately "strongest fact first": a missing injection end or a silence label outranks the
 * absence of a final, because a window where the audio never played cannot be evidence about the recogniser.
 */
internal fun classifyNoDecision(windowLines: List<String>): NoDecisionCause {
    val lines = windowLines.filter { it.isNotBlank() }
    if (lines.isEmpty()) return NoDecisionCause.UNKNOWN

    val finals = lines.filter { it.contains("final=true") }
    // `contains`, never `startsWith`: the tag and pid sit in front of the marker in a real logcat line, and before
    // [productLogLine] the row id did too. A `startsWith` here could not fire on any artifact the campaign produces,
    // which quietly turned every "no final arrived" row into UNKNOWN (review C1).
    val sawAsr = lines.any { it.contains("P3_ASR") }
    val noisy = lines.any { it.contains("P3_ACOUSTIC label=Noisy") || it.contains("skip reason=NOISY") }
    val gated = lines.any { it.contains("skip reason=BUSY") || it.contains("skip reason=DISMED") }
    val unusable = lines.any { it.contains("usable=false") }

    if (finals.isEmpty() && !sawAsr) {
        // With no `P3_ASR` line at all we cannot tell "no final" from "no transcript event reached the plugin".
        return NoDecisionCause.UNKNOWN
    }
    if (finals.isEmpty()) return NoDecisionCause.NO_FINAL
    // **Only `Noisy` counts**, never a trailing `Silence`.
    //
    // The first version also treated a window whose last `P3_ACOUSTIC` line was `label=Silence` as not-speech, and on
    // the real artifact that classified **every** no-decision row as [LABEL_NOT_SPEECH] - because the pipeline emits
    // `Silence` at the end of *every* utterance as a matter of course. It was measuring the labeler's ordinary
    // end-of-speech transition and calling it a cause. That is the exact failure §M's "diagnose before you change
    // anything" rule exists to prevent: it would have sent someone off to re-tune the acoustic gate.
    if (noisy) return NoDecisionCause.LABEL_NOT_SPEECH
    if (gated) return NoDecisionCause.GATED
    if (unusable) return NoDecisionCause.ASR_DRIFT
    // A final that was usable and drew no decision line at all: the utterance never reached the admission rule.
    return NoDecisionCause.ASR_DRIFT
}

/** Counts per cause, with **every** cause present so a report can print a zero rather than omit a row. */
internal fun diagnosisBreakdown(causes: List<NoDecisionCause>): Map<NoDecisionCause, Int> =
    NoDecisionCause.entries.associateWith { cause -> causes.count { it == cause } }

/** One corpus row's window: its id, and the log lines that fell inside it. */
internal data class NoDecisionWindow(val id: String, val lines: List<String>)

/** The device's own `MM-dd HH:mm:ss.SSS` stamp, the second field of every criterion line the campaign writes. */
private val DeviceStamp = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$""")

/**
 * One evidence-file line, reduced to the product line the classifier matches on.
 *
 * The campaign writes two shapes, both prefixed by the row id:
 *
 *  - a **criterion** line is `<id> \t <device stamp> \t <raw logcat line>`, and the classifier's predicates are
 *    written against the PRODUCT's vocabulary (`P3_ASR revision=… final=…`, `P3_ACOUSTIC label=…`), so the two
 *    prefixes have to come off before it is classified;
 *  - a **verdict** line is `<id> \t <verdict> \t <reason> …`, and it is kept **verbatim**: its presence is the
 *    evidence that the row was played at all, and [noDecisionRows] matches on its reason.
 *
 * Skipping this step is what made `NO_FINAL` unreachable on every real artifact: `startsWith("P3_ASR")` cannot match a
 * line that begins with the row id, while the `contains`-based predicates kept working - so the defect was invisible to
 * a test suite whose fixtures were bare log lines.
 */
internal fun productLogLine(line: String): String {
    val fields = line.split('\t')
    if (fields.size >= 3 && DeviceStamp.matches(fields[1].trim())) return fields.drop(2).joinToString("\t")
    return line
}

/**
 * Reads the campaign's per-row evidence file (id, timestamp, line) into windows.
 *
 * The file is what `campaign.ps1` writes - one line per criterion line, prefixed by the row id - so a row with **no**
 * decision line appears only in its `verdict=` summary line. Both shapes are gathered, and the summary line is kept
 * because its presence is itself the evidence that the row was played.
 */
internal fun readNoDecisionWindows(path: File): List<NoDecisionWindow> {
    require(path.isFile) { "evidence file not found: ${path.absolutePath}" }
    val byId = linkedMapOf<String, MutableList<String>>()
    path.readLines(Charsets.UTF_8).forEach { line ->
        val id = line.substringBefore('\t').trim()
        if (id.isEmpty() || id.startsWith("#")) return@forEach
        byId.getOrPut(id) { mutableListOf() }.add(productLogLine(line))
    }
    return byId.map { (id, lines) -> NoDecisionWindow(id, lines) }
}

/**
 * The rows this diagnosis is about: the ones the campaign recorded as having **no decision line in their window**.
 *
 * Two shapes have to be recognised, and the difference is itself the W2 finding:
 *
 *  - **today** the campaign writes `verdict=harness_error` with `no_decision_line_in_window` (unresolved, re-runnable);
 *  - **P3.4's file** writes `verdict=none reason=no decision line in window` - it recorded an instrument outcome as
 *    the model saying "no". That is the F19 defect, preserved verbatim in the artifact this diagnosis exists to read.
 *
 * Reading only the new spelling made this diagnosis report **zero** rows against the very file it was written for.
 * Only the no-decision reason qualifies: a row whose injection never completed is a different instrument failure with
 * nothing to diagnose.
 */
internal fun noDecisionRows(windows: List<NoDecisionWindow>): List<NoDecisionWindow> = windows.filter { window ->
    window.lines.any { line ->
        (line.contains("no_decision_line_in_window") || line.contains("no decision line in window"))
    }
}
