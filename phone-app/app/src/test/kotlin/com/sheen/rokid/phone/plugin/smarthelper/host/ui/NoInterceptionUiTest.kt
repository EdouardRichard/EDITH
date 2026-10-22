package com.sheen.rokid.phone.plugin.smarthelper.host.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3 Phase 4B (`D189`): the untestable-on-JVM half of the 「不拦截」 interaction, reduced to pure state and copy.
 *
 * The Compose switch/dialog around this cannot be exercised in this module (no Compose UI-test dependency), so the part
 * that decides **whether a second confirmation is required** and the words the user is warned with live here and are
 * pinned on the JVM. The UI itself is wiring; it is not claimed as device-tested.
 */
class NoInterceptionUiTest {

    @Test
    fun onlyTurningTheModeOnRequiresTheSecondConfirmation() {
        assertTrue("off -> on is the dangerous direction and needs the dialog",
            NoInterceptionUi.requiresSecondConfirmation(current = false, requested = true))
        assertFalse("turning it off must be immediate, with no extra gate",
            NoInterceptionUi.requiresSecondConfirmation(current = true, requested = false))
        assertFalse(NoInterceptionUi.requiresSecondConfirmation(current = true, requested = true))
        assertFalse(NoInterceptionUi.requiresSecondConfirmation(current = false, requested = false))
    }

    @Test
    fun theConfirmationCopyNamesEveryConsequenceTheUserMustAccept() {
        val copy = (listOf(NoInterceptionUi.CONFIRM_TITLE) + NoInterceptionUi.CONFIRM_LINES).joinToString("\n")

        listOf(
            "L0", "L4",                          // the whole risk range runs without confirmation
            "确认",                               // and explicitly without asking
            "关闭 App", "重启",                   // the mode survives close/kill/restart
            "手动关闭",                            // only an explicit disable exits
            "无法撤销",                            // destructive actions may not be undoable
            "STOP" /* 中文 copy uses 「急停」 */,
        ).forEach { expected ->
            val needle = if (expected == "STOP") "急停" else expected
            assertTrue("confirmation copy must mention 「$needle」, was:\n$copy", copy.contains(needle))
        }
    }

    @Test
    fun theSwitchRowCarriesTheRiskWithoutOpeningTheDialog() {
        listOf("L0–L4", "不再确认", "重启", "手动关闭", "无法撤销").forEach { expected ->
            assertTrue("the always-visible switch copy must mention 「$expected」",
                NoInterceptionUi.SWITCH_SUMMARY.contains(expected))
        }
    }
}
