package com.sheen.rokid.phone.overlay

/**
 * 0.5.1 (`C12`/`C14`/`C15`): the phone-only overlay's start/stop/revoke decisions, as pure functions.
 *
 * Why this exists: the decisions used to live inline in [PhoneOnlyOverlayService], an Android `Service` the repository
 * has no JVM/instrumentation rig for. The review found that `sync` confused a stale live gate with an explicit request
 * and that a refused start could not be told apart from a normal stop in the log; the settings page also derived
 * "enabled" from `Settings.canDrawOverlays` alone. Pulling the decisions out makes each one a JVM test
 * ([PhoneOnlyStartStopPolicyTest]) and leaves the service as the adapter that performs them. The device behaviour of
 * the adapter itself - a real `AppOpsManager` callback after `adb shell appops set ... deny` - remains K40/T12.
 */
internal object PhoneOnlyStartStopPolicy {

    /**
     * The platform's `AppOpsManager` op string for `SYSTEM_ALERT_WINDOW`.
     *
     * Pinned as a literal (verified against the platform `android.jar`) so the policy has no Android dependency and the
     * test can prove the string the watcher is keyed on; the service passes `AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW`.
     */
    const val SYSTEM_ALERT_WINDOW_OP = "android:system_alert_window"

    /** Why a start could not be provided. The settings page turns this into visible copy. */
    enum class Reason { MODE_OFF, NO_COMPOSITION, NO_OVERLAY_PERMISSION }

    /** What [sync] must do, and - for a stop - whether it was a refusal of a wanted start or a normal stop. */
    sealed interface Decision {
        /** Start (or re-start) the foreground service. */
        data object Start : Decision

        /**
         * Stop the service.
         *
         * [refused] is true when the mode wanted the overlay on but the start could not be provided (no permission, no
         * composition). The log then says `refused`, not `stopped`: a user who turned the mode on must be able to read
         * why no ball appeared. A false value is an ordinary stop (mode off).
         */
        data class Stop(val reason: Reason, val refused: Boolean) : Decision

        /** The overlay is already running and still wanted; leave it alone (no service churn on every resume). */
        data object KeepRunning : Decision
    }

    /** What an `AppOpsManager.OnOpChangedListener` callback means for the overlay. */
    enum class AppOpChange { STOP, IGNORE }

    /** What the settings page may claim about the overlay. */
    sealed interface Status {
        /** The service's windows are up; only this status reads as 「已启用」. */
        data object Running : Status

        /** The system grant is missing; the user needs the system screen, not a retry. */
        data object PermissionMissing : Status

        /** Permission is granted but this service instance never reached running. */
        data class Failed(val failure: PhoneOnlyStartFailure) : Status

        /** Permission is granted and no failure is recorded, but no service is running. */
        data object NotRunning : Status
    }

    /**
     * The one sync decision: start, stop or leave a running overlay alone.
     *
     * [requestedEnabled] is the mode transition's **explicit** request; null means the caller only has the live gate
     * (`MainActivity.onResume`). The explicit request wins over the gate because the detach gate invokes its
     * mode-changed callback **before** it publishes the new `enabled` value - reading the stale gate there stopped the
     * service exactly when the user enabled the mode (the 0.5 half-fix, completed in 0.5.1 `C15`).
     *
     * `hasComposition = false` means there is no host to draw, so the reason is [Reason.NO_COMPOSITION] even if the
     * composition also hides the gate. A `requestedEnabled = false` stop is always an ordinary stop, never a refusal.
     */
    fun sync(
        requestedEnabled: Boolean?,
        gateEnabled: Boolean,
        hasComposition: Boolean,
        permissionGranted: Boolean,
        running: Boolean,
    ): Decision {
        if (requestedEnabled == false) return Decision.Stop(Reason.MODE_OFF, refused = false)
        if (!hasComposition) return Decision.Stop(Reason.NO_COMPOSITION, refused = requestedEnabled == true)
        if (requestedEnabled == null && !gateEnabled) return Decision.Stop(Reason.MODE_OFF, refused = false)
        if (!permissionGranted) return Decision.Stop(Reason.NO_OVERLAY_PERMISSION, refused = true)
        return if (running) Decision.KeepRunning else Decision.Start
    }

    /**
     * What one AppOps callback means.
     *
     * `startWatchingMode(SYSTEM_ALERT_WINDOW, packageName, listener)` fires for both revoke and re-grant, so the
     * callback re-reads the permission: a re-grant is not a stop (the next resume/sync starts the service again), and a
     * revoke of another package's overlay must not touch this one.
     */
    fun onAppOpChanged(
        op: String,
        changedPackage: String?,
        packageName: String,
        permissionGranted: Boolean,
    ): AppOpChange {
        if (op != SYSTEM_ALERT_WINDOW_OP) return AppOpChange.IGNORE
        if (changedPackage != null && changedPackage != packageName) return AppOpChange.IGNORE
        return if (permissionGranted) AppOpChange.IGNORE else AppOpChange.STOP
    }

    /**
     * The settings page status (`C14`: it must not read 「已启用」 from `canDrawOverlays` alone).
     *
     * The permission check comes first: when the grant is missing the actionable thing is the system screen, even if a
     * stale failure is still recorded from an earlier attempt.
     */
    fun settingsStatus(
        permissionGranted: Boolean,
        isRunning: Boolean,
        failure: PhoneOnlyStartFailure?,
    ): Status = when {
        !permissionGranted -> Status.PermissionMissing
        isRunning -> Status.Running
        failure != null -> Status.Failed(failure)
        else -> Status.NotRunning
    }
}

/**
 * 0.5.1 (`C12`/`C14`): why this service instance never reached its running state.
 *
 * These are the three start failures the review named (owner reflection, window add, foreground), surfaced to the
 * settings page instead of only written to logcat. They are stable tokens: the visible copy maps them to Chinese, and
 * T12 reads them from `P5_PHONE_ONLY state=failed start_failure=...`.
 */
internal enum class PhoneOnlyStartFailure {
    /** The `ViewTreeLifecycleOwner`/`ViewTreeSavedStateRegistryOwner` install failed; Compose cannot attach. */
    LIFECYCLE_OWNER,

    /** `WindowManager.addView` refused the ball or the bubble window. */
    WINDOW,

    /** The foreground service/notification start was refused (sync's start request or the service's own promote). */
    FOREGROUND,
}

/** Short visible copy for a start failure; the retry button lives in the settings page. */
internal fun phoneOnlyStartFailureText(failure: PhoneOnlyStartFailure): String = when (failure) {
    PhoneOnlyStartFailure.LIFECYCLE_OWNER -> "悬浮球界面的生命周期挂载失败（系统组件缺失）"
    PhoneOnlyStartFailure.WINDOW -> "悬浮球窗口未能显示（系统拒绝了悬浮窗）"
    PhoneOnlyStartFailure.FOREGROUND -> "悬浮球前台服务启动失败（系统限制了后台服务）"
}
