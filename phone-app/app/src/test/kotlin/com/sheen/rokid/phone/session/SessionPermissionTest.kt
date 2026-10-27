package com.sheen.rokid.phone.session

import android.Manifest
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 6 (`D199`, spec §4.2/§4.5): the detached session needs the **microphone** permission, not Bluetooth.
 *
 * Two layers are pinned here, both pure JVM:
 *
 * - the **predicate** that decides which runtime permissions a start requests and which withheld one refuses it -
 *   detached requires `RECORD_AUDIO` and deliberately not `BLUETOOTH_*`; glasses mode keeps exactly the existing
 *   Android-12+ Bluetooth gate (and requires nothing below API 31);
 * - the **refusal code and its order** in `dispatch("edith.START")`: a detached refusal is `MIC_PERMISSION_MISSING`
 *   and it happens **before** `beforeStart`, so the foreground service (and its `microphone` FGS type) is never
 *   started for a session that has no microphone. Glasses mode keeps `BLUETOOTH_PERMISSION_DENIED`.
 *
 * The actual permission dialog is the Activity's job and is not driven here; what is tested is the decision the
 * Activity and the Service both read, so neither can disagree with the other about the mode.
 */
class SessionPermissionTest {

    // ---- the pure predicate -------------------------------------------------------------------------------------

    @Test
    fun detachedStartRequiresTheMicrophoneAndNotBluetooth() {
        val required = SessionPermissions.requiredForStart(detached = true, apiLevel = 33)

        assertTrue("detached capture is impossible without RECORD_AUDIO",
            required.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse("D199: detached mode must not ask for Bluetooth",
            required.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse(required.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue("the foreground notification is still requested where the platform requires it",
            required.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun glassesStartKeepsTheExistingBluetoothRequirement() {
        val required = SessionPermissions.requiredForStart(detached = false, apiLevel = 33)

        assertTrue(required.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(required.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertFalse("glasses mode has no phone microphone to grant", required.contains(Manifest.permission.RECORD_AUDIO))
        assertEquals("below API 31 the existing gate asks for no Bluetooth permission at all",
            emptyList<String>(), SessionPermissions.requiredForStart(detached = false, apiLevel = 30))
    }

    @Test
    fun aDetachedStartIsRefusedWithTheMicrophoneCode() {
        assertEquals(SessionPermissions.MIC_PERMISSION_MISSING,
            SessionPermissions.startRefusal(detached = true, apiLevel = 33) { it != Manifest.permission.RECORD_AUDIO })
        assertNull("a granted microphone admits the start", SessionPermissions.startRefusal(
            detached = true, apiLevel = 33) { it == Manifest.permission.RECORD_AUDIO })
        assertNull("detached mode must not depend on Bluetooth at all", SessionPermissions.startRefusal(
            detached = true, apiLevel = 33) { it == Manifest.permission.RECORD_AUDIO })
    }

    @Test
    fun aGlassesStartKeepsTheBluetoothRefusalCode() {
        assertEquals(SessionPermissions.BLUETOOTH_PERMISSION_DENIED,
            SessionPermissions.startRefusal(detached = false, apiLevel = 33) { false })
        assertEquals("one missing Bluetooth permission is enough to refuse, as before",
            SessionPermissions.BLUETOOTH_PERMISSION_DENIED,
            SessionPermissions.startRefusal(detached = false, apiLevel = 33) {
                it == Manifest.permission.BLUETOOTH_CONNECT
            })
        assertNull(SessionPermissions.startRefusal(detached = false, apiLevel = 33) { true })
        assertNull("below API 31 the gate admits regardless, which is the existing behavior",
            SessionPermissions.startRefusal(detached = false, apiLevel = 30) { false })
    }

    // ---- dispatch: code, and the order before beforeStart --------------------------------------------------------

    private fun detachedGate(detached: Boolean): DetachGate {
        val store = SmartSettingsStore(TestBlobs())
        store.save(SmartSettings(glassesDetached = detached))
        return DetachGate(store).also { it.load() }
    }

    private fun root(detached: Boolean): HostCompositionRoot {
        val model = object : ModelClient {
            override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
                error("not used")
        }
        return HostCompositionRoot(
            host = HostRuntime(1),
            profiles = MemoryProfileRepository(),
            model = model,
            environment = null,
            detachGate = detachedGate(detached),
        )
    }

    @Test
    fun dispatchRefusesADetachedStartWithTheMicrophoneCodeBeforeBeforeStart() {
        val root = root(detached = true)
        try {
            val token = root.authorizeStart("smart-assistant")
            var beforeStartRan = false

            val admitted = root.dispatch("edith.START", token, permissionGranted = false,
                beforeStart = { beforeStartRan = true })

            assertFalse(admitted)
            assertEquals("a detached refusal names the phone microphone, not Bluetooth",
                "MIC_PERMISSION_MISSING", root.state.value.errorCode)
            assertFalse("no foreground service may start before the permission result", beforeStartRan)
        } finally {
            root.close()
        }
    }

    @Test
    fun dispatchKeepsTheBluetoothRefusalForGlassesMode() {
        val root = root(detached = false)
        try {
            val token = root.authorizeStart("smart-assistant")
            var beforeStartRan = false

            val admitted = root.dispatch("edith.START", token, permissionGranted = false,
                beforeStart = { beforeStartRan = true })

            assertFalse(admitted)
            assertEquals("BLUETOOTH_PERMISSION_DENIED", root.state.value.errorCode)
            assertFalse(beforeStartRan)
        } finally {
            root.close()
        }
    }
}
