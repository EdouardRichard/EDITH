package com.sheen.rokid.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses pairing drives which application the SDK installs and launches.
 *
 * Two things must both hold: the default is **our own** rendering app (display acceptance cannot be met by the
 * vendor demo, which only logs payloads), and a run can still point the session at the audio probe - or back at the
 * demo - without editing constants. A wrong or half-applied override would make the SDK launch the wrong screen,
 * which presents as a connection failure rather than as a configuration mistake, so the refusal cases are pinned here.
 */
class CxrGlassesTargetTest {
    private fun reader(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    @Test fun theDefaultIsOurOwnGlassesRenderingApp() {
        val config = CxrGlassesTarget.configFrom(reader())
        assertEquals("com.sheen.rokid.glasses", config.glassesPackageName)
        assertEquals("com.sheen.rokid.glasses.app.GlassesActivity", config.glassesActivityName)
        assertEquals("the APK is installed by the operator, not pushed by the SDK", null, config.glassesApkPath)
        assertEquals("1.26.011-20260929-150201", config.firmware)
    }

    @Test fun anOperatorCanPointTheSessionAtTheAudioProbeInstead() {
        val config = CxrGlassesTarget.configFrom(
            reader(
                CxrGlassesTarget.TARGET_PACKAGE_PROPERTY to "edith.glasses.probe",
                CxrGlassesTarget.TARGET_ACTIVITY_PROPERTY to "edith.glasses.probe.ProbeActivity",
                CxrGlassesTarget.TARGET_APK_PROPERTY to "/sdcard/rokid/glasses-app.apk",
            ),
        )
        assertEquals("edith.glasses.probe", config.glassesPackageName)
        assertEquals("edith.glasses.probe.ProbeActivity", config.glassesActivityName)
        assertEquals("/sdcard/rokid/glasses-app.apk", config.glassesApkPath)
        // The firmware is a device fact, not part of the override.
        assertEquals("1.26.011-20260929-150201", config.firmware)
    }

    @Test fun anOverrideWithoutAnActivityIsRefusedRatherThanHalfApplied() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            CxrGlassesTarget.configFrom(reader(CxrGlassesTarget.TARGET_PACKAGE_PROPERTY to "edith.glasses.probe"))
        }
        assertTrue(thrown.message!!.contains("CXR_TARGET_ACTIVITY_REQUIRED_WITH_PACKAGE"))
    }

    @Test fun blankPropertiesAreTreatedAsUnsetRatherThanAsAnEmptyTarget() {
        // An empty package name would make the SDK throw later with a less obvious message, so it falls back instead.
        val config = CxrGlassesTarget.configFrom(
            reader(
                CxrGlassesTarget.TARGET_PACKAGE_PROPERTY to "  ",
                CxrGlassesTarget.TARGET_ACTIVITY_PROPERTY to "",
                CxrGlassesTarget.TARGET_APK_PROPERTY to "",
            ),
        )
        assertEquals("com.sheen.rokid.glasses", config.glassesPackageName)
        assertEquals(null, config.glassesApkPath)
    }

    @Test fun anOverrideMayOmitTheApkPathWhenTheTargetIsAlreadyInstalled() {
        val config = CxrGlassesTarget.configFrom(
            reader(
                CxrGlassesTarget.TARGET_PACKAGE_PROPERTY to "edith.glasses.probe",
                CxrGlassesTarget.TARGET_ACTIVITY_PROPERTY to "edith.glasses.probe.ProbeActivity",
            ),
        )
        assertEquals("edith.glasses.probe", config.glassesPackageName)
        assertEquals("an installed target needs no APK path", null, config.glassesApkPath)
    }

    @Test fun thePropertyKeysAreNamespacedSoTheyCannotCollideWithUnrelatedSettings() {
        for (key in listOf(
            CxrGlassesTarget.TARGET_PACKAGE_PROPERTY,
            CxrGlassesTarget.TARGET_ACTIVITY_PROPERTY,
            CxrGlassesTarget.TARGET_APK_PROPERTY,
        )) {
            assertTrue("key must be namespaced: $key", key.startsWith("edith.cxr."))
        }
    }

    @Test fun theLiveReaderFallsBackToTheDefaultWhenNothingIsSet() {
        // No properties are set in the test JVM, so the production entry point must yield the default pairing.
        val config = CxrGlassesTarget.config()
        assertEquals("com.sheen.rokid.glasses", config.glassesPackageName)
    }
}
