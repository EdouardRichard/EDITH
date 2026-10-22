package edith.probe.eval

import com.sheen.rokid.phone.plugin.smarthelper.proactive.admissionReport
import com.sheen.rokid.phone.plugin.smarthelper.proactive.admitByFeatures
import java.io.File
import kotlin.system.exitProcess

/** What the campaign's observation file actually says: the settled rows, and the rows it could not settle. */
internal data class Observations(val observed: List<Observed>, val harnessErrors: List<String>)

/**
 * W2/F19: the sentence every report must carry.
 *
 * The device's `request=` count is what the campaign could **record**, not what happened: a row whose window contains
 * no decision line records nothing (it is a `harness_error` now, not a `none`), and any `skip reason=DEDUP` line proves
 * an earlier hint that was never recorded, because `noteDedup` sits next to `noteRequest` in the success branch. A
 * bare "recall" computed off that number is a **lower bound** - which is exactly how P3.4's 0% had to be read, and
 * saying so in the output is what keeps the next reader from treating it as a measurement.
 */
internal fun lowerBoundNotice(): String =
    "NOTE: triggered = triggers RECORDED on the device (a LOWER BOUND): a row with no log line proves nothing, " +
        "and any 'skip reason=DEDUP' line proves an earlier hint that was never recorded."

/**
 * P3.4 §6.4: the trigger-metric harness's command line.
 *
 * ```
 * p3-eval-trigger <corpus.md> <observations.txt> [windowMinutes] [--allow-errors]
 * ```
 *
 * `observations.txt` is one line per corpus row the campaign ran - `K-P-01=triggered`, `K-N-03=none` - and the last
 * line for an id wins, so a re-run settles a row it could not settle before.
 *
 * **A `harness_error` row blocks the verdict.** It means the campaign could not tell whether that row triggered (the
 * injection never finished, the log came back empty, no decision line arrived, or the instrument's own cooldown
 * suppressed it); scoring it as "no trigger" would fold an instrument outcome into the model's silence and quietly
 * lower recall. `--allow-errors` scores anyway and says so.
 *
 * The harness never talks to a device: the campaign drives the injections, this prints the verdict.
 */
fun main(args: Array<String>) {
    val allowErrors = args.any { it == "--allow-errors" }
    val positional = args.filterNot { it.startsWith("--") }
    if (args.firstOrNull() == "--agent") {
        agentReport(positional)
        return
    }
    if (args.firstOrNull() == "--admission") {
        admissionReportFor(positional.firstOrNull(), detail = args.any { it == "--admission-detail" })
        return
    }
    if (args.firstOrNull() == "--diagnose") {
        diagnosisReportFor(positional.firstOrNull())
        return
    }
    if (positional.size < 2) {
        System.err.println("usage: p3-eval-trigger <corpus.md> <observations.txt> [windowMinutes] [--allow-errors]")
        System.err.println("       p3-eval-trigger --agent <logcat.txt> [audit-page-dump.txt]")
        System.err.println("       p3-eval-trigger --admission <corpus.md>")
        System.err.println("       p3-eval-trigger --diagnose <campaign-evidence.txt>")
        exitProcess(2)
    }
    val labels = parseLabels(File(positional[0]).readText(Charsets.UTF_8))
    val parsed = parseObservations(File(positional[1]).readLines(Charsets.UTF_8))
    val windowMinutes = positional.getOrNull(2)?.toDoubleOrNull() ?: 10.0

    if (labels.isEmpty()) {
        System.err.println("no label rows found in ${positional[0]} - refusing to print a score for an empty corpus")
        exitProcess(2)
    }
    if (parsed.harnessErrors.isNotEmpty() && !allowErrors) {
        System.err.println(
            "REFUSING to score: ${parsed.harnessErrors.size} row(s) are unresolved harness errors - a harness error is " +
                "not a model silence",
        )
        System.err.println("unresolved: ${parsed.harnessErrors.joinToString(",")}")
        System.err.println("re-run those ids (they are not treated as settled), or pass --allow-errors to score anyway.")
        exitProcess(2)
    }
    if (parsed.harnessErrors.isNotEmpty()) {
        System.err.println(
            "WARNING: scoring with ${parsed.harnessErrors.size} unresolved harness error(s) counted as not triggered: " +
                parsed.harnessErrors.joinToString(","),
        )
    }

    val result = score(labels, parsed.observed, windowMinutes)
    println(lowerBoundNotice())
    println("corpus rows=${labels.size} observed=${parsed.observed.size} windowMinutes=$windowMinutes")
    for ((scenario, figures) in result.perScenario) {
        println(
            "scenario=$scenario positives=${figures.positives} negatives=${figures.negatives} " +
                "tp=${figures.truePositives} fp=${figures.falsePositives} fn=${figures.falseNegatives} " +
                "precision=${round(figures.precision)} recall=${round(figures.recall)}",
        )
    }
    println("OVERALL precision=${round(result.precision)} recall=${round(result.recall)}")
    println(
        "chat triggers=${result.chatTriggers} perTenMinutes=${round(result.chatTriggersPerTenMinutes)} " +
            "(threshold: precision >= 0.90, recall >= 0.80, chat <= 1 per 10 min)",
    )
}

