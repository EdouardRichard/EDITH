package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.5.1 C12/C14: the overlay's start/stop/revoke decisions and its published run state.
 *
 * The service itself is an Android `Service` (window creation, `AppOpsManager` watcher and a foreground notification);
 * the repository has no Robolectric/instrumentation rig for it, so the decisions it makes are extracted into
 * [PhoneOnlyStartStopPolicy] and the state it publishes into [PhoneOnlyOverlayRuntime], both of which are pure JVM. The
 * device half - a real `appops set ... deny` revoking a live overlay - stays K40/T12 (recorded there, not claimed here).
 */
class PhoneOnlyStartStopPolicyTest {

    @Before
    fun resetTheProcessWideState() {
        // The runtime is process-wide on purpose (the settings page and the service live in one process); each test
        // starts from the same stopped/no-failure state through the production transitions, not a test-only reset.
        PhoneOnlyOverlayRuntime.stopped()
        PhoneOnlyOverlayRuntime.startRequested()
    }

    @Test
    fun `start is refused without permission`() {
        val decision = PhoneOnlyStartStopPolicy.sync(
            requestedEnabled = true,
            gateEnabled = true,
            hasComposition = true,
            permissionGranted = false,
            running = false,
        )

        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Stop(
                PhoneOnlyStartStopPolicy.Reason.NO_OVERLAY_PERMISSION,
                refused = true,
            ),
            decision,
        )
        // The settings page must not read "enabled" from the grant alone: a granted permission with no live service is
        // not a running overlay.
        assertEquals(
            PhoneOnlyStartStopPolicy.Status.PermissionMissing,
            PhoneOnlyStartStopPolicy.settingsStatus(permissionGranted = false, isRunning = false, failure = null),
        )
        assertEquals(
            PhoneOnlyStartStopPolicy.Status.NotRunning,
            PhoneOnlyStartStopPolicy.settingsStatus(permissionGranted = true, isRunning = false, failure = null),
        )
    }

    @Test
    fun `revoke stops the overlay`() {
        val packageName = "com.sheen.rokid.phone"

        // The AppOps watcher fires for the platform's SYSTEM_ALERT_WINDOW op string; the policy owns that literal so a
        // future platform-string drift is caught here instead of silently never stopping the overlay.
        assertEquals("android:system_alert_window", PhoneOnlyStartStopPolicy.SYSTEM_ALERT_WINDOW_OP)
        assertEquals(
            PhoneOnlyStartStopPolicy.AppOpChange.STOP,
            PhoneOnlyStartStopPolicy.onAppOpChanged(
                op = PhoneOnlyStartStopPolicy.SYSTEM_ALERT_WINDOW_OP,
                changedPackage = packageName,
                packageName = packageName,
                permissionGranted = false,
            ),
        )
        // A re-grant is not a stop; the next sync/resume is what starts the service again.
        assertEquals(
            PhoneOnlyStartStopPolicy.AppOpChange.IGNORE,
            PhoneOnlyStartStopPolicy.onAppOpChanged(
                op = PhoneOnlyStartStopPolicy.SYSTEM_ALERT_WINDOW_OP,
                changedPackage = packageName,
                packageName = packageName,
                permissionGranted = true,
            ),
        )
        // Only this package's op: another app's overlay grant/revoke must never stop this overlay.
        assertEquals(
            PhoneOnlyStartStopPolicy.AppOpChange.IGNORE,
            PhoneOnlyStartStopPolicy.onAppOpChanged(
                op = PhoneOnlyStartStopPolicy.SYSTEM_ALERT_WINDOW_OP,
                changedPackage = "com.example.other",
                packageName = packageName,
                permissionGranted = false,
            ),
        )
        // And only the overlay op: a notification/microphone app-op change is not a permission revoke here.
        assertEquals(
            PhoneOnlyStartStopPolicy.AppOpChange.IGNORE,
            PhoneOnlyStartStopPolicy.onAppOpChanged(
                op = "android:post_notification",
                changedPackage = packageName,
                packageName = packageName,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun `sync uses the requested value and distinguishes refused from stopped`() {
        // The 0.5 half-fix: on the off -> on transition the detach gate still holds the old value when its callback
        // runs, so `sync` was given the requested value explicitly. The request must win over the stale gate, or the
        // overlay is stopped exactly when the user enabled it.
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Start,
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = true,
                gateEnabled = false,
                hasComposition = true,
                permissionGranted = true,
                running = false,
            ),
        )

        // A normal stop is not a refusal: the mode is off, nothing was wanted.
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Stop(PhoneOnlyStartStopPolicy.Reason.MODE_OFF, refused = false),
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = false,
                gateEnabled = true,
                hasComposition = true,
                permissionGranted = true,
                running = true,
            ),
        )
        // A requested start that cannot be provided is a refusal, and the log must say `refused`, not `stopped`.
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Stop(
                PhoneOnlyStartStopPolicy.Reason.NO_OVERLAY_PERMISSION,
                refused = true,
            ),
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = null,
                gateEnabled = true,
                hasComposition = true,
                permissionGranted = false,
                running = false,
            ),
        )
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Stop(PhoneOnlyStartStopPolicy.Reason.NO_COMPOSITION, refused = true),
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = true,
                gateEnabled = false,
                hasComposition = false,
                permissionGranted = true,
                running = false,
            ),
        )
        // No explicit request -> the live gate is the truth (the onResume path).
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Stop(PhoneOnlyStartStopPolicy.Reason.MODE_OFF, refused = false),
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = null,
                gateEnabled = false,
                hasComposition = true,
                permissionGranted = true,
                running = false,
            ),
        )
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Start,
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = null,
                gateEnabled = true,
                hasComposition = true,
                permissionGranted = true,
                running = false,
            ),
        )
        // Already running and still wanted: no service churn on every resume.
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.KeepRunning,
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = null,
                gateEnabled = true,
                hasComposition = true,
                permissionGranted = true,
                running = true,
            ),
        )
        // With no composition there is no gate to read; it is a normal stop unless the caller explicitly wanted on.
        assertEquals(
            PhoneOnlyStartStopPolicy.Decision.Stop(PhoneOnlyStartStopPolicy.Reason.NO_COMPOSITION, refused = false),
            PhoneOnlyStartStopPolicy.sync(
                requestedEnabled = null,
                gateEnabled = false,
                hasComposition = false,
                permissionGranted = false,
                running = false,
            ),
        )
    }

    @Test
    fun `the settings page reports a start failure with a visible reason`() {
        assertEquals(
            PhoneOnlyStartStopPolicy.Status.Running,
            PhoneOnlyStartStopPolicy.settingsStatus(permissionGranted = true, isRunning = true, failure = null),
        )
        assertEquals(
            PhoneOnlyStartStopPolicy.Status.Failed(PhoneOnlyStartFailure.WINDOW),
            PhoneOnlyStartStopPolicy.settingsStatus(
                permissionGranted = true,
                isRunning = false,
                failure = PhoneOnlyStartFailure.WINDOW,
            ),
        )
        // The permission wins over a stale failure: the action the user needs is the system grant, not a retry.
        assertEquals(
            PhoneOnlyStartStopPolicy.Status.PermissionMissing,
            PhoneOnlyStartStopPolicy.settingsStatus(
                permissionGranted = false,
                isRunning = false,
                failure = PhoneOnlyStartFailure.WINDOW,
            ),
        )

        val texts = PhoneOnlyStartFailure.entries.map(::phoneOnlyStartFailureText)
        assertEquals("every failure needs its own honest reason", texts.size, texts.toSet().size)
        texts.forEach { text ->
            assertFalse("the failure copy is visible text, not a blank", text.isBlank())
            assertTrue("the failure copy names the overlay: $text", text.contains("悬浮球"))
        }
    }

    @Test
    fun `the running state is published only by the service lifecycle`() {
        assertFalse("nothing is running before the service says so", PhoneOnlyOverlayRuntime.isRunning.value)
        assertNull(PhoneOnlyOverlayRuntime.startFailure.value)

        PhoneOnlyOverlayRuntime.startRequested()
        assertFalse("a request is not a running overlay", PhoneOnlyOverlayRuntime.isRunning.value)

        PhoneOnlyOverlayRuntime.failed(PhoneOnlyStartFailure.WINDOW)
        assertFalse(PhoneOnlyOverlayRuntime.isRunning.value)
        assertEquals(PhoneOnlyStartFailure.WINDOW, PhoneOnlyOverlayRuntime.startFailure.value)

        PhoneOnlyOverlayRuntime.running()
        assertTrue("only the service reaching its running state sets this", PhoneOnlyOverlayRuntime.isRunning.value)
        assertNull("a successful start clears the previous failure", PhoneOnlyOverlayRuntime.startFailure.value)

        PhoneOnlyOverlayRuntime.stopped()
        assertFalse("a stopped service is not running", PhoneOnlyOverlayRuntime.isRunning.value)

        // Teardown keeps a failure visible so the settings page can still offer the retry; only a new request clears it.
        PhoneOnlyOverlayRuntime.failed(PhoneOnlyStartFailure.FOREGROUND)
        PhoneOnlyOverlayRuntime.stopped()
        assertEquals(PhoneOnlyStartFailure.FOREGROUND, PhoneOnlyOverlayRuntime.startFailure.value)
        PhoneOnlyOverlayRuntime.startRequested()
        assertNull(PhoneOnlyOverlayRuntime.startFailure.value)
    }
}
