package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.testsupport.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PcmIngressTest {
    private val epoch = InputEpoch(SessionId("s"), Generation(0), 1)
    private val format = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic")

    @Test fun `overflow discards affected continuity and exposes worker gap`() {
        val ingress = PcmIngress(FakeClock(), 20)
        ingress.open(epoch, format)
        assertEquals(PcmOffer.ACCEPTED, ingress.offer(epoch, ByteArray(640)))
        val before = ingress.continuityId
        assertEquals(PcmOffer.OVERFLOW, ingress.offer(epoch, ByteArray(2)))
        assertEquals(0, ingress.queuedBytes)
        assertNotEquals(before, ingress.continuityId)
        assertNull(ingress.poll())
        assertEquals(GapReason.OVERFLOW, ingress.pollGap()?.reason)
    }

    @Test fun `unknown and stopped epochs never queue PCM`() {
        val ingress = PcmIngress(FakeClock())
        ingress.open(epoch, null)
        assertEquals(PcmOffer.UNKNOWN_FORMAT, ingress.offer(epoch, ByteArray(640)))
        assertNull(ingress.poll())
        ingress.open(epoch.copy(serial = 2), format)
        assertEquals(PcmOffer.STALE_EPOCH, ingress.offer(epoch, ByteArray(640)))
        ingress.close()
        assertEquals(PcmOffer.STALE_EPOCH, ingress.offer(epoch.copy(serial = 2), ByteArray(640)))
    }

    @Test fun `ingress owns a bounded copy and accounts bytes`() {
        val ingress = PcmIngress(FakeClock(), 20)
        ingress.open(epoch, format)
        val bytes = byteArrayOf(1, 2)
        ingress.offer(epoch, bytes); bytes[0] = 9
        assertEquals(2, ingress.queuedBytes)
        assertContentEquals(byteArrayOf(1, 2), ingress.poll()!!.bytes)
        assertEquals(0, ingress.queuedBytes)
        repeat(100_000) {
            assertEquals(PcmOffer.ACCEPTED, ingress.offer(epoch, ByteArray(640)))
            assertEquals(640, ingress.queuedBytes)
            assertNotNull(ingress.poll())
        }
    }

    @Test fun `concurrent producers remain bounded and reset recovers a stalled worker`() {
        val ingress = PcmIngress(FakeClock(), 20)
        ingress.open(epoch, format)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val producers = (1..4).map {
                pool.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(1000) { ingress.offer(epoch, ByteArray(160)) }
                }
            }
            start.countDown()
            producers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(ingress.queuedBytes in 0..640)
            assertNotNull(ingress.pollGap())
            while (ingress.pollGap() != null) { }
            ingress.open(epoch.copy(serial = 2), format)
            ingress.reset(GapReason.SESSION_INTERRUPTED)
            assertEquals(0, ingress.queuedBytes)
            assertEquals(GapReason.SESSION_INTERRUPTED, ingress.pollGap()?.reason)
            assertNull(ingress.pollGap())
            assertEquals(PcmOffer.ACCEPTED, ingress.offer(epoch.copy(serial = 2), ByteArray(640)))
            assertEquals(640, ingress.poll()!!.bytes.size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `synthetic replay uses production ingress and media sample timing`() {
        val clock = FakeClock()
        val ingress = PcmIngress(clock)
        val framer = PcmFramer(format)
        ingress.open(epoch, format)
        val frames = mutableListOf<PcmFrame>()
        val source = ReplaySource(clock) { e, bytes ->
            assertEquals(PcmOffer.ACCEPTED, ingress.offer(e, bytes))
            frames += framer.accept(ingress.poll()!!)
        }
        val pcm = SyntheticPcm.sine(16000, 40)
        source.replay(epoch, listOf(pcm.copyOfRange(0, 641), pcm.copyOfRange(641, 1280)), 999_000_000)
        assertEquals(2, frames.size)
        assertEquals(listOf(20_000_000L, 40_000_000L), frames.map { it.endNanos })
        assertTrue(frames.flatMap { it.samples.toList() }.any { it != 0f })
    }

    @Test fun `pending overflow remains observable through close and reopen`() {
        val ingress = PcmIngress(FakeClock(), 20)
        ingress.open(epoch, format)
        val affected = ingress.continuityId
        assertEquals(PcmOffer.OVERFLOW, ingress.offer(epoch, ByteArray(641)))
        ingress.close()
        ingress.open(epoch.copy(serial = 2), format)
        val gap = assertNotNull(ingress.pollGap())
        assertEquals(epoch, gap.epoch)
        assertEquals(affected, gap.continuityId)
        assertEquals(GapReason.OVERFLOW, gap.reason)
        assertEquals(GapReason.SESSION_INTERRUPTED, ingress.pollGap()?.reason)
        assertNull(ingress.pollGap())
    }

    @Test fun `live format replacement notifies worker before new PCM`() {
        val ingress = PcmIngress(FakeClock())
        ingress.open(epoch, format)
        val affected = ingress.continuityId
        ingress.offer(epoch, ByteArray(100))
        ingress.open(epoch, format.copy(encoding = PcmEncoding.SIGNED_BE))
        assertEquals(0, ingress.queuedBytes)
        val gap = assertNotNull(ingress.pollGap())
        assertEquals(GapReason.FORMAT_CHANGED, gap.reason)
        assertEquals(affected, gap.continuityId)
        assertNull(ingress.pollGap())
    }

    @Test fun `overflow notifications retain each affected epoch and continuity`() {
        val ingress = PcmIngress(FakeClock(), 20)
        ingress.open(epoch, format)
        val first = ingress.continuityId
        ingress.offer(epoch, ByteArray(641))
        ingress.close()
        val next = epoch.copy(serial = 2)
        ingress.open(next, format)
        val second = ingress.continuityId
        ingress.offer(next, ByteArray(641))
        val gaps = generateSequence { ingress.pollGap() }.toList()
        val overflows = gaps.filter { it.reason == GapReason.OVERFLOW }
        assertEquals(listOf(epoch, next), overflows.map { it.epoch })
        assertEquals(listOf(first, second), overflows.map { it.continuityId })
    }

    @Test fun `stalled gap consumer closes ingress at bounded notification backlog`() {
        val ingress = PcmIngress(FakeClock(), 20)
        ingress.open(epoch, format)
        repeat(64) { assertEquals(PcmOffer.OVERFLOW, ingress.offer(epoch, ByteArray(641))) }
        assertEquals(PcmOffer.STALE_EPOCH, ingress.offer(epoch, ByteArray(640)))
        assertFailsWith<ContractError.ResourceConflict> { ingress.open(epoch.copy(serial = 2), format) }
        assertEquals(64, generateSequence { ingress.pollGap() }.count())
        ingress.open(epoch.copy(serial = 2), format)
        assertEquals(PcmOffer.ACCEPTED, ingress.offer(epoch.copy(serial = 2), ByteArray(640)))
    }
}
