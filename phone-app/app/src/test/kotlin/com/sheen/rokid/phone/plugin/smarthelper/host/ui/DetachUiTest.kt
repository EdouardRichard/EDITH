package com.sheen.rokid.phone.plugin.smarthelper.host.ui

import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5: the settings page's phone-only copy and switch state, extracted from Compose so the JVM pins the parts the owner
 * ruled on. The layout itself is a device check; these are the words and the enabled/disabled rule.
 *
 * 0.5.1 (`C1`): the user-visible name is 「纯手机模式」; the internal `DetachGate`/`glassesDetached` identifiers are
 * deliberately unchanged.
 */
class DetachUiTest {

    @Test
    fun theSwitchTitleNamesTheDetachedMode() {
        assertEquals("纯手机模式", DetachUi.SWITCH_TITLE)
        assertFalse("the old user-facing name is gone", DetachUi.SWITCH_TITLE.contains("脱离眼镜"))
        assertFalse("the 0.5 name is gone too", DetachUi.SWITCH_TITLE.contains("纯手机模型"))
    }

    @Test
    fun theExplanationStatesEveryConsequenceTheOwnerNamed() {
        // D199/D202/D53/D210: no glasses/CXR, the floating ball is the interaction surface, proactive inactive, not an
        // upload consent, a running session is stopped, and the change takes effect on the next start.
        listOf("不连接眼镜", "悬浮球", "主动辅助", "不是上传授权", "先停止", "下次开始会话").forEach { phrase ->
            assertTrue("the phone-only explanation must state '$phrase'", DetachUi.SWITCH_SUMMARY.contains(phrase))
        }
    }

    @Test
    fun theProactiveSwitchIsDisabledOnlyWhileDetached() {
        assertTrue("glasses mode keeps the proactive switch usable", DetachUi.proactiveSwitchEnabled(false))
        assertFalse("the phone-only mode disables it", DetachUi.proactiveSwitchEnabled(true))
        assertTrue("and the annotation names the mode", DetachUi.PROACTIVE_DETACHED_NOTE.contains("纯手机模式下不生效"))
        assertFalse("the annotation does not keep the 0.5 name",
            DetachUi.PROACTIVE_DETACHED_NOTE.contains("纯手机模型"))
        assertTrue("and says the persisted value is unchanged",
            DetachUi.PROACTIVE_DETACHED_NOTE.contains("持久值不变"))
    }

    @Test
    fun aSuccessfulSaveSaysWhenItTakesEffectAndWhetherTheSessionWasStopped() {
        val idle = DetachUi.successText(stoppedSession = false)
        assertTrue(idle.contains("已保存"))
        assertTrue(idle.contains("下次开始会话"))
        assertFalse("nothing was stopped, so the notice must not claim it was", idle.contains("停止"))

        val stopped = DetachUi.successText(stoppedSession = true)
        assertTrue(stopped.contains("已先停止"))
        assertTrue(stopped.contains("下次开始会话"))
    }

    @Test
    fun everyGateFailureHasVisibleChineseCopyAndNeverClaimsSaved() {
        listOf(
            DetachGate.ERROR_STORE_UNAVAILABLE,
            DetachGate.ERROR_STOP_FAILED,
            DetachGate.ERROR_SETTINGS_UNREADABLE,
            DetachGate.ERROR_SAVE_FAILED,
        ).forEach { code ->
            val text = DetachUi.errorText(code)
            assertTrue("$code must be visible", text.isNotBlank())
            assertTrue("$code must not look like success", text.contains("未保存") || text.contains("未覆盖"))
            assertFalse("$code must not say saved", text.contains("已保存"))
        }

        val unknown = DetachUi.errorText("SOMETHING_ELSE")
        assertTrue("an unknown code is still surfaced", unknown.contains("SOMETHING_ELSE"))
        assertTrue(unknown.contains("保存失败"))
    }

    @Test
    fun aFailureAfterTheGateStoppedTheSessionDisclosesThatSideEffect() {
        // Saving can fail **after** the stop (an unreadable blob, a refused write). The change was not saved, but the
        // session is already gone; the user must not have to infer that from a bare failure sentence.
        val text = DetachUi.errorText(DetachGate.ERROR_SAVE_FAILED, stoppedSession = true)
        assertTrue(text.contains("未保存"))
        assertTrue("the stop is a real side effect and must be stated", text.contains("会话已先停止"))
        assertFalse(text.contains("已保存"))
    }
}
