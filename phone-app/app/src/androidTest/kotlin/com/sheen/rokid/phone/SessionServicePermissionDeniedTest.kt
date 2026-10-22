package com.sheen.rokid.phone
import com.sheen.rokid.phone.session.SessionService

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.SessionState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

// Run this class separately after revoking CONNECT and SCAN; there is no grant rule.
@RunWith(AndroidJUnit4::class)
class SessionServicePermissionDeniedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as FakePhoneApplication

    @Before fun requireRevokedBluetoothPermissions() {
        assertTrue("permission-denial scenario requires Android 12/API 31 or later", Build.VERSION.SDK_INT >= 31)
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            assertEquals("revoke $permission before running this separate class", PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(context, permission))
        }
        context.stopService(Intent(context, SessionService::class.java))
        instrumentation.waitForIdleSync()
        app.composition.stop()
        app.inputEpochs.clear()
        app.stoppedEpochs.clear()
    }

    @After fun cleanup() {
        context.stopService(Intent(context, SessionService::class.java))
        instrumentation.waitForIdleSync()
        app.composition.stop()
    }

    @Test fun deniedBluetoothStartThroughAndroidServiceLeavesNoInputOrForegroundSession() {
        ForegroundTestActivity.launch().use { scenario ->
            scenario.onActivity { activity ->
                val token = app.composition.authorizeStart("p2-diagnostic")
                activity.startService(Intent(activity, SessionService::class.java)
                    .setAction(SessionService.START).putExtra("edith.explicitStart", token))
            }
            await { app.composition.state.value.errorCode == "BLUETOOTH_PERMISSION_DENIED" }
            assertEquals(SessionState.Idle, app.composition.state.value.session)
            assertTrue("denied START must not start InputPort", app.inputEpochs.isEmpty())
            assertNull(app.composition.host.currentInputEpoch)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val activities = context.getSystemService(ActivityManager::class.java)
            await {
                notifications.activeNotifications.isEmpty() &&
                    activities.getRunningServices(Int.MAX_VALUE).none { it.service.className == SessionService::class.java.name }
            }
            assertTrue(app.inputEpochs.isEmpty())
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("expected denied service outcome within 3 seconds", condition())
    }
}
