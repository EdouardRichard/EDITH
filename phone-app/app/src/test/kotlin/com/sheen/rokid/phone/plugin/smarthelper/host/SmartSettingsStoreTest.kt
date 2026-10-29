package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.phone.storage.writeJson
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.storage.ProfileRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14a item 6: the persisted settings the smart plugin is configured from.
 *
 * The store has one job beyond storage: **never** let a missing or unreadable blob become a configuration the plugin
 * would refuse. `ConfigurationPolicy.validate` runs this configuration the next time the plugin activates, so a
 * store that threw on a corrupt blob would take the whole passive loop down instead of falling back to the ratified
 * defaults.
 */
class SmartSettingsStoreTest {

    private class Repo(private val profiles: List<ModelProfile>) : ProfileRepository {
        override fun list(): List<ModelProfile> = profiles
        override fun get(id: String): ModelProfile? = profiles.firstOrNull { it.id == id }
        override fun save(profile: ModelProfile) = profile
        override fun delete(id: String) = Unit
        override fun verify(id: String, result: CapabilityVerification) = Unit
        override fun listenChanges(listener: (String) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private fun profile(id: String) = ModelProfile(id, 7, "Synthetic $id", "Synthetic",
        "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
        object : SecretHandle { override val id = "draft-placeholder" },
        4096, 2048, 1024, 128, 5000, setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())

    @Test
    fun theRatifiedDefaultsAreTheOnesTheSpecNames() {
        val settings = SmartSettings()

        assertEquals(listOf("伊迪丝", "edith"), settings.wakeWords)
        assertEquals("L1", settings.maxLevel)
        assertTrue(settings.whitelist.isEmpty())
        assertNull("no profile is chosen until one exists", settings.profileId)
        assertEquals(3000L, settings.cooldownMillis)
    }

    @Test
    fun aSavedStoreReloadsExactlyWhatWasWritten() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        val saved = SmartSettings(
            wakeWords = listOf("伊迪丝", "edith", "小助手"),
            maxLevel = "L2",
            whitelist = listOf("open_app|navigation"),
            profileId = "p-1",
            cooldownMillis = 4500L,
        )

        store.save(saved)

        assertEquals(saved, SmartSettingsStore(blobs).load())
    }

    @Test
    fun anEmptyStoreLoadsTheDefaultsWithTheFirstAvailableProfile() {
        val store = SmartSettingsStore(TestBlobs())

        val loaded = store.load(Repo(listOf(profile("first"), profile("second"))))

        assertEquals(listOf("伊迪丝", "edith"), loaded.wakeWords)
        assertEquals("L1", loaded.maxLevel)
        assertEquals("first", loaded.profileId)
        assertEquals(3000L, loaded.cooldownMillis)
    }

    @Test
    fun aCorruptStoreLoadsTheDefaultsInsteadOfFailingValidation() {
        val blobs = TestBlobs()
        blobs.values[SmartSettingsStore.BLOB_ID] = "{ not json".toByteArray()

        val loaded = SmartSettingsStore(blobs).load(Repo(listOf(profile("only"))))

        assertEquals(SmartSettings().copy(profileId = "only"), loaded)
    }

    @Test
    fun aStoredBlobWithAProfileThatNoLongerExistsFallsBackToTheAvailableOne() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(profileId = "deleted"))

        val loaded = SmartSettingsStore(blobs).load(Repo(listOf(profile("available"))))

