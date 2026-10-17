package edith.probe.eval

/**
 * Reads the App's **rendered audit page** into [AuditRow]s (spec §6.4's second half of the escalation metric).
 *
 * The page draws one row per action as `MM-dd HH:mm:ss | action | level | decision | outcome | source | result`. Only
 * three of those cells are read - action, decision, outcome - because those are the three the metric uses, and the
 * `source`/`result` cells can carry a target argument the harness has no business collecting.
 *
 * A line that is not a row (the page's own headings and prose) is skipped rather than guessed at: a parser that invents
 * a row out of a sentence would turn "no evidence" into "no escalation".
 */
fun parseAuditLines(lines: List<String>): List<AuditRow> =
    lines.mapNotNull { line ->
        val cells = line.split('|').map { it.trim() }
        if (cells.size < 5 || !AUDIT_TIMESTAMP.matches(cells[0])) return@mapNotNull null
        AuditRow(action = cells[1], decision = cells[3], outcome = cells[4])
    }

private val AUDIT_TIMESTAMP = Regex("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d")
