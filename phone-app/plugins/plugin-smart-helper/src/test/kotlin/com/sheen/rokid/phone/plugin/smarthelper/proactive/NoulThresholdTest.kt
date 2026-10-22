package com.sheen.rokid.phone.plugin.smarthelper.proactive
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin

import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase C: the `noul` gate's operating point is a **measured** value, and this is what keeps it one.
 *
 * The gate shipped with the default `0.5` for two phases, explicitly recorded as "not chosen" (the fine-tune spec
 * requires an operating point picked on a selection set, and there was none). The owner's 2026-10-15 ruling was to
 * produce one, and to pick **precision first**: among thresholds whose precision on the selection set reaches 0.90,
 * take the one with the greatest recall.
 *
 * This test does not re-derive the number - that lives in the evidence file - it pins the things a later edit could
 * silently break: that the constant is *some* real operating point rather than the old default, that a probability
 * between the two follows the chosen one, and that the rule it came from is recorded beside it.
 *
 * **What it does not pin** (independent review, 2026-10-15): that the *production* gate is built with the constant.
 * The call site names it explicitly (`SmartAssistantPlugin.activeGate`), but no test drives an activation far enough
 * to observe the threshold it constructed, which the plan had asked for. Recorded as a gap rather than implied by this
 * KDoc.
 */
class NoulThresholdTest {

    private class ScriptedSystemOne(private val result: SystemOneResult) : SystemOneService {
        override fun ask(state: String, question: com.sheen.rokid.plugin.SystemOneQuestion): SystemOneResult = result
        override val isAvailable = true
        override val status = SystemOneStatus.READY
        override val modelInfo: com.sheen.rokid.plugin.ModelInfo? = null
    }

    private fun state() = ProactiveGateState.of(transcript = "这个页面卡住了", foreground = "微信")

    private fun gateAt(threshold: Double) =
        NoulGate(ScriptedSystemOne(SystemOneResult.Success(SystemOneAnswer.Noul(0.5, 0.5), 1.0)), threshold)

    @Test
    fun theOperatingPointIsARealProbabilityAndNotTheOldDefault() {
        assertTrue(NoulThreshold.VALUE > 0.0 && NoulThreshold.VALUE < 1.0, "was ${NoulThreshold.VALUE}")
        assertNotEquals(0.5, NoulThreshold.VALUE, "0.5 was never chosen; leaving it in place is the defect")
    }

    @Test
    fun aProbabilityBetweenTheChosenPointAndTheOldDefaultFollowsTheChosenPoint() {
        // Whatever the number is, the gate must be using it: a probability that sits between the two must be decided
        // by the chosen point and not by the default it replaced.
        val probe = (NoulThreshold.VALUE + 0.5) / 2.0
        val under = NoulGate(ScriptedSystemOne(
            SystemOneResult.Success(SystemOneAnswer.Noul(probe, 0.5), 1.0)), threshold = NoulThreshold.VALUE)
        assertEquals(probe >= NoulThreshold.VALUE, under.shouldIntervene(state()).intervene)
        val atDefault = NoulGate(ScriptedSystemOne(
            SystemOneResult.Success(SystemOneAnswer.Noul(probe, 0.5), 1.0)), threshold = 0.5)
        assertNotEquals(probe >= NoulThreshold.VALUE, atDefault.shouldIntervene(state()).intervene,
            "the probe must sit between the two thresholds or this proves nothing")
    }

    @Test
    fun theChosenRuleIsRecordedWithTheNumber() {
        // The number is meaningless without the rule it came from: a later reader has to be able to tell a
        // precision-first operating point from a best-F1 one.
        assertTrue(NoulThreshold.CHOICE_RULE.contains("precision"), NoulThreshold.CHOICE_RULE)
    }
}
