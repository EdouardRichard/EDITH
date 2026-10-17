package com.sheen.rokid.phone
import com.sheen.rokid.phone.debug.DebugHintInjector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug hint injector's payload rule (P3.2 `P3.2-CLIP`).
 *
 * Two payloads exist for two different questions. The Stage 0 capacity probe asked "how many characters fit?" and needs
 * a payload of a **known length**, which is the `测` filler. Judging the auto-scroll - and legibility at wearing
 * distance - needs something a person can actually read, which is why a caller-supplied text is now accepted: the
 * owner's verdict on the filler was "都是测测测，一样的字，辨识度不搞".
 *
 * Either way the payload is bounded by the wire's own limit: asking for more would only measure a refusal.
 */
class DebugHintInjectorTest {

    @Test
    fun aSuppliedTextIsUsedVerbatimSoTheWearerHasSomethingToRead() {
        assertEquals("明天上午九点我去体检，记得空腹。", DebugHintInjector.payload("明天上午九点我去体检，记得空腹。", chars = 0))
    }

    @Test
    fun aSuppliedTextWinsOverTheFillerLength() {
        assertEquals("读这一句", DebugHintInjector.payload("读这一句", chars = 120))
    }

    @Test
    fun withoutTextTheFillerKeepsTheStage0ProbeWorking() {
        assertEquals("测测测", DebugHintInjector.payload(null, chars = 3))
        assertEquals("测", DebugHintInjector.payload("", chars = 1))
        assertEquals("测", DebugHintInjector.payload("   ", chars = 1))
    }

    @Test
    fun neitherPayloadEverExceedsTheWireBound() {
        assertEquals(DebugHintInjector.MAX_CHARS, DebugHintInjector.payload(null, chars = 500).length)
        assertEquals(DebugHintInjector.MAX_CHARS, DebugHintInjector.payload("字".repeat(2000), chars = 0).length)
    }

    /**
     * The instrument's bound must be the **wire's** bound, or the probe measures its own cap and reads it as the
     * chain's. That is not hypothetical: `2026-10-09-p3-2-hint-capacity.md:36` records "探针测不到 120 以上，因为
     * >120 会被我们自己的线缆拒绝" - the "线缆" there was this constant, so every value above 120 was refused by
     * the instrument before the real policy or the glasses ever saw it.
     */
    @Test
    fun theInjectorsBoundIsTheWiresBoundSoTheProbeCanReachTheRealLimit() {
        assertEquals(com.sheen.rokid.output.HintWireCodec.MAX_TEXT_CODEPOINTS, DebugHintInjector.MAX_CHARS)
    }

    @Test
    fun nothingAskedForMeansNothingInjected() {
        assertEquals("", DebugHintInjector.payload(null, chars = 0))
        assertEquals("", DebugHintInjector.payload(null, chars = -5))
    }
}
