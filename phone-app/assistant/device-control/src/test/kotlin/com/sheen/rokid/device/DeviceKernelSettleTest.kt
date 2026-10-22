package com.sheen.rokid.device

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bounded post-action settle (spec §4.4): a tap/scroll/key waits for the screen to leave the
 * pre-action shape, a type stops at the first changed observation, and "nothing changed" is only
 * concluded after the per-kind budget. These tests shrink the policy instead of sleeping for the
 * production 2,000 ms.
 */
class DeviceKernelSettleTest {

    private val accessibility = FakeAccessibilityPort()
    private val kernels = mutableListOf<DeviceKernel>()

    @AfterTest
    fun tearDown() {
        kernels.forEach { runCatching { it.close() } }
        kernels.clear()
    }

    private fun kernel(
        changeWaitMillis: Long = 500,
        heldStillMillis: Long = 40,
        pollIntervalMillis: Long = 5,
    ): DeviceKernel = DeviceKernel(
        accessibility = accessibility,
        shell = null,
        settlePolicy = SettlePolicy(
            tapChangeWaitMillis = changeWaitMillis,
            scrollChangeWaitMillis = changeWaitMillis,
            typeChangeWaitMillis = changeWaitMillis,
            keyChangeWaitMillis = changeWaitMillis,
            heldStillMillis = heldStillMillis,
            pollIntervalMillis = pollIntervalMillis,
        ),
    ).also { kernels += it }

    @Test
    fun `settle waits for a delayed screen change and reports it as applied`() {
        val kernel = kernel(changeWaitMillis = 500, heldStillMillis = 40)
        assertTrue(kernel.observe().ok)
        accessibility.resetCalls()
        var observes = 0
        accessibility.onObserve = {
            observes += 1
            // The first two looks still show the old screen; the navigation commits later.
            if (observes >= 3) {
                accessibility.screen = testObservation(listOf(testNode(text = "Wi-Fi, connected")))
            }
        }

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        assertTrue(envelope.ok, "the delayed change must be seen: ${envelope.error}")
        assertTrue(envelope.accepted)
        assertEquals(true, envelope.applied, "the observation changed after the settle")
        assertEquals("screen_change", envelope.verified?.kind)
        assertEquals(kernel.revision, envelope.verified?.revision)
        assertEquals("Wi-Fi, connected", kernel.latestObservation?.nodes?.single()?.text)
        assertTrue(observes >= 3, "the kernel must poll until the change: observes=$observes")
    }

    @Test
    fun `settle reports no effect when the screen never changes and stays bounded`() {
        val kernel = kernel(changeWaitMillis = 60, heldStillMillis = 20)
        assertTrue(kernel.observe().ok)
        accessibility.resetCalls()
        val startedAt = System.nanoTime()

        val envelope = kernel.act(ActKind.TAP, ref = Ref("#n0"))

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertFalse(envelope.ok)
        assertTrue(envelope.accepted)
        assertEquals(false, envelope.applied)
        assertEquals(DeviceErrorCodes.ACCEPTED_NO_EFFECT, envelope.error?.code)
        val observes = accessibility.calls.count { it.startsWith("observe:") }
        assertTrue(observes >= 2, "the settle must keep polling until the change wait expires: observes=$observes")
        assertTrue(elapsedMillis < 5_000, "the settle must be bounded, took $elapsedMillis ms")
        assertTrue(kernel.observe().ok, "a settled failure must leave the worker free")
    }

    @Test
    fun `a type action stops at the first changed observation`() {
        val kernel = kernel(changeWaitMillis = 500, heldStillMillis = 500)
        assertTrue(kernel.observe().ok)
        accessibility.resetCalls()
        var observes = 0
        accessibility.onObserve = {
            observes += 1
            // Every look after the change is different, so a held-still confirmation would run to
            // the 500 ms bound; the after-change mode must stop on the third look instead.
            accessibility.screen = testObservation(listOf(testNode(text = "typed-$observes")))
            if (observes < 3) accessibility.screen = testObservation()
        }
        val startedAt = System.nanoTime()

        val envelope = kernel.act(ActKind.TYPE, ref = Ref("#n1"), value = "hello")

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(envelope.ok, "typing reads at the first change: ${envelope.error}")
        assertEquals(true, envelope.applied)
        val settleObserves = accessibility.calls.count { it.startsWith("observe:") }
        assertEquals(3, settleObserves, "the after-change look must not add a held-still confirmation")
        assertTrue(elapsedMillis < 400, "the type settle must not wait for stillness, took $elapsedMillis ms")
        assertEquals("typed-3", kernel.latestObservation?.nodes?.single()?.text)
    }
}
