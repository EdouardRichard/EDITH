package com.sheen.rokid.host

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's hint bound (spec §6.1/§6.2, `P3.2-CLIP`).
 *
 * The reported defect: a long notification was **refused by the phone** (`HintPolicy`), so the turn said nothing at
 * all — while the glasses have scrolled a taller hint through its window since `P3.2-CLIP` (`HintAutoScroll`). The
 * owner's ruling on 2026-10-14 is explicit: 超长通知**不能截断，要推送眼镜端滚动显示**. So the bound is a defence
 * against a document arriving on the wire, not a rejection of a sentence that needs two screens.
 */
class HintPolicyTest {

    private fun hint(text: String) =
        Assistance("h", SessionId("s"), Generation(1), "turn", 0, text, null, 1_000_000_000L)

    @Test
    fun `a long notification is not refused`() {
        // 400 codepoints is ~35 lines at the measured 12-char line: the glasses slide it, and refusing it here is the
        // bug the owner reported. Whatever the final number is, it must be well past a single window (6 lines ≈ 72).
        val policy = HintPolicy()
        assertTrue(policy.accepts(hint("字".repeat(400))), "a 400-codepoint notification must reach the glasses")
    }

    @Test
    fun `a hint far past a document is still refused`() {
        // The bound keeps its original purpose: a hint may not be used to push a document onto the glasses.
        val policy = HintPolicy()
        assertFalse(policy.accepts(hint("字".repeat(HintPolicy.DEFAULT_MAX_CODE_POINTS + 1))))
    }

    @Test
    fun `a broken surrogate pair is still refused`() {
        val policy = HintPolicy()
        assertFalse(policy.accepts(hint("好\uD83D")), "a lone high surrogate is not displayable text")
        assertFalse(policy.accepts(hint("\uDE00好")), "a lone low surrogate is not displayable text")
        assertTrue(policy.accepts(hint("🙂")), "a complete pair is fine")
    }

    @Test
    fun `the bound counts codepoints rather than UTF-16 units`() {
        // An emoji is two `Char`s; counting units would refuse half as many emoji as the bound promises.
        val policy = HintPolicy()
        val text = "🙂".repeat(HintPolicy.DEFAULT_MAX_CODE_POINTS)
        assertTrue(policy.accepts(hint(text)))
        assertTrue(text.codePointCount(0, text.length) == HintPolicy.DEFAULT_MAX_CODE_POINTS)
    }

    @Test
    fun `the phone's bound equals the wire's bound`() {
        // The phone accepts a hint and the wire has to carry it. If these two numbers drifted, the phone would accept
        // a notification and then fail to encode it - the hint would vanish between the policy and the transport,
        // which is the same class of defect as the refusal the owner reported.
        assertEquals(
            com.sheen.rokid.output.HintWireCodec.MAX_TEXT_CODEPOINTS,
            HintPolicy.DEFAULT_MAX_CODE_POINTS,
            "com.sheen.rokid.host.HintPolicy and com.sheen.rokid.output.HintWireCodec must agree on the hint bound",
        )
    }
}
