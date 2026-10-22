package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.ui.profile.TestBlobs

import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
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

        arming.set(false)
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

        arming.set(false)

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
        arming.set(false)
        assertFalse(arming.enabled.value)
    }
}
