package edith.output

import edith.contracts.OutputStatus
import edith.plugin.DeadlineScheduler
import edith.plugin.CancellationHandle
import edith.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T18 glasses-side hint controller.
 *
 * The glasses are the last line of defence for a hint that arrived late, out of order, for a stale session,
 * or with a deadline that has already passed. The rules the plan fixes:
 *
 * - a session is established by HELLO; generation must be monotonically increasing;
 * - stale session, stale generation and stale revision are refused;
 * - SHOW/CLEAR carry a per-generation increasing `sequence`, and CLEAR raises a fence so any late SHOW at or
 *   below that fence is refused - while a new turn in the same generation may still use a higher sequence,
 *   so a clear must not permanently disable the session;
 * - the local deadline is re-checked before rendering, and a late SHOW may not extend it;
 * - an ACK is `Displayed` only after the render callback actually commits; no ACK may claim human visibility.
 */
class GlassHintControllerTest {
    private val clock = FakeClock()
    private val scheduler = FakeScheduler()
    private val rendered = mutableListOf<String?>()
    private val acks = mutableListOf<HintCommand.Ack>()

    private fun controller() = GlassHintController(clock, scheduler, { rendered += it }, { acks += it })

    private fun expireMapping(controller: GlassHintController) {
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
            validUntilGlassNanos = 10_000_000_000L))
    }

    @Test fun helloEstablishesTheSessionAndAcceptsAShow() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
            validUntilGlassNanos = 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, sequence = 1, hintId = "h", revision = 1, syncId = "sync",
            phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "先讲结论"))
        assertEquals(listOf<String?>("先讲结论"), rendered)
        assertEquals(1, acks.size)
        assertEquals(OutputStatus.Displayed, acks.single().status)
    }

    @Test fun aShowWithoutAnEstablishedSessionIsRefused() {
        val controller = controller()
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 1_000, "text"))
        assertTrue(rendered.isEmpty(), "no session means nothing may be rendered")
    }

    @Test fun aStaleGenerationIsRefused() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", generation = 5))
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", generation = 4, sequence = 1, hintId = "h", revision = 1,
            syncId = "sync", phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "old"))
        assertTrue(rendered.isEmpty(), "an older generation must never render")
    }

    @Test fun aStaleRevisionForTheSameHintIsRefused() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", revision = 3, "sync", 0, 1_000, "v3"))
        controller.accept(HintCommand.Show("s", 1, 2, "h", revision = 2, "sync", 0, 1_000, "v2"))
        assertEquals(listOf<String?>("v3"), rendered, "a lower revision of the same hint must not overwrite")
    }

    @Test fun aShowAfterCloseIsRefused() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 1_000, "first"))
        controller.accept(HintCommand.Clear("s", 1, sequence = 2))
        controller.accept(HintCommand.Show("s", 1, sequence = 2, hintId = "h2", revision = 1, syncId = "sync",
            phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "late"))
        assertEquals(listOf<String?>("first", null), rendered, "a SHOW at or below the clear fence is refused")
    }

    @Test fun aNewTurnAfterClearMayStillRender() {
        // The fence must not permanently disable the session: a strictly higher sequence is a new turn.
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 1_000, "first"))
        controller.accept(HintCommand.Clear("s", 1, 2))
        controller.accept(HintCommand.Show("s", 1, sequence = 3, hintId = "h3", revision = 1, syncId = "sync",
            phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "second turn"))
        assertEquals(listOf<String?>("first", null, "second turn"), rendered)
    }

    @Test fun aShowWithNoClockMappingIsRefused() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 1_000, "no mapping"))
        assertTrue(rendered.isEmpty(), "without a syncId mapping the deadline cannot be trusted")
    }

    @Test fun aLateShowWithAnAlreadyPassedDeadlineIsRefusedInsteadOfExtendingIt() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        // TTL of 2 s, but the phone sent it 1.9 s ago: only 0.1 s may remain, so 1.85 s later it is expired.
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", phoneSentNanos = 0,
            remainingTtlMillis = 2_000, text = "short"))
        assertEquals(1, rendered.size)
        // The scheduled expiry must have fired locally, clearing the text.
        scheduler.fireAll()
        assertEquals(listOf<String?>("short", null), rendered)
    }

    @Test fun theLocalExpiryFiresWithoutAnyFurtherMessageFromThePhone() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 5_000, "text"))
        assertTrue(scheduler.pendingCount > 0, "a local deadline must be scheduled")
        scheduler.fireAll()
        assertEquals(listOf<String?>("text", null), rendered)
    }

    @Test fun disconnectClearsLocally() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 5_000, "text"))
        controller.disconnect()
        assertEquals(listOf<String?>("text", null), rendered, "a lost link must not leave text on screen")
    }

    @Test fun anAckIsOnlySentForACommitAndNeverClaimsHumanVisibility() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 7, "sync", 0, 5_000, "text"))
        assertEquals(1, acks.size)
        val ack = acks.single()
        assertEquals(OutputStatus.Displayed, ack.status)
        assertEquals(7L, ack.revision)
        assertEquals("h", ack.hintId)
        assertEquals(1L, ack.sequence)
        assertTrue(OutputStatus.entries.none { it.name.contains("Human") || it.name.contains("Visible") },
            "no OutputStatus may assert human visibility")
    }

    @Test fun aRefusedShowProducesNoAck() {
        val controller = controller()
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 1_000, "no session"))
        assertTrue(acks.isEmpty(), "a refused command must not be acknowledged as displayed")
    }

    @Test fun aShowWhoseDeadlineAlreadyPassedOnArrivalIsRefused() {
        // The phone stamped it at t=0 with a 1 ms TTL; the wire took 1 ms, so it arrives already dead. The
        // deadline must be re-checked before rendering, or a late hint would flash on screen.
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        clock.nanos = 1_000_000
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", phoneSentNanos = 0,
            remainingTtlMillis = 1, text = "dead on arrival"))
        assertTrue(rendered.isEmpty(), "an already-expired hint must never be rendered")
        assertTrue(acks.isEmpty(), "and it must not be acknowledged as displayed")
    }

    @Test fun aShowExactlyAtItsDeadlineIsRefused() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        // deadline = phoneSent(0) + ttl(1 s) + offsetLower(0) - drift(0) = 1 s
        clock.nanos = 1_000_000_000L
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", phoneSentNanos = 0,
            remainingTtlMillis = 1_000, text = "at the boundary"))
        assertTrue(rendered.isEmpty(), "the deadline instant itself is not a valid render time")
    }

    /**
     * The device numbers, not a fake clock near zero.
     *
     * Found on the glasses on 2026-10-08: `SYNC` was accepted with `validFor=29956ms` and the very next `SHOW` was
     * refused as "mapping expired". The mapping had been anchored at zero while `isExpired` measures elapsed time, so
     * on any device whose monotonic clock is older than the validity window (every real phone and glasses - the
     * glasses' uptime was ~145,000 s) it read as expired the instant it was created. The tests only ever used clocks
     * starting at zero, which is exactly why they stayed green.
     */
    @Test fun aMappingFromSyncIsNotAlreadyExpiredOnADeviceWhoseClockIsHoursOld() {
        val glassUptime = 145_533_601_387_345L
        val phoneUptime = 144_913_586_573_571L
        val offset = glassUptime - phoneUptime
        val deviceClock = FakeClock(glassUptime)
        val rendered = mutableListOf<String?>()
        val acks = mutableListOf<HintCommand.Ack>()
        val controller = GlassHintController(deviceClock, scheduler, { rendered += it }, { acks += it })

        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", offsetLowerNanos = offset, offsetUpperNanos = offset + 31_000_000,
            validUntilGlassNanos = glassUptime + 30_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, sequence = 1, hintId = "h", revision = 1, syncId = "sync",
            phoneSentNanos = phoneUptime, remainingTtlMillis = 10_000, text = "先讲结论"))

        assertEquals(listOf<String?>("先讲结论"), rendered, "a freshly accepted mapping must not read as expired")
        assertEquals(1, acks.size, "and it must be acknowledged once rendered")
    }

    private class FakeClock(var nanos: Long = 0) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val pendingCount: Int get() = tasks.size
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val entry = deadlineNanos to task
            tasks += entry
            return object : CancellationHandle {
                override fun cancel() { tasks.remove(entry) }
            }
        }
        fun fireAll() {
            val ready = tasks.toList()
            tasks.clear()
            ready.forEach { it.second() }
        }
    }
}
