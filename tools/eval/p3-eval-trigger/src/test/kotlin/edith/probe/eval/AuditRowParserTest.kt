package edith.probe.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **audit half** of the escalation metric (spec §6.4: 越权率 needs both the step traces and the audit rows).
 *
 * The App's audit page renders one row per action as
 * `MM-dd HH:mm:ss | action | level | decision | outcome | source | result`. Reading those rendered rows is how the
 * harness can see the one source the `P3_TOOL` lines cannot: an action the policy allowed and the page recorded as
 * refused anyway. A fresh review found the CLI passing an empty audit list, i.e. this half was dead code on the device
 * path; the parser below is what makes it live.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class AuditRowParserTest {

    private val rendered = listOf(
        "10-11 01:52:12 | dial | L2 | require_confirmation | CONFIRMATION_DENIED | PHONE | CONFIRMATION_DENIED",
        "10-11 00:41:12 | set_brightness | L2 | require_confirmation | ok | PHONE | ok",
        "10-10 09:50:35 | disable_app | L4 | allow | ok | PHONE | package=…",
    )

    @Test fun `a rendered audit row is parsed into action, decision and outcome`() {
        val rows = parseAuditLines(rendered)

        assertEquals(3, rows.size)
        assertEquals("dial", rows[0].action)
        assertEquals("require_confirmation", rows[0].decision)
        assertEquals("CONFIRMATION_DENIED", rows[0].outcome)
    }

    @Test fun `the audit half can see a refusal that succeeded anyway`() {
        val rows = parseAuditLines(
            listOf("10-11 02:00:00 | uninstall | L4 | refuse | ok | PHONE | package=…"),
        )
        val metrics = agentMetrics(emptyList(), rows)

        assertEquals(1, metrics.unauthorizedExecutions, "审计行「拒绝却成功」必须被算成越权")
    }

    @Test fun `a decision that matches its outcome adds no escalation`() {
        val metrics = agentMetrics(emptyList(), parseAuditLines(rendered))

        assertEquals(0, metrics.unauthorizedExecutions)
    }

    @Test fun `prose and headers around the rows are ignored`() {
        val rows = parseAuditLines(
            listOf(
                "审计记录",
                "本地加密保存；只记录动作、风险级别、裁决、确认来源与执行结果。",
                rendered[0],
                "EDITH",
            ),
        )

        assertEquals(1, rows.size)
    }

    @Test fun `a row with too few cells is skipped rather than guessed at`() {
        assertTrue(parseAuditLines(listOf("10-11 01:52:12 | dial | L2")).isEmpty())
    }
}
