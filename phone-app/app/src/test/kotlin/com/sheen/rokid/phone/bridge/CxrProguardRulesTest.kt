package com.sheen.rokid.phone.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CXR native bridges resolve their Java peers by name through JNI. The vendor `client-l` proguard.txt
 * keeps only `Caps.{<init>,write,serialize,size}`, and `cxr-service-bridge` ships no proguard.txt at all, so
 * without the project keep rules R8 strips the reflective surface and every session creation aborts the
 * process in native code:
 *
 *   NoSuchMethodError: no non-static method "Lcom/rokid/cxr/Caps;.at(I)Lcom/rokid/cxr/Caps$Value;"
 *   ClassNotFoundException: Didn't find class "com.rokid.cxr.CXRSocketProtocol"
 *
 * Both were observed on the K40 as `SIGABRT` in `JNI_OnLoad`. Breadth measured at checkpoint34: keeping the
 * package costs 32,709 APK bytes (0.021%) and one extra DEX class, while not keeping it removes 62
 * `com.rokid.cxr` symbols - a deterministic crash for a negligible saving, so the broad keep stays.
 *
 * This test fails if the rules are deleted, which is the failure mode that has no other guard.
 */
class CxrProguardRulesTest {
    @Test fun theCxrReflectiveBridgeSurfaceStaysKept() {
        val rules = File("proguard-rules.pro")
        assertTrue("expected ${rules.absolutePath}; tests run with the module as working dir", rules.isFile)
        val text = rules.readText()
        assertTrue(
            "com.rokid.cxr must stay kept: R8 otherwise strips Caps.at(I) and CXRSocketProtocol, and the " +
                "native JNI_OnLoad aborts the process",
            text.contains("-keep class com.rokid.cxr.** { *; }"))
        assertTrue(
            "com.rokid.sprite.aiapp.externalapp carries the AIDL callbacks the bridge resolves by name",
            text.contains("-keep class com.rokid.sprite.aiapp.externalapp.** { *; }"))
    }

    @Test fun theKeepRulesAreActiveRatherThanCommentedOutForAMeasurement() {
        val active = File("proguard-rules.pro").readLines()
            .map { it.trim() }
            .filter { it.startsWith("-keep class com.rokid.") }
        assertTrue("both CXR keep rules must be active, found: $active", active.size >= 2)
        assertTrue("no CXR keep rule may be commented out", active.none { it.startsWith("#") })
    }
}
