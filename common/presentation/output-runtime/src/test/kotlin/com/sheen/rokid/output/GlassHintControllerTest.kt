package com.sheen.rokid.output

import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val rendered = mutableListOf<DisplayedHint?>()
    private val acks = mutableListOf<HintCommand.Ack>()

    private fun controller() = GlassHintController(clock, scheduler, { rendered += it }, { acks += it })

    /** What the wearer would see, so an assertion about the text does not have to restate the route and the id. */
    private fun renderedTexts(): List<String?> = rendered.map { it?.text }

    private fun expireMapping(controller: GlassHintController) {
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
            validUntilGlassNanos = 10_000_000_000L))
    }

    @Test
    fun theControllerExposesTheIdentityItAcceptsCommandsFor() {
        // 0.23 P5: the glasses' BACK needs the session/generation to put in a STOP. It must be the identity the
        // controller accepted, not a second copy that could disagree with the SHOW gate.
        val controller = controller()
        assertNull(controller.currentIdentity())
        controller.accept(HintCommand.Hello("s-1", 4))
        assertEquals("s-1" to 4L, controller.currentIdentity())
        controller.accept(HintCommand.Hello("s-2", 1))
        assertEquals("s-2" to 1L, controller.currentIdentity())
        controller.disconnect()
        assertNull(controller.currentIdentity())
    }

    @Test
    fun aStopFromThePhoneIsNotACommandTheGlassesActOn() {
        // STOP travels glasses -> phone. If a mixed build ever sends it back, it must be inert rather than crash the
        // controller's exhaustive `when`.
        val controller = controller()
        controller.accept(HintCommand.Hello("s-1", 1))
        controller.accept(HintCommand.Stop("s-1", 1))
        assertEquals("s-1" to 1L, controller.currentIdentity())
    }

    @Test fun helloEstablishesTheSessionAndAcceptsAShow() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
            validUntilGlassNanos = 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, sequence = 1, hintId = "h", revision = 1, syncId = "sync",
            phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "先讲结论"))
        assertEquals(listOf<String?>("先讲结论"), renderedTexts())
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
        assertEquals(listOf<String?>("v3"), renderedTexts(), "a lower revision of the same hint must not overwrite")
    }

    @Test fun aShowAfterCloseIsRefused() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 1_000, "first"))
        controller.accept(HintCommand.Clear("s", 1, sequence = 2))
        controller.accept(HintCommand.Show("s", 1, sequence = 2, hintId = "h2", revision = 1, syncId = "sync",
            phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "late"))
        assertEquals(listOf<String?>("first", null), renderedTexts(), "a SHOW at or below the clear fence is refused")
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
        assertEquals(listOf<String?>("first", null, "second turn"), renderedTexts())
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
        assertEquals(listOf<String?>("short", null), renderedTexts())
    }

    @Test fun theLocalExpiryFiresWithoutAnyFurtherMessageFromThePhone() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 5_000, "text"))
        assertTrue(scheduler.pendingCount > 0, "a local deadline must be scheduled")
        scheduler.fireAll()
        assertEquals(listOf<String?>("text", null), renderedTexts())
    }

    @Test fun disconnectClearsLocally() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 5_000, "text"))
        controller.disconnect()
        assertEquals(listOf<String?>("text", null), renderedTexts(), "a lost link must not leave text on screen")
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
        val rendered = mutableListOf<DisplayedHint?>()
        val acks = mutableListOf<HintCommand.Ack>()
        val controller = GlassHintController(deviceClock, scheduler, { rendered += it }, { acks += it })

        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Sync("sync", offsetLowerNanos = offset, offsetUpperNanos = offset + 31_000_000,
            validUntilGlassNanos = glassUptime + 30_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, sequence = 1, hintId = "h", revision = 1, syncId = "sync",
            phoneSentNanos = phoneUptime, remainingTtlMillis = 10_000, text = "先讲结论"))

        // This test drives its own controller on a device-aged clock, so it reads its own list rather than the
        // field-backed helper above.
        assertEquals(listOf<String?>("先讲结论"), rendered.map { it?.text },
            "a freshly accepted mapping must not read as expired")
        assertEquals(1, acks.size, "and it must be acknowledged once rendered")
    }

    /**
     * The phone's session generation is a **per-process** counter, and the glasses' is for the life of *their*
     * process.
     *
     * `HostRuntime` starts at 0 and increments on every `startSession`, so a phone app run that started three
     * sessions sends `HELLO` with generation 3, while the next app run starts again at generation 1 - and the glasses
     * process (and the session it stored) outlives the phone's. Measured on the K40 + glasses on 2026-10-09: after
     * such a restart every `SHOW` was refused for the rest of the glasses process's life
     * (`Show refused: session 393e1238-… != 49dedb25-…`), so no hint could ever be displayed again.
     *
     * A `HELLO` naming a **different** session is a new session and must replace the stored one; only a `HELLO` for
     * the **same** session with an older generation is stale, which is the ordering rule this gate was written for.
     */
    @Test fun aHelloForANewSessionReplacesTheStoredOneEvenWhenThePhonesGenerationRestartedLower() {
        val controller = controller()
        controller.accept(HintCommand.Hello("old-session", generation = 3))
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("old-session", 3, 1, "h", 1, "sync", 0, 5_000, "old"))

        // The phone app is restarted: a new session, and its own generation counter is back at 1.
        controller.accept(HintCommand.Hello("new-session", generation = 1))
        controller.accept(HintCommand.Sync("sync2", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("new-session", 1, sequence = 1, hintId = "h2", revision = 1,
            syncId = "sync2", phoneSentNanos = 0, remainingTtlMillis = 5_000, text = "new"))

        assertEquals(listOf<String?>("old", null, "new"), renderedTexts(),
            "a HELLO for a different session must be adopted even when its generation is lower")
        assertEquals(2, acks.size, "and the new session's hint must be acknowledged")
    }

    /** The ordering rule the generation gate exists for: an older `HELLO` for the *same* session is still ignored. */
    @Test fun aHelloForTheSameSessionWithAnOlderGenerationIsStillIgnored() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", generation = 5))
        controller.accept(HintCommand.Hello("s", generation = 4))
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 5, 1, "h", 1, "sync", 0, 5_000, "text"))
        assertEquals(listOf<String?>("text"), renderedTexts(), "the newer generation of the same session must survive")
    }

    /**
     * The gate that hid D14 for three rounds: a `HELLO` that was ignored looked exactly like one that never arrived.
     * Both outcomes are now reported with the session/generation that was stored, so the glasses' log can tell
     * "this process kept an old session" from "the HELLO never reached it".
     */
    @Test fun aHelloReportsWhetherItWasAdoptedAndWhatItReplaced() {
        val lines = mutableListOf<String>()
        val controller = GlassHintController(clock, scheduler, { rendered += it }, { acks += it },
            diagnostics = { lines += it })
        controller.accept(HintCommand.Hello("s-1", generation = 3))
        controller.accept(HintCommand.Hello("s-2", generation = 1))
        controller.accept(HintCommand.Hello("s-2", generation = 0))
        assertEquals(listOf(
            "Hello accepted: session=s-1 generation=3",
            "Hello accepted: session=s-2 generation=1 (replaced session=s-1 generation=3)",
            "Hello refused: generation 0 < 1 for session s-2",
        ), lines)
    }

    // ---------------------------------------------------------------- Phase C: what the render callback carries

    /**
     * The controller owns the session identity, so a caller with its own traffic to gate (Phase C's `SAID` — an
     * utterance the wearer made) must be able to ask it rather than keep a second copy that could disagree. A second
     * copy is how a late utterance from the previous session ends up in this session's conversation.
     */
    @Test fun aCallerCanAskWhetherAnIdentityIsTheSessionThisControllerAdopted() {
        val controller = controller()
        assertFalse(controller.isCurrent("s", 1), "nothing has been adopted yet")
        controller.accept(HintCommand.Hello("s", 4))
        assertTrue(controller.isCurrent("s", 4))
        assertFalse(controller.isCurrent("s", 3), "an older generation of the same session is not current")
        assertFalse(controller.isCurrent("other", 4), "another session's traffic is never current")
        controller.disconnect()
        assertFalse(controller.isCurrent("s", 4), "a lost link adopts nothing")
    }

    /**
     * The glasses show one conversation per route (spec §6.6), so the render callback must carry the route of the
     * hint it accepted — and it must carry it *from here*, because this class is where the eight acceptance gates
     * live. A view that decided for itself whether a SHOW was real would be a second implementation of those rules,
     * and the two would drift.
     */
    @Test fun theRenderCallbackCarriesTheAcceptedShowAndItsRoute() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, sequence = 1, hintId = "h-1", revision = 1, syncId = "sync",
            phoneSentNanos = 0, remainingTtlMillis = 1_000, text = "左边的答案",
            route = com.sheen.rokid.contracts.AssistantRoute.PROACTIVE))
        assertEquals(
            listOf<DisplayedHint?>(DisplayedHint("左边的答案", com.sheen.rokid.contracts.AssistantRoute.PROACTIVE, "h-1")),
            rendered)
    }

    /** A hint the gates refused must not enter the wearer's conversation: nobody saw it, so nobody said it. */
    @Test fun aRefusedShowNeverReachesTheRenderCallback() {
        val controller = controller()
        controller.accept(HintCommand.Hello("s", 1))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "never-synced", 0, 1_000, "no mapping"))
        assertTrue(rendered.isEmpty(), "a refused hint must not enter the conversation")
    }

    /**
     * Retiring a hint is `null`, and the name matters: `null` means "nothing is live on the panel now", **not**
     * "forget what was shown". The conversation the wearer reads back from is the view's, and this callback must not
     * be readable as an instruction to erase it.
     */
    @Test fun retiringAHintIsReportedAsNothingLiveRatherThanAsAnInstructionToErase() {
        val controller = controller()
        expireMapping(controller)
        controller.accept(HintCommand.Sync("sync", 0, 1_000_000, 10_000_000_000L))
        controller.accept(HintCommand.Show("s", 1, 1, "h", 1, "sync", 0, 5_000, "文本"))
        controller.accept(HintCommand.Clear("s", 1, 2))
        assertEquals(listOf<String?>("文本", null), renderedTexts())
        assertEquals(2, rendered.size, "the accepted hint and its retirement, and nothing else")
        assertEquals("h", rendered.first()?.hintId, "the retirement must not restate the hint as a new one")
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
