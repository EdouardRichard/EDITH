package com.sheen.rokid.phone.plugin.smarthelper.host.ui
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14b: the settings **form** the user's text is parsed through.
 *
 * The form is the only place text becomes a [SmartSettings]. Two properties matter more than any individual rule:
 * it reports **every** rejection (a partially wrong form must not lose the fields that were right), and it never
 * writes an unvalidated value through - a bad wake word never reaches the store's word set, and an unknown level
 * never reaches the plugin's risk gate.
 */
class SmartSettingsFormTest {

    private fun settings(
        wakeWords: List<String> = listOf("伊迪丝", "edith"),
        maxLevel: String = "L1",
        whitelist: List<String> = emptyList(),
        profileId: String? = null,
        cooldownMillis: Long = 3000L,
        proactiveCooldownMillis: Long = 60_000L,
        proactiveSensitivity: String = "LOW",
        proactiveEnabled: Boolean = true,
        continueDialogue: Boolean = true,
        visionEnabled: Boolean = true,
        noInterception: Boolean = false,
    ) = SmartSettings(wakeWords, maxLevel, whitelist, profileId, cooldownMillis,
        proactiveCooldownMillis, proactiveSensitivity, proactiveEnabled, continueDialogue = continueDialogue,
        visionEnabled = visionEnabled, noInterception = noInterception)

    // ---- 0.3 Phase 4B (`D189`): the 「不拦截」 switch ------------------------------------------------

