package com.sheen.rokid.agent

/**
 * The bounds and switches of one perception snapshot (edith-0.3 Phase 2, spec §7.3/§7.4; R5).
 *
 * These are policy, not model constants: the element cap, notification cap and ref TTL are configurable so a future
 * settings surface can change them without touching the perception provider or the snapshot contract. The defaults are
 * the Phase 2 values; the host and the plugin each construct the default policy.
 *
 * [includeClipboard] guards the clipboard read as a whole (a disabled clipboard is `unavailable(disabled)`, and the
 * platform is never read), [includeVision] gates the screenshot fallback, and [calendarTitleLimit] bounds how many
 * event titles one snapshot may carry.
 */
data class PerceptionPolicy(
    val elementLimit: Int = 60,
    val notificationLimit: Int = 3,
    val refTtlMillis: Long = 20_000L,
    val includeClipboard: Boolean = true,
    val includeVision: Boolean = true,
    val calendarTitleLimit: Int = 5,
)
