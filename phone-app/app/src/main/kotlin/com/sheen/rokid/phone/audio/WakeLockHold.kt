package com.sheen.rokid.phone.audio

import android.os.PowerManager

/**
 * 0.4 Task 8 (`D199`, spec §4.6): keeps the CPU awake for exactly as long as a **detached** session owns the ears.
 *
 * A detached session listens on the phone microphone with no CXR connection to keep anything alive, so when the screen
 * is off the platform is free to suspend the CPU: the foreground service keeps the process and the notification, but a
 * suspended CPU stops feeding KWS and the wake word never fires. The hold is `PARTIAL_WAKE_LOCK`, which is the platform's
 * own CPU-only lock - it deliberately does **not** turn the screen on or show anything on the lock screen, because that
 * is not part of this version's promise. The vendor SDK has no CPU-hold capability at all (official FAQ covers only
 * `FLAG_KEEP_SCREEN_ON` and an un-interceptable power key), so this is the documented Android-platform fallback the
 * project rules allow, not a claim about a vendor API.
 *
 * Ownership rules, each pinned by [WakeLockHoldTest]:
 *
 * - **One platform acquire for one logical hold.** [acquire] is idempotent and non-reference-counted: the second call
 *   returns the same held state without touching `PowerManager` again, so no `acquire`/`release` imbalance can leak a
 *   lock.
 * - **Never pretend to hold.** A refused or throwing acquire returns `false`, keeps the platform's reason for the
 *   evidence line, and leaves [isHeld] false. The hold is not reported as active just because a call was attempted.
 * - **Release is idempotent and always safe.** [release] before an acquire, twice in a row, or after a `close` is a
 *   no-op; [close] is the same release so a composition can own it as a resource.
 *
 * The platform sits behind [WakeLockBridge] so a JVM test can drive these rules without an Android runtime. Production
 * builds it with [fromPowerManager]; the lock itself is [PowerManagerWakeLockBridge].
 */
class WakeLockHold(
    private val bridge: WakeLockBridge,
) : AutoCloseable {

    /** Guards [held] and serializes the platform calls; release is also reached from the capture-failure teardown. */
    private val lock = Any()

    /** The logical hold. Only set after the platform both accepted the acquire **and** reports the lock held. */
    private var held = false

    /** The stable reason the last acquire did not produce a hold, or null. Diagnostics only - never content. */
    @Volatile private var failure: String? = null

    /**
     * Acquires the CPU lock once for this hold.
     *
     * @return true when the platform holds the lock. A refused acquisition, a throwing platform and a bridge whose
     *   `acquire` is not followed by a real hold all return false and record a reason; a later successful acquire
     *   clears it.
     */
    fun acquire(): Boolean = synchronized(lock) {
        if (held && platformHolds()) return true
        if (held) held = false
        val accepted = runCatching { bridge.acquire() }.getOrDefault(false) && platformHolds()
        if (!accepted) {
            failure = runCatching { bridge.lastFailure() }.getOrNull() ?: ACQUIRE_FAILED
            return false
        }
        held = true
        failure = null
        true
    }

    /**
     * Releases the platform lock if this hold still owns it. Idempotent, safe before [acquire], and safe to call more
     * than once from the disarm/pause/stop/close paths that all share it.
     */
    fun release() {
        synchronized(lock) {
            if (!held && !platformHolds()) return
            held = false
            // A platform release that throws must not crash a session teardown; the OS drops the process's locks with
            // the process anyway, and the next acquire starts from the same "not held" state.
            runCatching { bridge.release() }
        }
    }

    /** Whether this hold currently keeps the CPU awake. False after a refused acquire and after any release. */
    fun isHeld(): Boolean = synchronized(lock) {
        held && platformHolds()
    }

    /** The stable reason the last acquire did not produce a hold (`ACQUIRE_FAILED` when the bridge gave none). */
    fun lastFailure(): String? = failure

    override fun close() = release()

    private fun platformHolds(): Boolean =
        runCatching { bridge.isHeld() }.getOrDefault(false)

    companion object {
        /** The production wake-lock tag; also the name a `dumpsys power`/logcat device pass greps for. */
        const val DEFAULT_TAG = "edith:detached-wake"

        /** No bridge-specific reason was available for a refused acquire. */
        const val ACQUIRE_FAILED = "ACQUIRE_FAILED"

        /** The `PowerManager` service was missing, so no lock could even be created. */
        const val POWER_MANAGER_MISSING = "POWER_MANAGER_MISSING"

        /** The platform refused to create the lock object (permission/policy failure at `newWakeLock`). */
        const val LOCK_UNAVAILABLE = "LOCK_UNAVAILABLE"

        /** Builds the shipped hold over the platform's `PowerManager`. Null keeps "no service" an honest failure. */
        fun fromPowerManager(power: PowerManager?, tag: String = DEFAULT_TAG): WakeLockHold =
            WakeLockHold(PowerManagerWakeLockBridge(power, tag))
    }
}

/**
 * The platform seam behind [WakeLockHold]. Implementations must not throw out of these methods; the hold still guards
 * every call so a broken platform becomes "not held" rather than a crashed session.
 */
interface WakeLockBridge {
    /** Asks the platform for the lock. True only when the platform reports it held afterwards. */
    fun acquire(): Boolean

    /** Releases the platform lock. Must tolerate being called when it is not held. */
    fun release()

    /** The platform's own answer, used to verify an acquire and to report [WakeLockHold.isHeld]. */
    fun isHeld(): Boolean

    /** A stable reason the last [acquire] failed, or null. Never carries audio, text or any other content. */
    fun lastFailure(): String? = null
}

/**
 * The production [WakeLockBridge]: one `PowerManager.WakeLock` created with `PARTIAL_WAKE_LOCK`, non-reference-counted
 * and acquired without a timeout (the session's disarm/pause/stop/close paths own the release, spec §4.6).
 *
 * `PARTIAL_WAKE_LOCK` is the Android-official CPU-only lock and cannot light the display; that is intentional - this
 * task promises wake, not screen-on. The lock is lazily materialised once: `newWakeLock` is called here, at assembly,
 * while the acquire/release calls are cheap client-side operations.
 */
class PowerManagerWakeLockBridge(
    power: PowerManager?,
    tag: String = WakeLockHold.DEFAULT_TAG,
) : WakeLockBridge {

    private val lock: PowerManager.WakeLock?

    @Volatile private var failure: String? = null

    init {
        if (power == null) {
            lock = null
            failure = WakeLockHold.POWER_MANAGER_MISSING
        } else {
            lock = runCatching {
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply { setReferenceCounted(false) }
            }.getOrNull()
            if (lock == null) failure = WakeLockHold.LOCK_UNAVAILABLE
        }
    }

    @Synchronized override fun acquire(): Boolean {
        val wake = lock ?: return false
        if (runCatching { wake.isHeld }.getOrDefault(false)) return true
        return runCatching {
            wake.acquire()
            wake.isHeld
        }.getOrElse { throwable ->
            failure = throwable.javaClass.simpleName
            false
        }
    }

    @Synchronized override fun release() {
        val wake = lock ?: return
        if (runCatching { wake.isHeld }.getOrDefault(false)) runCatching { wake.release() }
    }

    @Synchronized override fun isHeld(): Boolean = runCatching { lock?.isHeld == true }.getOrDefault(false)

    override fun lastFailure(): String? = failure
}