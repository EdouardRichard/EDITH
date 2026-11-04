package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.phone.ui.settings.AssistantBehaviorLogic
import com.sheen.rokid.phone.ui.settings.AssistantBehaviorUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 design §3.3/§4.2: the pure half of the assistant behavior page.
 *
 * The page has to prove four things that are easy to lose in Compose bookkeeping:
 *
 * - exactly four stored fields need an App restart (wake words, continue dialogue, proactive cooldown,
 *   proactive sensitivity) and the two live switches (vision, proactive arming) do not;
 * - the proactive toggle is **outside** the form save: it calls the immediate setter and never the form save;
 * - a failed save keeps the user's draft and shows the mapped error instead of writing only a log;
 * - the seconds field is a UI projection of the millisecond storage value.
 *
 * `AssistantBehaviorLogic` is the host-free state machine the composable drives, so these assertions exercise the
 * same transitions the UI runs, not a parallel reimplementation.
 */
class AssistantBehaviorTest {

    private fun settings(
        wakeWords: List<String> = listOf("伊迪丝", "edith"),
        maxLevel: String = "L1",
        whitelist: List<String> = emptyList(),
        cooldownMillis: Long = 3000L,
        proactiveCooldownMillis: Long = 60_000L,
        proactiveSensitivity: String = "LOW",
        proactiveEnabled: Boolean = true,
        continueDialogue: Boolean = true,
        visionEnabled: Boolean = true,
        noInterception: Boolean = false,
        glassesDetached: Boolean = false,
    ) = SmartSettings(
        wakeWords = wakeWords,
        maxLevel = maxLevel,
        whitelist = whitelist,
        cooldownMillis = cooldownMillis,
        proactiveCooldownMillis = proactiveCooldownMillis,
        proactiveSensitivity = proactiveSensitivity,
        proactiveEnabled = proactiveEnabled,
        continueDialogue = continueDialogue,
        visionEnabled = visionEnabled,
        noInterception = noInterception,
        glassesDetached = glassesDetached,
    )

    // ---- restart semantics ------------------------------------------------------------------------------------

    @Test
    fun `the four restart fields need a restart and vision does not`() {
        val base = settings()

        val allFour = base.copy(
            wakeWords = listOf("小艾"),
            continueDialogue = false,
            proactiveCooldownMillis = 120_000L,
            proactiveSensitivity = "HIGH",
        )
        assertEquals(4, SmartSettingsForm.needsRestartCount(base, allFour))

        val oneAtATime = listOf(
            "wake words" to base.copy(wakeWords = listOf("小艾")),
            "continue dialogue" to base.copy(continueDialogue = false),
            "proactive cooldown" to base.copy(proactiveCooldownMillis = 120_000L),
            "proactive sensitivity" to base.copy(proactiveSensitivity = "HIGH"),
        )
        oneAtATime.forEach { (name, changed) ->
            assertEquals("$name must count as one restart field", 1,
                SmartSettingsForm.needsRestartCount(base, changed))
        }

        assertEquals(
            "vision and proactive arming are live/no-restart settings",
            0,
            SmartSettingsForm.needsRestartCount(
                base,
                base.copy(visionEnabled = false, proactiveEnabled = false),
            ),
        )
    }

    @Test
    fun `saving the same values reports zero restarts`() {
        val base = settings()
        val roundTrip = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(base), base)
        assertTrue(roundTrip.errors.toString(), roundTrip.errors.isEmpty())
        assertEquals(0, SmartSettingsForm.needsRestartCount(base, roundTrip.settings))
        assertEquals("已保存，立即生效", SettingsFeedback.savedRestartText(0))

