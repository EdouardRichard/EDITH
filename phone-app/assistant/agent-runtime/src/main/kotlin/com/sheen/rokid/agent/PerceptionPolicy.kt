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
 *
 * 0.5.4 Task 13: the ref TTL is 45 s, not 20 s. One model/thinking step can take longer than 20 s, and expiring a ref
 * the model is still working with turned a valid multi-step plan into a STALE_REF round trip. The registry itself is
 * value-agnostic (the caller passes the TTL); this is the host's policy default.
 */
data class PerceptionPolicy(
    val elementLimit: Int = 120,
    val notificationLimit: Int = 3,
    val refTtlMillis: Long = 45_000L,
    val includeClipboard: Boolean = true,
    val includeVision: Boolean = true,
    val calendarTitleLimit: Int = 5,
)