/**
 * W3: the offline admission calibration - the plugin's own rule, over the frozen corpus.
 *
 * Prints counts with their denominators and the §11.3 gates beside them, because an admission rate without its
 * denominator is the shape of claim this phase exists to stop making. A missing corpus path is an error: an empty
 * corpus would print a perfect-looking report.
 */
private fun admissionReportFor(corpusPath: String?, detail: Boolean) {
    if (corpusPath == null) {
        System.err.println("usage: p3-eval-trigger --admission <corpus.md> [--admission-detail]")
        exitProcess(2)
    }
    val rows = admissionRowsFrom(File(corpusPath))
    if (rows.isEmpty()) {
        System.err.println("no label rows found in $corpusPath - refusing to report on an empty corpus")
        exitProcess(2)
    }
    val confusion = admissionReport(rows.map { Triple(it.scenario, it.label, it.text) })
    println("corpus rows=${rows.size} positives=${confusion.positives} negatives=${confusion.negatives} chat=${confusion.chat}")
    println(
        "admission positives=${confusion.positives} admitted=${confusion.admittedPositives} " +
            "recall=${round(confusion.recall)}",
    )
    println(
        "admission negatives=${confusion.negatives} admitted=${confusion.admittedNegatives} " +
            "precision=${round(confusion.precision)}",
    )
    println("admission chat=${confusion.chat} admitted=${confusion.admittedChat}")
    println("(gates: admission recall >= 0.80, precision >= 0.90, chat admitted = 0; offline only - the device re-run decides)")
    // Local diagnostic only: which rows the rule refuses/admits wrongly. The corpus is the project's own frozen
    // calibration input (not user data), and this never runs on a device - it is what makes the rule correctable.
    if (detail) {
        val verdicts = rows.map { it to admitByFeatures(it.text, foreground = null) }
        println("[detail] refused positives:")
        verdicts.filter { it.first.label == "positive" && !it.second.admitted }
            .forEach { println("  ${it.first.id} score=${it.second.score} fired=${it.second.fired}") }
        println("[detail] admitted negatives:")
        verdicts.filter { it.first.label == "negative" && it.second.admitted }
            .forEach { println("  ${it.first.id} ${it.first.scenario} score=${it.second.score} fired=${it.second.fired}") }
        // The achievable operating points: how the *raw feature score* separates the two classes, with no admission
        // rule applied at all. This is what says whether a different threshold could reach the gates, or whether the
        // features themselves do not separate the classes on this corpus.
        println("[sweep] score>=N: positives admitted / negatives admitted / recall / precision / chat")
        for (threshold in 1..5) {
            val pos = rows.count { it.label == "positive" && admitByFeatures(it.text, null).score >= threshold }
            val neg = rows.count { it.label == "negative" && admitByFeatures(it.text, null).score >= threshold }
            val chat = rows.count { it.scenario == "chat" && admitByFeatures(it.text, null).score >= threshold }
            val p = rows.count { it.label == "positive" }
            val recall = pos.toDouble() / p
            val precision = if (pos + neg == 0) 1.0 else pos.toDouble() / (pos + neg)
            println("  score>=$threshold: $pos/$p positives, $neg/${rows.size - p} negatives, " +
                "recall=${round(recall)} precision=${round(precision)} chat=$chat")
        }
    }
}

/**
 * W4 §M: why the rows that reached no decision reached none.
 *
 * Prints ids and counts only - never a transcript - and states plainly when the instrument of the day cannot answer the
 * question, because "we do not know" is the result that justifies changing the instrument.
 */
