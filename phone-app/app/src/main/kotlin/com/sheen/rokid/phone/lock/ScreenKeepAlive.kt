package com.sheen.rokid.phone.lock

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the phone screen awake for the bounded window of a tool call.
 *
 * An accessibility action needs an interactive display. The existing session foreground service keeps the process alive
 * but not the screen; this is the one Android-supported wake lock (`WAKE_LOCK`) the unlock path uses. It is released by
 * its own timeout, so a tool that never returns cannot hold the screen forever.
 */
class ScreenKeepAlive(context: Context) : AutoCloseable {

    // Lazy for the same reason as the PIN store: a context-less test double must be able to build a composition.
    private val power by lazy {
        context.applicationContext.getSystemService(PowerManager::class.java)
    }
    private val lock by lazy {
        power?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG,
        )?.apply { setReferenceCounted(false) }
    }

    fun acquire() {
        // Re-acquiring resets the timeout; the caller is a real tool call, not a periodic timer.
        runCatching { lock?.acquire(TIMEOUT_MILLIS) }
    }

    override fun close() {
        runCatching {
            val held = lock ?: return@runCatching
            if (held.isHeld) held.release()
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "edith:lock-screen-unlock"
        const val TIMEOUT_MILLIS = 120_000L
    }
}
