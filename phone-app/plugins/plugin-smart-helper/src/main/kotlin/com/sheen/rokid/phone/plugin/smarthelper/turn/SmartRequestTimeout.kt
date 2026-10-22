package com.sheen.rokid.phone.plugin.smarthelper.turn

/**
 * How long **one model request** may take: the profile's own `timeoutMillis`, in nanos (D49).
 *
 * The turn's budget is a different number — it counts machine time and is shifted while a confirmation waits on the
 * user — so the request cap travels separately instead of doubling as the turn deadline. A `null` or non-positive
 * profile timeout means "no separate cap", which keeps the pre-D49 behaviour for a caller that has no profile.
 */
internal fun modelRequestTimeoutNanos(profileTimeoutMillis: Long?): Long =
    (profileTimeoutMillis ?: 0L).coerceAtLeast(0L) * 1_000_000L