        val state = AssistantBehaviorLogic.initial(base)
        assertFalse("an untouched page is not dirty", state.dirty)
        val saved = AssistantBehaviorLogic.save(state) { SettingsSaveResult(saved = true) }
        assertEquals(0, saved.restartCount)
        assertEquals("已保存，立即生效", saved.saveMessage)
    }

    @Test
    fun `a save counts the fields that changed and clears dirty`() {
        var state = AssistantBehaviorLogic.initial(settings())

        state = AssistantBehaviorLogic.editDraft(
            state,
            state.draft.copy(
                wakeWords = "EDITH",
                continueDialogue = false,
                proactiveSensitivity = "HIGH",
            ),
        )
        state = AssistantBehaviorLogic.editCooldownSeconds(state, "120")
        assertTrue(state.dirty)

        var saved: SmartSettings? = null
        state = AssistantBehaviorLogic.save(state) { settings ->
            saved = settings
            SettingsSaveResult(saved = true)
        }

        assertEquals(4, state.restartCount)
        assertEquals("已保存 · 4 项需重启 App 生效", state.saveMessage)
        assertFalse("a successful save clears the dirty state", state.dirty)
        assertTrue(state.validationErrors.isEmpty())

        val written = requireNotNull(saved)
        assertEquals(listOf("EDITH"), written.wakeWords)
        assertFalse(written.continueDialogue)
        assertEquals("HIGH", written.proactiveSensitivity)
        assertEquals(120_000L, written.proactiveCooldownMillis)
        assertTrue("unrelated stored switches survive the page's own save", written.proactiveEnabled)
    }

    @Test
    fun `the cooldown page field is seconds while storage stays milliseconds`() {
        assertEquals(5_000L, AssistantBehaviorLogic.parseCooldownSeconds("5"))
        assertEquals(600_000L, AssistantBehaviorLogic.parseCooldownSeconds("600"))
        assertEquals(120_000L, AssistantBehaviorLogic.parseCooldownSeconds(" 120 "))
        assertNull(AssistantBehaviorLogic.parseCooldownSeconds("4"))
        assertNull(AssistantBehaviorLogic.parseCooldownSeconds("601"))
        assertNull(AssistantBehaviorLogic.parseCooldownSeconds(""))
        assertNull(AssistantBehaviorLogic.parseCooldownSeconds("abc"))

        assertEquals("60", AssistantBehaviorLogic.cooldownSecondsText(60_000L))
        assertEquals("60.5", AssistantBehaviorLogic.cooldownSecondsText(60_500L))

        var state = AssistantBehaviorLogic.initial(settings(proactiveCooldownMillis = 60_000L))
        state = AssistantBehaviorLogic.editCooldownSeconds(state, "120")
        assertTrue(state.dirty)
        state = AssistantBehaviorLogic.save(state) { SettingsSaveResult(saved = true) }
        assertEquals(120_000L, state.base.proactiveCooldownMillis)
        assertEquals(1, state.restartCount)
    }

    @Test
    fun `an out of range cooldown blocks the save and keeps the draft`() {
        var saves = 0
        var state = AssistantBehaviorLogic.initial(settings())
        state = AssistantBehaviorLogic.editDraft(state, state.draft.copy(wakeWords = "EDITH"))
        state = AssistantBehaviorLogic.editCooldownSeconds(state, "6000")
        state = AssistantBehaviorLogic.save(state) {
            saves++
            SettingsSaveResult(saved = true)
        }

        assertEquals(0, saves)
        assertEquals(listOf(AssistantBehaviorUi.COOLDOWN_SECONDS_ERROR), state.validationErrors)
        assertEquals("EDITH", state.draft.wakeWords)
        assertEquals("6000", state.cooldownSeconds)
    }

    // ---- immediate proactive arming ---------------------------------------------------------------------------

    @Test
    fun `the proactive toggle is outside the form save and immediate`() {
        var formSaves = 0
        var persistenceSaves = 0
        val toggles = mutableListOf<Boolean>()
        val initial = AssistantBehaviorLogic.initial(settings(proactiveEnabled = true))

        val state = AssistantBehaviorLogic.toggleProactive(initial, false) { enabled ->
            toggles += enabled
            persistenceSaves++
            SettingsSaveResult(saved = true)
        }

        assertEquals("the switch calls the immediate setter once", listOf(false), toggles)
        assertEquals(1, persistenceSaves)
        assertEquals("the proactive switch is not part of the form save", 0, formSaves)
        assertNull("no form save result is produced by the toggle", state.result)
        assertFalse("the toggle does not dirty the form", state.dirty)
        assertFalse(state.proactiveChecked)
        assertFalse(
            "a later form save must carry the freshly persisted arming value",
            state.base.proactiveEnabled,
        )
        assertEquals("已保存，立即生效", state.proactiveMessage)
    }

    @Test
    fun `a failed proactive toggle shows the mapped error and still moves the live switch`() {
        val failure = SettingsSaveResult(saved = false, errorCode = SettingsSaveResult.ERROR_SAVE_FAILED)
        val state = AssistantBehaviorLogic.toggleProactive(
            AssistantBehaviorLogic.initial(settings(proactiveEnabled = true)),
            false,
        ) { failure }

        assertFalse("the live switch moves because the arming gate is immediate", state.proactiveChecked)
        assertEquals(failure, state.proactiveResult)
        assertEquals(SettingsFeedback.saveFailedText(failure), state.proactiveMessage)
        assertTrue(
            "a failed persistence must not be remembered as the stored value",
            state.base.proactiveEnabled,
        )
    }

    @Test
    fun `the proactive toggle is disabled in phone only mode and the A12 reason stays in the help body`() {
        val state = AssistantBehaviorLogic.initial(settings(glassesDetached = true))
        assertFalse(state.proactiveOperable)

        val toggles = mutableListOf<Boolean>()
        val after = AssistantBehaviorLogic.toggleProactive(state, false) {
            toggles += it
            SettingsSaveResult(saved = true)
        }
        assertTrue("a disabled switch must not call the setter", toggles.isEmpty())
        assertTrue(after.proactiveChecked)

        assertTrue(
            "A12 must stay written down for the (?) popover",
            com.sheen.rokid.phone.ui.settings.HelpContent.PROACTIVE.body.contains(
                com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi.PROACTIVE_DETACHED_NOTE,
            ),
        )
    }

    // ---- failure visibility -----------------------------------------------------------------------------------

    @Test
    fun `a failed save shows the inline error and keeps the draft`() {
        val base = settings()
        val initial = AssistantBehaviorLogic.initial(base)
        val editedDraft = initial.draft.copy(wakeWords = "EDITH")
        var state = AssistantBehaviorLogic.editDraft(initial, editedDraft)
        state = AssistantBehaviorLogic.editCooldownSeconds(state, "90")

        val failure = SettingsSaveResult(
            saved = false,
            errorCode = SettingsSaveResult.ERROR_SETTINGS_UNREADABLE,
        )
        var attempted: SmartSettings? = null
        state = AssistantBehaviorLogic.save(state) { settings ->
            attempted = settings
            failure
        }

        assertNotNull("the page must really attempt the save", attempted)
        val written = requireNotNull(attempted)
        assertEquals("the parsed draft reaches the save callback", listOf("EDITH"), written.wakeWords)
        assertEquals(90_000L, written.proactiveCooldownMillis)
        assertEquals("the draft is kept exactly as edited", editedDraft, state.draft)
        assertEquals("the seconds text is kept", "90", state.cooldownSeconds)
        assertEquals(failure, state.result)
        assertEquals(SettingsFeedback.saveFailedText(failure), state.saveMessage)
        assertTrue("a failed save leaves the page dirty", state.dirty)
        assertTrue(state.validationErrors.isEmpty())
    }

    @Test
    fun `every save failure has visible Chinese copy and never claims success`() {
        listOf(
            SettingsSaveResult.ERROR_STORE_UNAVAILABLE,
            SettingsSaveResult.ERROR_SETTINGS_UNREADABLE,
            SettingsSaveResult.ERROR_SAVE_FAILED,
        ).forEach { code ->
            val text = SettingsFeedback.saveFailedText(SettingsSaveResult(saved = false, errorCode = code))
            assertNotNull("$code must be visible", text)
            assertTrue("$code must not look like success", text!!.contains("未保存") || text.contains("未覆盖"))
            assertFalse("$code must not say saved", text.contains("已保存"))
        }

        assertNull("a successful save has no failure text", SettingsFeedback.saveFailedText(SettingsSaveResult(true)))

        val unknown = SettingsFeedback.saveFailedText(SettingsSaveResult(false, "SOMETHING_ELSE"))
        assertNotNull(unknown)
        assertTrue("an unknown code is still surfaced", unknown!!.contains("SOMETHING_ELSE"))
        assertTrue(unknown.contains("保存失败"))

        val generic = SettingsFeedback.saveFailedText(SettingsSaveResult(false))
        assertNotNull(generic)
        assertTrue(generic!!.contains("保存失败"))

        assertEquals("已保存，立即生效", SettingsFeedback.savedRestartText(0))
        assertEquals("已保存，立即生效", SettingsFeedback.savedRestartText(-1))
        assertEquals("已保存 · 2 项需重启 App 生效", SettingsFeedback.savedRestartText(2))
    }

    @Test
    fun `the page copy keeps the exact restart note and discard question`() {
        assertEquals("带「需重启」的项会在完全关闭并重新打开 App 后生效。", AssistantBehaviorUi.RESTART_NOTE)
        assertEquals("需重启", AssistantBehaviorUi.RESTART_CHIP)
        assertEquals("敏感", AssistantBehaviorUi.SENSITIVE_BADGE)
        assertEquals("放弃修改？", AssistantBehaviorUi.DISCARD_TITLE)
        assertEquals("放弃", AssistantBehaviorUi.DISCARD_CONFIRM)
        assertEquals("继续编辑", AssistantBehaviorUi.DISCARD_KEEP)
        assertFalse("the retired term must not appear", AssistantBehaviorUi.RESTART_NOTE.contains("档案"))
    }

    // ---- ProactiveArming now reports persistence --------------------------------------------------------------

    @Test
    fun `the proactive switch reports whether the change was persisted`() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)

        val saved = ProactiveArming(store).set(false)
        assertTrue(saved.saved)
        assertNull(saved.errorCode)
        assertFalse(store.load().proactiveEnabled)

        blobs.failReadId = SmartSettingsStore.BLOB_ID
        val unreadable = ProactiveArming(SmartSettingsStore(blobs)).set(true)
        assertFalse("an unreadable blob must not be overwritten", unreadable.saved)
        assertEquals(SettingsSaveResult.ERROR_SETTINGS_UNREADABLE, unreadable.errorCode)

        blobs.failReadId = null
        blobs.failId = SmartSettingsStore.BLOB_ID
        val refused = ProactiveArming(SmartSettingsStore(blobs)).set(false)
        assertFalse(refused.saved)
        assertEquals(SettingsSaveResult.ERROR_SAVE_FAILED, refused.errorCode)
        assertFalse("a refused write keeps the stored value", store.load().proactiveEnabled)

        val memoryOnly = ProactiveArming()
        val noStore = memoryOnly.set(false)
        assertFalse(noStore.saved)
        assertEquals(SettingsSaveResult.ERROR_STORE_UNAVAILABLE, noStore.errorCode)
        assertFalse(memoryOnly.enabled.value)
    }
}
