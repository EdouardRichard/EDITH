package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.storage.TestBlobs

import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `D53`② (2026-10-11): 「主动辅助」 is a **persisted global switch**, on by default.
 *
 * The owner's ruling, in the four properties these tests pin:
 *
 * 1. **default on** - it takes effect as soon as the App is installed;
 * 2. it **survives** a reload (and therefore an App restart / phone restart, which are the same read);
 * 3. **nothing re-arms a disarmed switch** - no load, no session start, no stop, no reconnect;
 * 4. turning it off is **persisted**, and writing it does not damage the other stored settings.
 *
 * The old model - session state, off by default, reset by the composition when the session stopped - is what this
 * replaces, and it is why `ProactiveArming.deactivate()` no longer exists.
 */
class ProactiveArmingPersistenceTest {

    private fun store() = SmartSettingsStore(TestBlobs())

    @Test
    fun theDefaultIsOnAndItSurvivesAReload() {
        val store = store()
        val arming = ProactiveArming(store)
        arming.load()

        assertTrue("D53-②: 默认开", arming.enabled.value)

        assertTrue("the persisted write must report success", arming.set(false).saved)
        val reloaded = ProactiveArming(store)
        reloaded.load()
        assertFalse("关掉后跨加载保持关闭", reloaded.enabled.value)
    }

    @Test
    fun nothingReArmsADisarmedSwitch() {
        val store = store()
        store.save(SmartSettings(proactiveEnabled = false))
        val arming = ProactiveArming(store)
        arming.load()

        assertFalse("the stored answer wins over the default", arming.enabled.value)

        arming.load()
        assertFalse("重新加载不得把它打开", arming.enabled.value)
    }

    @Test
    fun settingTheSwitchKeepsEveryOtherStoredField() {
        val store = store()
        store.save(SmartSettings(proactiveCooldownMillis = 12_000, proactiveSensitivity = "HIGH"))
        val arming = ProactiveArming(store)
        arming.load()

        assertTrue("the field-preserving write must report success", arming.set(false).saved)

        val after = store.load()
        assertEquals(12_000L, after.proactiveCooldownMillis)
        assertEquals("HIGH", after.proactiveSensitivity)
        assertFalse(after.proactiveEnabled)
    }

    @Test
    fun aSwitchWithoutAStoreStillDefaultsOnAndStillToggles() {
        // Several JVM rigs build a composition by hand and have no blob store. That must not silently mean "off", or
        // every one of those tests would be measuring a disarmed feature.
        val arming = ProactiveArming()

        assertTrue(arming.enabled.value)
        val result = arming.set(false)
        assertFalse(arming.enabled.value)
        assertFalse("a hand-built rig has nothing to persist", result.saved)
        assertEquals(SettingsSaveResult.ERROR_STORE_UNAVAILABLE, result.errorCode)
    }

    @Test
    fun anUnreadableBlobIsNotOverwrittenWithDefaultsByTheProactiveSwitch() {
        // 0.4 Task 5: the blob now also carries `glassesDetached`. The switch's write-modify-write must not build on
        // the reader's defaults, or a temporarily unreadable blob would silently wipe the detach mode (and every other
        // stored setting) while the user was only toggling 「主动辅助」.
        val blobs = TestBlobs()
        SmartSettingsStore(blobs).save(SmartSettings(glassesDetached = true, maxLevel = "L2"))
        blobs.failReadId = SmartSettingsStore.BLOB_ID

        val result = ProactiveArming(SmartSettingsStore(blobs)).set(false)
        assertFalse("an unreadable blob must not be reported as saved", result.saved)
        assertEquals(SettingsSaveResult.ERROR_SETTINGS_UNREADABLE, result.errorCode)

        blobs.failReadId = null
        val after = SmartSettingsStore(blobs).load()
        assertTrue("the detach mode must survive a failed proactive write", after.glassesDetached)
        assertEquals("and so must every other field", "L2", after.maxLevel)
    }

    @Test
    fun aRefusedWriteIsReportedInsteadOfHiddenInTheLog() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(proactiveEnabled = true))
        blobs.failId = SmartSettingsStore.BLOB_ID

        val result = ProactiveArming(store).set(false)

        assertFalse(result.saved)
        assertEquals(SettingsSaveResult.ERROR_SAVE_FAILED, result.errorCode)
        assertTrue("the refused value was not persisted", store.load().proactiveEnabled)
    }
}