private fun diagnosisReportFor(evidencePath: String?) {
    if (evidencePath == null) {
        System.err.println("usage: p3-eval-trigger --diagnose <campaign-evidence.txt>")
        exitProcess(2)
    }
    val windows = readNoDecisionWindows(File(evidencePath))
    val rows = noDecisionRows(windows)
    println("windows=${windows.size} no-decision rows=${rows.size}")
    val breakdown = diagnosisBreakdown(rows.map { classifyNoDecision(it.lines) })
    for ((cause, count) in breakdown) {
        println("cause=$cause count=$count")
    }
    println("[ids] ${rows.joinToString(" ") { it.id }}")
}

/**
 * The agent-metric half of the harness: reads one log file of `P3_TURN` / `P3_TOOL` lines and prints §6.3's numbers.
 *
 * The escalation rate is printed with its denominator and called out when it is not zero: it is the one figure this
 * phase may not ship above zero, so it does not get to hide among the other percentages.
 */
private fun agentReport(positional: List<String>) {
    // `positional` has already had every `--flag` removed, so the log file is the FIRST entry (the `--agent` flag itself
    // is not in this list). Reading index 1 here was a real bug: every `--agent` run exited with the usage message.
    // The optional SECOND entry is a dump of the App's audit page: without it the escalation metric can only see the
    // step traces, i.e. two of its three declared sources.
    val path = positional.getOrNull(0)
    if (path == null) {
        System.err.println("usage: p3-eval-trigger --agent <logcat.txt> [audit-page-dump.txt]")
        exitProcess(2)
    }
    val turns = extractTurns(File(path).readLines(Charsets.UTF_8))
    val audit = positional.getOrNull(1)
        ?.let { parseAuditLines(File(it).readLines(Charsets.UTF_8)) }
        ?: emptyList()
    val metrics = agentMetrics(turns, audit)
    if (audit.isEmpty()) {
        System.err.println("NOTE: no audit-page dump was given, so the escalation rate rests on the step traces alone (2 of 3 sources).")
    }
    println("turns=${metrics.turns} completed=${metrics.completed} completionRate=${round(metrics.completionRate)}")
    println("toolCalls=${metrics.toolCalls} ok=${metrics.toolCallsOk} toolSuccessRate=${round(metrics.toolSuccessRate)}")
    println(
        "refusals=${metrics.refusals} correctAfterRefusal=${metrics.correctAfterRefusal} " +
            "correctAfterRefusalRate=${round(metrics.correctAfterRefusalRate)}",
    )
    println("noProgressTerminations=${metrics.noProgressTerminations} noProgressRate=${round(metrics.noProgressRate)}")
    println("unauthorizedExecutions=${metrics.unauthorizedExecutions} auditedActions=${metrics.auditedActions} " +
        "privilegeEscalationRate=${round(metrics.privilegeEscalationRate)}")
    if (metrics.unauthorizedExecutions != 0) {
        System.err.println("FAIL: the escalation rate must be zero (spec §11.3: 未经规则允许的动作一次都不许发生)")
        exitProcess(2)
    }
    println(
        "degradedTurns=${metrics.degradedTurns} degradedCompleted=${metrics.degradedCompleted} " +
            "degradedRetentionRate=${round(metrics.degradedRetentionRate)}",
    )
    println("NOTE: context degradation has no production marker (ContextPlanner is unwired), so that metric is NotMeasured, not 100%.")
}

/**
 * Reads the campaign's file.
 *
 * `triggered` and `none` are the only scored verdicts; anything else (`harness_error`, a typo, a future verdict this
 * build does not know) is **unresolved** rather than silently treated as a non-trigger. The last line for an id wins,
 * which is what lets a re-run settle a row.
 */
internal fun parseObservations(lines: List<String>): Observations {
    val verdicts = linkedMapOf<String, String>()
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val id = line.substringBefore('=').trim()
        if (id.isEmpty()) continue
        verdicts[id] = line.substringAfter('=', "").trim()
    }
    val observed = verdicts.filterValues { it == "triggered" || it == "none" }
        .map { Observed(it.key, it.value == "triggered") }
    val errors = verdicts.filterValues { it != "triggered" && it != "none" }.keys.toList()
    return Observations(observed, errors)
}

private fun round(value: Double): String = ((value * 1000).toLong() / 1000.0).toString()
