package edith.probe.eval

/** One frozen corpus row: what the corpus says should happen, and the sentence it says it about. */
data class EvalLabel(val id: String, val scenario: String, val label: String, val text: String)

/** What the device actually did for one corpus row. */
data class Observed(val id: String, val triggered: Boolean)

/** One scenario's counts and rates. Counts are always carried, so no rate is ever quoted without its denominator. */
data class ScenarioScore(
    val positives: Int,
    val negatives: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double,
    val recall: Double,
)

/**
 * The phase's trigger metrics (spec §6.3): precision ≥90%, recall ≥80%, and the chat mis-trigger window.
 *
 * [precision] and [recall] are **micro-averaged** over every scenario (the sums of the counts), while [perScenario]
 * keeps each scenario's own figures - a system that is precise on `knowledge` and useless on `dilemma` must not be able
 * to hide behind one total.
 */
data class TriggerScore(
    val perScenario: Map<String, ScenarioScore>,
    val precision: Double,
    val recall: Double,
    val chatTriggers: Int,
    val windowMinutes: Double,
    val chatTriggersPerTenMinutes: Double,
)

/** The four scenarios the corpus is written in; anything else in a table is not a label row. */
private val SCENARIOS = setOf("knowledge", "privacy", "dilemma", "chat")

/**
 * Reads the corpus markdown's label rows and nothing else.
 *
 * The file is also full of prose and a counts table, so a row only counts when it has the five columns, a known
 * scenario and a `positive`/`negative` label. A parser that guessed would silently score the wrong set, and a wrong set
 * scored honestly is still a wrong result.
 */
fun parseLabels(markdown: String): List<EvalLabel> =
    markdown.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("|") }
        .map { line -> line.trim('|').split('|').map { cell -> cell.trim() } }
        .filter { cells -> cells.size >= 5 && cells[1] in SCENARIOS && (cells[2] == "positive" || cells[2] == "negative") }
        .map { cells -> EvalLabel(cells[0], cells[1], cells[2], cells[3]) }
        .toList()

/**
 * Scores [labels] against [observed].
 *
 * Three decisions worth stating, because each one could otherwise flatter the result:
 *
 *  - a row with **no observation counts as not triggered** - absence of evidence is not a trigger;
 *  - an observation for an id the corpus does not contain is **ignored**, so a stray log line cannot invent a
 *    false positive or a true one;
 *  - a scenario with **no positive prediction at all** scores `precision = 1.0` and `recall = 0.0`: there were no false
 *    positives because there was nothing predicted, and the zero recall is what says so out loud. (Quoting only the
 *    precision of a system that never fires is the classic way to make it look perfect.)
 */
fun score(labels: List<EvalLabel>, observed: List<Observed>, windowMinutes: Double = 10.0): TriggerScore {
    val triggered = observed.associate { it.id to it.triggered }
    val perScenario = linkedMapOf<String, ScenarioScore>()
    for (scenario in labels.map { it.scenario }.distinct()) {
        val rows = labels.filter { it.scenario == scenario }
        val positives = rows.count { it.label == "positive" }
        val negatives = rows.count { it.label == "negative" }
        val truePositives = rows.count { it.label == "positive" && triggered[it.id] == true }
        val falsePositives = rows.count { it.label == "negative" && triggered[it.id] == true }
        perScenario[scenario] = ScenarioScore(
            positives = positives,
            negatives = negatives,
            truePositives = truePositives,
            falsePositives = falsePositives,
            falseNegatives = positives - truePositives,
            precision = precisionOf(truePositives, falsePositives),
            recall = recallOf(truePositives, positives - truePositives),
        )
    }
    val truePositives = perScenario.values.sumOf { it.truePositives }
    val falsePositives = perScenario.values.sumOf { it.falsePositives }
    val falseNegatives = perScenario.values.sumOf { it.falseNegatives }
    val chatTriggers = perScenario["chat"]?.falsePositives ?: 0
    return TriggerScore(
        perScenario = perScenario,
        precision = precisionOf(truePositives, falsePositives),
        recall = recallOf(truePositives, falseNegatives),
        chatTriggers = chatTriggers,
        windowMinutes = windowMinutes,
        chatTriggersPerTenMinutes = if (windowMinutes <= 0.0) 0.0 else chatTriggers * 10.0 / windowMinutes,
    )
}

private fun precisionOf(truePositives: Int, falsePositives: Int): Double =
    if (truePositives + falsePositives == 0) 1.0 else truePositives.toDouble() / (truePositives + falsePositives)

private fun recallOf(truePositives: Int, falseNegatives: Int): Double =
    if (truePositives + falseNegatives == 0) 0.0 else truePositives.toDouble() / (truePositives + falseNegatives)
