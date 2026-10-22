package com.sheen.rokid.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.3 Phase 4B (spec §5.4): a confirmation channel must be able to say whether it can put a question to the
 * wearer.
 *
 * The method is additive with a `true` default on purpose: every channel written before 4B (and every test fake that
 * genuinely can ask) keeps its exact behaviour, while a channel whose display/permission surface is gone can opt out
 * and be refused immediately instead of making the loop wait out D47's 120 s window. This test is the contract-level
 * proof of both halves.
 */
class ConfirmationContractTest {

    private fun handle() = object : CancellationHandle { override fun cancel() = Unit }

    /** A channel written against the pre-4B contract: only `present` exists, so it inherits the default. */
    private class PresentOnlyChannel : ConfirmationChannel {
        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    @Test
    fun `a channel that predates availability is available by default`() {
        assertTrue(
            PresentOnlyChannel().available(),
            "the additive default must not turn an existing channel into an unavailable one",
        )
    }

    @Test
    fun `a channel can report its surface unavailable explicitly`() {
        val channel = object : ConfirmationChannel {
            override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle = handle()
            override fun available(): Boolean = false
        }

        assertFalse(channel.available(), "an explicitly unavailable channel must not claim it can ask")
    }
}
