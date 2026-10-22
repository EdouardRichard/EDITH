package com.sheen.rokid.phone.session.confirmation

/**
 * Debug-only acceptance switch for the **glasses rung** of the confirmation ladder.
 *
 * Why it exists: on the K40, stopping the glasses app ends the CXR session (`session=Interrupted`), while starting the
 * session with the glasses app absent is refused by the composition (`CapabilityMissing`). The notification,
 * notifications-disabled and no-visible-surface rows therefore cannot be reached by a real glasses removal while a
 * session is live. This switch lets the debug harness treat only the glasses answer as unavailable; the real
 * notification probe, the real page/overlay probe, the real answer path and the real audit remain in force.
 *
 * It is inert in every release build: only `DebugConfirmationSurface` (debug source set) writes it, and that receiver
 * exists only in `src/debug/AndroidManifest.xml`.
 */
internal object ConfirmationSurfaceDebug {
    @Volatile
    var suppressGlasses: Boolean = false

    /**
     * Makes the composition's phone ladder report no available surface. Used only by the debug no-surface row; the
     * notification/page probes are not called, which is what lets `CONFIRMATION_UNAVAILABLE` be exercised while the CXR
     * session stays alive. Release has no writer.
     */
    @Volatile
    var suppressPhone: Boolean = false
}
