package com.sheen.rokid.phone.ui
import com.sheen.rokid.phone.app.HostCompositionRoot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin picker, after `D57` shelved P4 (the interview assistant) for this version.
 *
 * `D57` keeps the **module and its registration** — so P4 can resume without re-creating anything — but takes the
 * plugin out of the **picker**: offering a selectable plugin that cannot activate is the "looks usable, is not" entry
 * this project keeps deleting (the same reasoning that removed `ProactiveSkip.GATE` and `SHORT`). The registry route
 * stays legal (`HostCompositionRoot` still accepts the id, and its refusal test still applies), which is why this
 * pins the *options the page renders* and not `registerPlugin`.
 */
class PluginPickerOptionsTest {

    @Test
    fun theShelvedInterviewAssistantIsNotOffered() {
        val ids = pluginPickerOptions(debug = false, diagnosticHintSenderPresent = false).map { it.first }

        assertFalse("D57: 面试助手已搁置，不得出现在选择器里：$ids", "interview-assistant" in ids)
    }

    @Test
    fun theSmartAssistantIsOfferedFirstAndStillLabelled() {
        val first = pluginPickerOptions(debug = false, diagnosticHintSenderPresent = false).first()

        assertEquals("smart-assistant", first.first)
        assertEquals("智能助手", first.second)
    }

    @Test
    fun theDebugOnlyEntriesSurvive() {
        val ids = pluginPickerOptions(debug = true, diagnosticHintSenderPresent = true).map { it.first }

        assertTrue("the diagnostic plugin is a debug-only entry: $ids", "p2-diagnostic" in ids)
        assertTrue("the hint probe is debug-only and needs its sender: $ids", "p2-hint" in ids)
    }

    @Test
    fun aReleaseBuildOffersNoDebugEntries() {
        val ids = pluginPickerOptions(debug = false, diagnosticHintSenderPresent = true).map { it.first }

        assertFalse("a release build must not offer the diagnostic plugin: $ids", "p2-diagnostic" in ids)
        assertFalse("a release build must not offer the hint probe: $ids", "p2-hint" in ids)
    }
}
