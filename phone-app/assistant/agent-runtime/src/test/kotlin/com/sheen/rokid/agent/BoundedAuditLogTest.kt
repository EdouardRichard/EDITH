package com.sheen.rokid.agent

import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [`BoundedAuditLog`] is the in-memory [AuditSink] P3.1 can actually run: spec §7.4's encrypted on-disk store is a
 * later phase, so this recorder's whole job is to be **honest and bounded**.
 *
 * "Honest" means a line keeps the identity it was given and nothing else; "bounded" means it can never hold a raw
 * screen or notification dump (the summary is flattened and capped) and can never grow past its `capacity`.
 */
class BoundedAuditLogTest {

    private val clock = FakeClock()

    @Test fun `a line keeps its identity verbatim and is stamped by the clock`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)
        clock.nowNanos = 1_234L

        log.record("turn-1", "message.send", "sent to contact A", "ok")

        assertEquals(
            listOf(AuditEntry("turn-1", "message.send", "sent to contact A", "ok", 1_234L)),
            log.entries(),
        )
    }

    @Test fun `each line is stamped with the clock at record time`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)
        clock.nowNanos = 10L
        log.record("turn-1", "a", "s", "ok")
        clock.advance(5L)
        log.record("turn-2", "b", "s", "ok")

        assertEquals(listOf(10L, 15L), log.entries().map { it.atNanos })
    }

    @Test fun `every line break and control character in the summary is stored as a space`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)

        log.record("turn-1", "screen.read", "line one\nline two\r\tend\u0000\u001b", "ok")

        assertEquals("line one line two  end  ", log.entries().single().summary)
    }

    @Test fun `a Unicode line separator is flattened too`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)

        log.record("turn-1", "screen.read", "before\u2028after\u2029end", "ok")

        assertEquals("before after end", log.entries().single().summary)
    }

    @Test fun `a notification body dump cannot be stored wholesale`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)
        val body = "面试官: 请介绍一下你自己\n" + "原文".repeat(200) + "SENSITIVE_TAIL"

        log.record("turn-1", "notification.read", body, "ok")

        val stored = log.entries().single().summary
        assertTrue(stored.length <= 200, "a bounded recorder must cap the summary, was ${stored.length}")
        assertFalse('\n' in stored, "the newline that made this a body dump must not survive")
        assertFalse("SENSITIVE_TAIL" in stored, "the tail past the cap must be gone")
    }

    @Test fun `a summary of exactly 200 characters is kept whole`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)
        val exact = "x".repeat(200)

        log.record("turn-1", "a", exact, "ok")

        assertEquals(exact, log.entries().single().summary)
    }

    @Test fun `only the most recent capacity lines are kept, in insertion order`() {
        val log = BoundedAuditLog(capacity = 3, clock = clock)

        (1..5).forEach { log.record("turn-$it", "action", "s$it", "ok") }

        assertEquals(listOf("turn-3", "turn-4", "turn-5"), log.entries().map { it.turnId })
    }

    @Test fun `clear empties the log`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)
        log.record("turn-1", "a", "s", "ok")

        log.clear()

        assertEquals(emptyList(), log.entries())
    }

    @Test fun `entries returns a defensive copy`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)
        log.record("turn-1", "a", "s", "ok")

        val snapshot = log.entries()
        log.record("turn-2", "b", "s", "ok")

        assertEquals(1, snapshot.size)
        assertEquals(2, log.entries().size)
    }

    @Test fun `a structured line keeps its scope risk decision and source`() {
        val log = BoundedAuditLog(capacity = 8, clock = clock)

        log.record(
            "turn-1", "app.uninstall", "package=edith.phone.a11yprobe", "ok",
            scope = "app", risk = "L3", decision = "require_confirmation",
            confirmationSource = ConfirmationSource.VOICE,
        )

        val entry = log.entries().single()
        assertEquals("app", entry.scope)
        assertEquals("L3", entry.risk)
        assertEquals("require_confirmation", entry.decision)
        assertEquals(ConfirmationSource.VOICE, entry.confirmationSource)
    }

    @Test fun `a capacity below one is rejected`() {
        assertFailsWith<IllegalArgumentException> { BoundedAuditLog(capacity = 0, clock = clock) }
    }
}
