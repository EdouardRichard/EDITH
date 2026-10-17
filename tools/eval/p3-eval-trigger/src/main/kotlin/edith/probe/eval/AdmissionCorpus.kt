package edith.probe.eval

import java.io.File

/**
 * W3: the frozen corpus's rows, as the admission calibration needs them.
 *
 * The corpus is markdown with prose, count tables and four label tables. [admissionRows] keeps exactly the rows the
 * scorer keeps (`parseLabels`'s rule: five columns, a known scenario, a `positive`/`negative` label) and adds the
 * **scenario**, which the admission report needs because §11.3's chat clause is defined on it.
 *
 * This reads the **same file** `parseLabels` does, by the same rule, so "the rule passed calibration" and "the rule is
 * scored on the frozen corpus" cannot drift apart.
 */
internal data class CorpusRow(val id: String, val scenario: String, val label: String, val text: String)

/** The four scenarios the corpus is written in; anything else in a table is not a label row. */
private val CORPUS_SCENARIOS = setOf("knowledge", "privacy", "dilemma", "chat")

internal fun admissionRows(markdown: String): List<CorpusRow> =
    markdown.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("|") }
        .map { line -> line.trim('|').split('|').map { cell -> cell.trim() } }
        .filter { cells -> cells.size >= 5 && cells[1] in CORPUS_SCENARIOS && (cells[2] == "positive" || cells[2] == "negative") }
        .map { cells -> CorpusRow(cells[0], cells[1], cells[2], cells[3]) }
        .toList()

/** Reads [path] as UTF-8 and parses it; a missing file is an error, never an empty corpus. */
internal fun admissionRowsFrom(path: File): List<CorpusRow> {
    require(path.isFile) { "frozen corpus not found at ${path.absolutePath}" }
    return admissionRows(path.readText(Charsets.UTF_8))
}