        assertEquals("available", loaded.profileId)
    }

    @Test
    fun anExplicitlyChosenProfileIsKeptWhenItStillExists() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(profileId = "chosen"))

        val loaded = SmartSettingsStore(blobs).load(Repo(listOf(profile("available"), profile("chosen"))))

        assertEquals("chosen", loaded.profileId)
    }

    @Test
    fun theConfigurationValuesUseTheKeyNamesThePluginKnows() {
        val settings = SmartSettings(wakeWords = listOf("伊迪丝", "edith"), maxLevel = "L1", whitelist = emptyList(),
            profileId = "p-1", cooldownMillis = 3000L)

        val values = settings.pluginValues(profile("p-1"))

        assertEquals("p-1", values["profileId"])
        assertEquals("7", values["profileRevision"])
        assertEquals("伊迪丝,edith", values["wakeWords"])
        assertEquals("L1", values["maxLevel"])
        assertEquals("", values["whitelist"])
    }

    @Test
    fun anUnknownStoredLevelFallsBackToTheShippedCeilingInsteadOfRefusingThePlugin() {
        // The plugin refuses a maxLevel it does not know, and a refused configuration takes the whole activation
        // down, so the store is where an unrecognised value has to be normalised.
        val settings = SmartSettings(maxLevel = "not-a-level")

        assertEquals("L1", settings.effectiveMaxLevel())
        assertEquals("L1", settings.pluginValues(null)["maxLevel"])
    }

    @Test
    fun aStoredLevelIsNormalisedToThePluginsOwnSpelling() {
        assertEquals("L2", SmartSettings(maxLevel = " l2 ").effectiveMaxLevel())
    }

    @Test
    fun theProactiveDefaultsAreTheOnesTheSpecNames() {
        val settings = SmartSettings()

        // Tuned on 2026-10-09 after two ambient windows measured ~1.8 admitted triggers per ten minutes against the
        // spec's ceiling of one (P3.2-FT): the shipped sensitivity is now the stricter `LOW`, which refuses a
        // transcript shorter than twelve characters (both measured false triggers were 9-12 character phrases). The
        // cooldown deliberately keeps its original 60 s - only the length threshold changed.
        assertEquals(60_000L, settings.proactiveCooldownMillis)
        assertEquals("LOW", settings.proactiveSensitivity)
        assertEquals("LOW", settings.effectiveProactiveSensitivity())
    }

    @Test
    fun aBlobWrittenBeforeTheProactiveKeysExistedStillLoadsWithTheNewDefaults() {
        val blobs = TestBlobs()
        // Exactly the shape the store wrote before P3.2: no proactive keys anywhere.
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L2")
            put("whitelist", buildJsonArray { })
            put("cooldownMillis", 4500L)
        })

        val loaded = SmartSettingsStore(blobs).load()

        assertEquals("L2", loaded.maxLevel)
        assertEquals(4500L, loaded.cooldownMillis)
        assertEquals(60_000L, loaded.proactiveCooldownMillis)
        assertEquals("LOW", loaded.proactiveSensitivity)
    }

    @Test
    fun theProactiveKeysRoundTripAndAreSentUnderTheNamesThePluginKnows() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(proactiveCooldownMillis = 300_000L, proactiveSensitivity = "high"))

        val loaded = store.load()
        assertEquals(300_000L, loaded.proactiveCooldownMillis)
        assertEquals("HIGH", loaded.effectiveProactiveSensitivity())

        val values = loaded.pluginValues(profile("p-1"))
        assertEquals("300000", values["proactiveCooldownMillis"])
        assertEquals("HIGH", values["proactiveSensitivity"])
    }

    @Test
    fun anUnrecognisedStoredSensitivityFallsBackRatherThanRefusingThePlugin() {
        // Same rule as `maxLevel`: the plugin refuses a level it does not know, and a refused configuration takes the
        // whole activation down, so the store normalises it.
        assertEquals("LOW", SmartSettings(proactiveSensitivity = "loud").effectiveProactiveSensitivity())
    }

    @Test
    fun theCooldownIsConvertedToThePluginsNanosParameter() {
        assertEquals(3_000_000_000L, SmartSettings(cooldownMillis = 3000L).cooldownNanos)
        assertEquals(0L, SmartSettings(cooldownMillis = -1L).cooldownNanos)
    }

    @Test
    fun speechIsOffByDefaultOnAFreshBlob() {
        val settings = SmartSettings()
        assertEquals(false, settings.speechEnabled)
    }

    @Test
    fun speechSurvivesASaveAndLoad() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(speechEnabled = true))
        assertEquals(true, SmartSettingsStore(blobs).load().speechEnabled)
    }

    @Test
    fun aBlobWithoutTheSpeechFieldKeepsItsDefault() {
        // Old blob written before P5: no speechEnabled key. Must load with default (false), not fail.
        val blobs = TestBlobs()
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L1")
            put("whitelist", buildJsonArray {})
            put("cooldownMillis", 3000L)
            put("proactiveCooldownMillis", 60000L)
            put("proactiveSensitivity", "LOW")
            put("proactiveEnabled", true)
        })
        val loaded = SmartSettingsStore(blobs).load()
        assertEquals(false, loaded.speechEnabled)
        // Other fields must still load correctly
        assertEquals("L1", loaded.maxLevel)
        assertEquals(true, loaded.proactiveEnabled)
    }

    // ---- 0.24 S3 (spec §5.5): continual dialogue ---------------------------------------------------

    @Test
    fun continualDialogueIsOnByDefault() {
        // The official `settings_ai_assistant_continue_dialogue` defaults to "open"; a fresh install must continue.
        assertEquals(true, SmartSettings().continueDialogue)
    }

    @Test
    fun continualDialogueSurvivesASaveAndLoadInBothDirections() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(continueDialogue = false))
        assertEquals("off must persist", false, SmartSettingsStore(blobs).load().continueDialogue)

        SmartSettingsStore(blobs).save(SmartSettings(continueDialogue = true))
        assertEquals(true, SmartSettingsStore(blobs).load().continueDialogue)
    }

    @Test
    fun aBlobWrittenBeforeTheContinueDialogueKeyExistedLoadsItAsOn() {
        // The key is new in 0.24: the official default is on, and turning it off must be the user's explicit act.
        val blobs = TestBlobs()
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L1")
            put("whitelist", buildJsonArray {})
            put("cooldownMillis", 3000L)
        })

        val loaded = SmartSettingsStore(blobs).load()

        assertEquals(true, loaded.continueDialogue)
        assertEquals("the rest of the old blob must still load", "L1", loaded.maxLevel)
    }

    @Test
    fun anExplicitlyDisabledContinueDialogueIsNotReEnabledByAnythingElseInTheBlob() {
        val blobs = TestBlobs()
        // The screen saves the whole value; every other field changing must not flip the switch back on.
        SmartSettingsStore(blobs).save(SmartSettings(continueDialogue = false, maxLevel = "L2", cooldownMillis = 5000L))

        val reloaded = SmartSettingsStore(blobs).load()

        assertEquals(false, reloaded.continueDialogue)
        assertEquals("L2", reloaded.maxLevel)
        assertEquals(5000L, reloaded.cooldownMillis)
    }

    // ---- 0.3 Phase 2 Task 10: the vision switch -------------------------------------------------

    @Test
    fun visionIsOnByDefaultInAFreshStoreAndInAFreshSettingsValue() {
        assertEquals(true, SmartSettings().visionEnabled)
        assertEquals(true, SmartSettingsStore(TestBlobs()).load().visionEnabled)
    }

    @Test
    fun anExplicitlyDisabledVisionSwitchSurvivesASaveAndLoadInBothDirections() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(visionEnabled = false))

        assertEquals(
            "an explicit off must persist; no other field may re-enable it",
            false,
            SmartSettingsStore(blobs).load().visionEnabled,
        )

        SmartSettingsStore(blobs).save(SmartSettings(visionEnabled = true))
        assertEquals(true, SmartSettingsStore(blobs).load().visionEnabled)
    }

    @Test
    fun aBlobWrittenBeforeTheVisionKeyExistedLoadsItAsOn() {
        // The key is new in Phase 2 Task 10: the default is on, and turning it off must be the user's explicit act.
        val blobs = TestBlobs()
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L1")
            put("whitelist", buildJsonArray {})
            put("cooldownMillis", 3000L)
        })

        val loaded = SmartSettingsStore(blobs).load()

        assertEquals(true, loaded.visionEnabled)
        assertEquals("the rest of the old blob must still load", "L1", loaded.maxLevel)
    }

    // ---- 0.3 Phase 4B (`D189`): the 「不拦截」 switch ----------------------------------------------

    @Test
    fun noInterceptionIsOffByDefaultInAFreshStoreAndInAFreshSettingsValue() {
        assertFalse("the mode is dangerous and must ship off", SmartSettings().noInterception)
        assertFalse(SmartSettingsStore(TestBlobs()).load().noInterception)
    }

    @Test
    fun noInterceptionSurvivesASaveAndLoadInBothDirections() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(noInterception = true))
        assertTrue("an explicit on must persist", SmartSettingsStore(blobs).load().noInterception)

        SmartSettingsStore(blobs).save(SmartSettings(noInterception = false))
        assertFalse("an explicit off must persist", SmartSettingsStore(blobs).load().noInterception)
    }

    @Test
    fun aBlobWrittenBeforeTheNoInterceptionKeyExistedLoadsItAsOff() {
        // Exactly the shape written before 4B: no noInterception key anywhere. The safe upgrade path is off - an old
        // blob must never enable unconfirmed execution by omission.
        val blobs = TestBlobs()
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L2")
            put("whitelist", buildJsonArray {})
            put("cooldownMillis", 4500L)
        })

        val loaded = SmartSettingsStore(blobs).load()

        assertFalse(loaded.noInterception)
        assertEquals("the rest of the old blob must still load", "L2", loaded.maxLevel)
    }

    @Test
    fun aCorruptNoInterceptionValueFallsBackToOffInsteadOfTruthyCoercion() {
        listOf<JsonElement>(
            JsonPrimitive("true"),
            buildJsonObject { put("unexpected", true) },
        ).forEach { corrupt ->
            val blobs = TestBlobs()
            writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
                put("version", 1)
                put("wakeWords", buildJsonArray { add("伊迪丝") })
                put("maxLevel", "L1")
                put("whitelist", buildJsonArray {})
                put("cooldownMillis", 3000L)
                put("noInterception", corrupt)
            })

            assertFalse("a corrupt value must never read as on: $corrupt",
                SmartSettingsStore(blobs).load().noInterception)
        }
    }

    @Test
    fun aNormalSaveOfAnotherFieldKeepsAnExplicitlyDisabledNoInterception() {
        val blobs = TestBlobs()
        // The screen saves the whole value. An edit to an unrelated field must not reset the switch.
        SmartSettingsStore(blobs).save(
            SmartSettings(noInterception = false, maxLevel = "L2", cooldownMillis = 5000L))

        val reloaded = SmartSettingsStore(blobs).load()

        assertFalse(reloaded.noInterception)
        assertEquals("L2", reloaded.maxLevel)
        assertEquals(5000L, reloaded.cooldownMillis)
    }

    // ---- 0.4 Task 5 (`D199`/`D202`): the host-only 「纯手机模式」 setting ---------------------------

    @Test
    fun glassesDetachDefaultsToGlassesModeWhenTheKeyIsMissing() {
        // A fresh store, and a blob written before 0.4, both mean "glasses mode" - the existing behaviour. Detaching
        // must be the user's explicit act, never an upgrade side effect.
        assertFalse("a fresh store must play through the glasses", SmartSettingsStore(TestBlobs()).load().glassesDetached)

        val blobs = TestBlobs()
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L2")
            put("whitelist", buildJsonArray {})
            put("cooldownMillis", 4500L)
            put("proactiveEnabled", true)
        })

        val loaded = SmartSettingsStore(blobs).load()

        assertFalse("a pre-0.4 blob must not come up detached", loaded.glassesDetached)
        assertEquals("the rest of the old blob must still load", "L2", loaded.maxLevel)
        assertTrue("detaching is not a way to disable the stored proactive switch", loaded.proactiveEnabled)
    }

    @Test
    fun glassesDetachSurvivesASaveAndLoadInBothDirections() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(glassesDetached = true))
        assertTrue("an explicit detach must persist", SmartSettingsStore(blobs).load().glassesDetached)

        SmartSettingsStore(blobs).save(SmartSettings(glassesDetached = false))
        assertFalse("closing detach must persist too", SmartSettingsStore(blobs).load().glassesDetached)
    }

    // ---- 0.5 (`D210`/`D211`/`D213`): the phone-only appearance and explicit-speech flag -----------------

    @Test
    fun phoneOnlyAppearanceDefaultsWhenTheKeysAreMissing() {
        // A fresh store and a pre-0.5 blob both mean the shipped preview values; nothing detaches or silences by upgrade.
        val loaded = SmartSettingsStore(TestBlobs()).load()
        assertEquals(SmartSettings.DEFAULT_BALL_OPACITY, loaded.ballOpacityPercent)
        assertEquals(SmartSettings.DEFAULT_BUBBLE_OPACITY, loaded.bubbleOpacityPercent)
        assertFalse("a fresh store has no explicit speech choice yet", loaded.speechConfigured)
        assertEquals(82, loaded.clampedBallOpacity())
        assertEquals(75, loaded.clampedBubbleOpacity())
    }

    @Test
    fun phoneOnlyAppearanceAndSpeechConfiguredRoundTrip() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(ballOpacityPercent = 40, bubbleOpacityPercent = 55, speechConfigured = true))

        val loaded = store.load()

        assertEquals(40, loaded.ballOpacityPercent)
        assertEquals(55, loaded.bubbleOpacityPercent)
        assertTrue("the explicit speech choice must persist", loaded.speechConfigured)
    }

    @Test
    fun savingAnOutOfRangeOpacityStoresTheClampedValue() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(ballOpacityPercent = 0, bubbleOpacityPercent = 1_000))

        val loaded = store.load()

        assertEquals(SmartSettings.MIN_BALL_OPACITY, loaded.ballOpacityPercent)
        assertEquals(SmartSettings.MAX_OPACITY, loaded.bubbleOpacityPercent)
        assertEquals(SmartSettings.MIN_BALL_OPACITY, loaded.clampedBallOpacity())
        assertEquals(SmartSettings.MAX_OPACITY, loaded.clampedBubbleOpacity())
    }

    @Test
    fun savingDetachKeepsEveryOtherStoredField() {
        // The gate does load -> copy(glassesDetached) -> save. A save that dropped the user's other choices would be
        // the same silent wipe `ProactiveArming.set` documents; this pins that the detach write is one field only.
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        val original = SmartSettings(
            wakeWords = listOf("伊迪丝", "edith", "小助手"),
            maxLevel = "L2",
            whitelist = listOf("open_app|navigation"),
            profileId = "p-1",
            cooldownMillis = 4500L,
            proactiveCooldownMillis = 300_000L,
            proactiveSensitivity = "HIGH",
            proactiveEnabled = false,
            speechEnabled = true,
            continueDialogue = false,
            visionEnabled = false,
            noInterception = true,
        )
        store.save(original)

        store.save(store.load().copy(glassesDetached = true))

        val reloaded = store.load()
        assertTrue(reloaded.glassesDetached)
        assertEquals("detaching must not rewrite the user's proactive choice",
            false, reloaded.proactiveEnabled)
        assertEquals(original.copy(glassesDetached = true), reloaded)
    }

    @Test
    fun aCorruptGlassesDetachValueFallsBackToGlassesMode() {
        val blobs = TestBlobs()
        writeJson(blobs, SmartSettingsStore.BLOB_ID, buildJsonObject {
            put("version", 1)
            put("wakeWords", buildJsonArray { add("伊迪丝") })
            put("maxLevel", "L1")
            put("whitelist", buildJsonArray {})
            put("cooldownMillis", 3000L)
            put("glassesDetached", JsonPrimitive("true"))
        })

        assertFalse("a string must not be coerced into a detach",
            SmartSettingsStore(blobs).load().glassesDetached)
    }

    @Test
    fun aMissingBlobIsSafeToWriteButAnUnreadableOneIsNot() {
        // The gate's write-modify-write path needs to distinguish "first install" (defaults may be saved) from "the
        // user's blob exists but cannot be read" (saving defaults would erase every other setting).
        assertNotNull("a missing blob is a first-install default, not a failure",
            SmartSettingsStore(TestBlobs()).loadForWrite())

        val corrupt = TestBlobs()
        corrupt.values[SmartSettingsStore.BLOB_ID] = "{ not json".toByteArray()
        assertNull("a corrupt blob must not be overwritten through the gate",
            SmartSettingsStore(corrupt).loadForWrite())

        val unreadable = TestBlobs().also { it.failReadId = SmartSettingsStore.BLOB_ID }
        assertNull("an unreadable blob must not be overwritten through the gate",
            SmartSettingsStore(unreadable).loadForWrite())
    }

    @Test
    fun loadForWriteStillResolvesTheStoredFields() {
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(maxLevel = "L2", proactiveEnabled = false))

        val stored = SmartSettingsStore(blobs).loadForWrite()

        assertNotNull(stored)
        assertEquals("L2", stored!!.maxLevel)
        assertFalse(stored.proactiveEnabled)
    }

    @Test
    fun anUnreadableProfileRepositoryDoesNotStopTheSettingsFromResolving() {
        // The composition reads the settings while it assembles the plugin. The profile repository can genuinely
        // refuse (`ContractError.ConfigurationRejected("Storage unavailable; reconfigure")` when the keystore cannot
        // be opened), and an exception here would take the whole composition - and with it the passive loop - down.
        // The shape must stay usable so the caller can still build an accepted configuration.
        val refusing = object : ProfileRepository {
            override fun list(): List<ModelProfile> = throw com.sheen.rokid.contracts.ContractError.ConfigurationRejected("no storage")
            override fun get(id: String): ModelProfile? = throw com.sheen.rokid.contracts.ContractError.ConfigurationRejected("no storage")
            override fun save(profile: ModelProfile) = profile
            override fun delete(id: String) = Unit
            override fun verify(id: String, result: CapabilityVerification) = Unit
            override fun listenChanges(listener: (String) -> Unit): CancellationHandle =
                object : CancellationHandle { override fun cancel() = Unit }
        }

        val settings = SmartSettings(profileId = "chosen")

        val resolved = settings.resolveProfileId(refusing)

        assertEquals("the stored choice is kept when the repository cannot answer", "chosen", resolved)
        assertTrue("the configuration shape must still validate",
            settings.pluginValues(null).getValue("profileId").isNotBlank())
    }
}