    @Test
    fun theNoInterceptionSwitchRoundTripsThroughTheFormInBothDirections() {
        val base = settings(noInterception = false)

        val draft = SmartSettingsForm.fromSettings(base)
        assertFalse("the screen must show the stored off switch", draft.noInterception)

        val on = SmartSettingsForm.toSettings(draft.copy(noInterception = true), base)
        assertTrue("a valid switch reports no errors: ${on.errors}", on.errors.isEmpty())
        assertTrue("turning the switch on must be stored", on.settings.noInterception)

        val storedOn = settings(noInterception = true)
        val untouched = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(storedOn), storedOn)
        assertTrue("loading and saving an untouched on switch must not turn it off", untouched.settings.noInterception)
    }

    @Test
    fun aFormSaveOfAnotherFieldNeverResetsTheNoInterceptionSwitch() {
        // The form edits one page and saves the whole value: an unrelated edit must carry the persisted mode through,
        // not fall back to the Draft default (`false`).
        val base = settings(noInterception = true)

        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.fromSettings(base).copy(maxLevel = "L2"), base)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertTrue("an unrelated save must keep the mode on", result.settings.noInterception)
        assertEquals("L2", result.settings.maxLevel)
    }

    // ---- 0.3 Phase 2 Task 10: the perception-screenshot switch ---------------------------------------

    @Test
    fun theVisionSwitchRoundTripsThroughTheFormInBothDirections() {
        val base = settings(visionEnabled = true)

        val draft = SmartSettingsForm.fromSettings(base)
        assertTrue("the screen must show the stored switch", draft.visionEnabled)

        val off = SmartSettingsForm.toSettings(draft.copy(visionEnabled = false), base)
        assertTrue("a valid switch reports no errors: ${off.errors}", off.errors.isEmpty())
        assertFalse("turning the switch off must be stored", off.settings.visionEnabled)

        val storedOff = settings(visionEnabled = false)
        val untouched = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(storedOff), storedOff)
        assertFalse("loading and saving an untouched off switch must not re-enable it", untouched.settings.visionEnabled)
    }

    // ---- 0.24 S3: the continual-dialogue switch -----------------------------------------------------

    @Test
    fun theContinueDialogueSwitchRoundTripsThroughTheForm() {
        val base = settings(continueDialogue = true)

        val draft = SmartSettingsForm.fromSettings(base)
        assertTrue("the screen must show the stored switch", draft.continueDialogue)

        val off = SmartSettingsForm.toSettings(draft.copy(continueDialogue = false), base)
        assertTrue("a valid switch reports no errors: ${off.errors}", off.errors.isEmpty())
        assertFalse("turning the switch off must be stored", off.settings.continueDialogue)
    }

    @Test
    fun aFormSaveDoesNotTurnAStoredOffContinueDialogueBackOn() {
        val storedOff = settings(continueDialogue = false)

        val draft = SmartSettingsForm.fromSettings(storedOff)
        assertFalse("loading the stored value must not assume the default", draft.continueDialogue)

        val result = SmartSettingsForm.toSettings(draft, storedOff)
        assertFalse("saving an untouched form must not re-enable it", result.settings.continueDialogue)
    }

    @Test
    fun theContinueDialogueSwitchCarriesThePhoneOnlyNote() {
        // 0.5.1 (`C2`, spec §3.2): the switch and its stored value stay - glasses mode still uses them - but phone-only
        // mode never opens the post-answer round. The page must say what the user does instead: long-press the ball.
        assertEquals(
            "纯手机模式下不生效；下一句长按悬浮球",
            SmartSettingsForm.CONTINUE_DIALOGUE_PHONE_ONLY_NOTE,
        )
    }

    // ---- P3.2: the proactive cooldown and sensitivity fields ----------------------------------------

    @Test
    fun aProactiveCooldownOutsideTheAllowedRangeIsRefusedAndTheStoredValueSurvives() {
        val base = settings(proactiveCooldownMillis = 60_000L)

        listOf("4999", "600001", "abc", "", "12.5", "-1").forEach { raw ->
            val draft = SmartSettingsForm.fromSettings(base).copy(proactiveCooldown = raw)
            val result = SmartSettingsForm.toSettings(draft, base)

            assertEquals("raw=$raw", listOf("主动辅助冷却需为 5000-600000 毫秒的整数，未保存"), result.errors)
            assertEquals("raw=$raw", 60_000L, result.settings.proactiveCooldownMillis)
        }
    }

    @Test
    fun aProactiveCooldownAtEitherBoundIsAcceptedAndRoundTrips() {
        listOf(5_000L, 600_000L).forEach { millis ->
            val base = settings(proactiveCooldownMillis = millis)
            val result = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(base), base)

            assertTrue("millis=$millis errors=${result.errors}", result.errors.isEmpty())
            assertEquals(millis, result.settings.proactiveCooldownMillis)
            assertEquals("$millis", SmartSettingsForm.fromSettings(base).proactiveCooldown)
        }
    }

    @Test
    fun anUnknownSensitivityIsRefusedAndTheStoredLevelSurvives() {
        val base = settings(proactiveSensitivity = "HIGH")

        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.fromSettings(base).copy(proactiveSensitivity = "LOUD"), base)

        assertEquals(listOf("敏感度「LOUD」不是有效档位，请选择 LOW/MEDIUM/HIGH"), result.errors)
        assertEquals("a refused level must not be stored", "HIGH", result.settings.proactiveSensitivity)
    }

    @Test
    fun theSensitivityIsNormalisedToThePluginsOwnSpelling() {
        val base = settings(proactiveSensitivity = "low")

        assertEquals("LOW", SmartSettingsForm.fromSettings(base).proactiveSensitivity)
        val result = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(base), base)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals("LOW", result.settings.effectiveProactiveSensitivity())
    }

    // ---- 1. round trip ------------------------------------------------------------------------------

    @Test
    fun fromSettingsFormatsEveryFieldAndToSettingsRebuildsTheSameSettings() {
        val original = settings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L2",
            whitelist = listOf("launch|app", "note|storage"), profileId = "p-1", cooldownMillis = 2500L)

        val draft = SmartSettingsForm.fromSettings(original)
        assertEquals("伊迪丝,edith", draft.wakeWords)
        assertEquals("L2", draft.maxLevel)
        assertEquals("launch|app,note|storage", draft.whitelist)

        val result = SmartSettingsForm.toSettings(draft, base = original)

        assertTrue("a valid draft reports no errors, was ${result.errors}", result.errors.isEmpty())
        assertEquals(listOf("伊迪丝", "edith"), result.settings.wakeWords)
        assertEquals("L2", result.settings.maxLevel)
        assertEquals(listOf("launch|app", "note|storage"), result.settings.whitelist)
        // The profile selection and the cooldown are not fields of this form: they must survive untouched.
        assertEquals("p-1", result.settings.profileId)
        assertEquals(2500L, result.settings.cooldownMillis)
    }

    @Test
    fun fromSettingsAndToSettingsRoundTripAFullSettingsValue() {
        val original = settings(wakeWords = listOf("小艾"), maxLevel = "L4",
            whitelist = listOf("call|contacts"), profileId = "p-2", cooldownMillis = 1500L)

        // The form owns three of the five fields; the other two are the base's, so a real round trip starts from
        // the value being edited (which is what the screen does: it loads the stored settings as the base).
        val rebuilt = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(original), base = original).settings

        assertEquals(original, rebuilt)
    }

    // ---- 2. rejections, each on its own -------------------------------------------------------------

    @Test
    fun anEmptyWakeWordFieldIsRejectedAndKeepsTheBasesWords() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("   ", "L1", ""), base = settings(wakeWords = listOf("小艾")))

        assertEquals("a form that would clear every wake word must keep the previous ones",
            listOf("小艾"), result.settings.wakeWords)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single(), result.errors.single().contains("唤醒词"))
    }

    @Test
    fun aWakeWordLongerThanSixteenCharactersIsRejectedAndTheOtherFieldsStillApply() {
        val tooLong = "a".repeat(17)
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝,$tooLong", "L3", "launch|app"), base = settings())

        assertEquals("the valid word must survive a rejected sibling", listOf("伊迪丝"), result.settings.wakeWords)
        assertEquals("the level is a different field and must still apply", "L3", result.settings.maxLevel)
        assertEquals(listOf("launch|app"), result.settings.whitelist)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single(), result.errors.single().contains("16"))
    }

    @Test
    fun aWakeWordWithNoLetterOrCjkCharacterIsRejected() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝,1234", "L1", ""), base = settings())

        assertEquals(listOf("伊迪丝"), result.settings.wakeWords)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun anUnknownMaxLevelIsRejectedAndKeepsTheBasesLevel() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝", "L9", ""), base = settings(maxLevel = "L2"))

        assertEquals("L2", result.settings.maxLevel)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single(), result.errors.single().contains("L0"))
    }

    @Test
    fun aWhitelistEntryWithoutASeparatorIsRejected() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝", "L1", "launch|app,launch"), base = settings())

        assertEquals("only the well-formed entries may be stored", listOf("launch|app"), result.settings.whitelist)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single(), result.errors.single().contains("|"))
    }

    @Test
    fun aWhitelistEntryWithMoreThanTwoPartsIsRejectedLikeThePluginsOwnParser() {
        // `SmartAssistantPlugin.parseWhitelist` requires exactly two parts and refuses the whole configuration
        // otherwise, so `a|b|c` may never reach the store: the next activation would reject the configuration the
        // screen just saved.
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝", "L1", "launch|app|now,note|storage"), base = settings())

        assertEquals("an entry the plugin refuses must not be stored", listOf("note|storage"), result.settings.whitelist)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single(), result.errors.single().contains("launch|app|now"))
    }

    @Test
    fun aWhitelistEntryWithABlankHalfIsRejected() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝", "L1", "launch|app,|app,launch|"), base = settings())

        assertEquals(listOf("launch|app"), result.settings.whitelist)
        assertEquals(2, result.errors.size)
    }

    @Test
    fun anEmptyWhitelistIsValidAndClearsTheStoredGrants() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝", "L1", "  "), base = settings(whitelist = listOf("launch|app")))

        assertTrue("no whitelist is a legitimate answer, not a rejection", result.errors.isEmpty())
        assertTrue(result.settings.whitelist.isEmpty())
    }

    @Test
    fun everyRejectionIsReportedRatherThanStoppingAtTheFirst() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("1234", "L9", "launch"), base = settings())

        // Four, not three: the rejected word is named, the all-words-rejected case is named separately (the field
        // ends up empty either way), and the level and the whitelist are each named. The point is that the parser
        // does not stop at the first failure.
        assertEquals("every wrong field must be named, was ${result.errors}", 4, result.errors.size)
        assertTrue(result.errors.any { it.contains("L0") })
        assertTrue(result.errors.any { it.contains("|") })
        assertTrue(result.errors.any { it.contains("1234") })
    }

    // ---- 3. spelling, spacing, ordering -------------------------------------------------------------

    @Test
    fun fullWidthCommasAndSurroundingWhitespaceAreHandledAndOrderingIsPreserved() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("  小艾 ， EDITH , 伊迪丝  ", " l2 ", " launch|app , note|storage "),
            base = settings())

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals("the user's order is meaningful and must survive", listOf("小艾", "EDITH", "伊迪丝"),
            result.settings.wakeWords)
        assertEquals("L2", result.settings.maxLevel)
        assertEquals(listOf("launch|app", "note|storage"), result.settings.whitelist)
    }

    @Test
    fun aFullWidthCommaInsideTheWhitelistSeparatesEntriesLikeAHalfWidthOne() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("伊迪丝", "L1", "launch|app，note|storage"), base = settings())

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(listOf("launch|app", "note|storage"), result.settings.whitelist)
    }

    @Test
    fun wakeWordsThatDifferOnlyInCaseAreDeduplicatedKeepingTheFirstSpelling() {
        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.Draft("EDITH,edith,伊迪丝", "L1", ""), base = settings())

        assertEquals(listOf("EDITH", "伊迪丝"), result.settings.wakeWords)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun aWordOfExactlySixteenCharactersIsAccepted() {
        val word = "a".repeat(16)
        val result = SmartSettingsForm.toSettings(SmartSettingsForm.Draft(word, "L1", ""), base = settings())

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(listOf(word), result.settings.wakeWords)
    }

    @Test
    fun anAllInvalidWakeWordListKeepsThePreviousWordsWhileAnAllInvalidWhitelistClearsTheGrants() {
        // The documented asymmetry (Task 14b-fix item 7), pinned so it cannot change silently: an all-invalid
        // wake-word list keeps the previous words (the plugin requires at least one, so falling back is the only
        // usable answer), while an all-invalid whitelist stores no grants (every entry the user wrote was refused,
        // so the safe direction is fewer permissions). Both report their errors.
        val base = settings(wakeWords = listOf("小艾"), whitelist = listOf("launch|app"))

        val words = SmartSettingsForm.toSettings(SmartSettingsForm.Draft("1234", "L1", ""), base)
        val grants = SmartSettingsForm.toSettings(SmartSettingsForm.Draft("伊迪丝", "L1", "launch"), base)

        assertEquals("an empty wake-word list is not configurable, so the previous words survive",
            listOf("小艾"), words.settings.wakeWords)
        assertTrue("the rejected word is still reported", words.errors.isNotEmpty())
        assertTrue("every whitelist entry was refused, so no grant survives", grants.settings.whitelist.isEmpty())
        assertTrue("the rejected entry is still reported", grants.errors.isNotEmpty())
    }

    @Test
    fun aDraftAlwaysProducesUsableSettings() {
        val base = settings(profileId = "p-1", cooldownMillis = 7000L)

        val result = SmartSettingsForm.toSettings(SmartSettingsForm.Draft("1234", "L9", "launch"), base)

        assertNotEquals("a rejected level must not become a level the plugin refuses", "L9", result.settings.maxLevel)
        assertEquals("p-1", result.settings.profileId)
        assertEquals(7000L, result.settings.cooldownMillis)
    }

    @Test
    fun theFormKeepsThePersistedProactiveSwitch() {
        // `D53`②: 「主动辅助」 is a persisted global setting and this form does not own it, so an edit made on the
        // settings screen must carry it through rather than fall back to the field's default (`true`). A form that
        // silently re-armed a switch the user had turned off would be exactly the "auto re-open" the ruling forbids.
        val base = settings(proactiveEnabled = false)

        val result = SmartSettingsForm.toSettings(SmartSettingsForm.fromSettings(base), base)

        assertFalse("a save from the form must not re-arm it", result.settings.proactiveEnabled)
    }

    @Test
    fun theFormKeepsThePersistedGlassesDetachFlag() {
        // 0.4 Task 5: the detach switch is a host-only setting owned by `DetachGate`, not a field of this form. A save
        // of the form's own fields must still carry the persisted flag through; otherwise the next App start would
        // silently return to glasses mode (or worse, detach) because the form rebuilt a default value.
        val base = settings().copy(glassesDetached = true)

        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.fromSettings(base).copy(maxLevel = "L2"), base)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertTrue("the form must not drop the detach flag", result.settings.glassesDetached)
        assertEquals("L2", result.settings.maxLevel)
    }

    @Test
    fun theSettingsPageNamesThePhoneOnlyMode() {
        // 0.5.1 (`C1`, spec §3.1): the plan names this suite alongside `DetachUiTest` as the pin for the user-visible
        // 「纯手机模式」 copy. `DetachUiTest` owns the full copy (old names absent, consequence text); this test is the
        // settings-form suite's mode-name check, kept separate from the flag-retention test above so a rename cannot
        // pass by only preserving `glassesDetached`.
        assertEquals("纯手机模式", DetachUi.SWITCH_TITLE)
        assertTrue("the proactive note must name the same mode",
            DetachUi.PROACTIVE_DETACHED_NOTE.contains("纯手机模式"))
    }

    @Test
    fun theFormKeepsThePersistedPhoneOnlyAppearanceAndSpeechFlag() {
        // 0.5: the two opacities and the explicit-speech flag are host-only settings, not fields of this form. A save of
        // the form's own fields must carry them through; otherwise the next start would silently reset the user's look
        // (or re-open the speech default by dropping `speechConfigured`).
        val base = settings().copy(ballOpacityPercent = 41, bubbleOpacityPercent = 57, speechConfigured = true)

        val result = SmartSettingsForm.toSettings(
            SmartSettingsForm.fromSettings(base).copy(maxLevel = "L2"), base)

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(41, result.settings.ballOpacityPercent)
        assertEquals(57, result.settings.bubbleOpacityPercent)
        assertTrue("the explicit speech choice must survive a form save", result.settings.speechConfigured)
        assertEquals("L2", result.settings.maxLevel)
    }
}
