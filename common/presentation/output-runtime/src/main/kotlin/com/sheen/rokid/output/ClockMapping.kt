package com.sheen.rokid.output

/**
 * A phone/glasses clock mapping established by one four-timestamp exchange.
 *
 * The handshake is `(p0, g1, g2, p3)`: the phone stamps its send `p0`, the glasses stamp their receive `g1`
 * and their send `g2`, and the phone stamps its receive `p3`. That bounds the glasses-minus-phone offset to
 * `[g2 - p3, g1 - p0]` without assuming symmetric latency.
 *
 * Phone absolute nanoseconds are never handed to the glasses. Only this mapping, identified by its `syncId`,
 * crosses the boundary, and only until it expires - a SYNC message carries the interval and a validity
 * deadline, not a raw phone instant.
 */
class ClockMapping private constructor(
    val syncId: String,
    val offsetLowerNanos: Long,
    val offsetUpperNanos: Long,
    val establishedAtPhoneNanos: Long,
    val driftMarginNanos: Long,
    /**
     * How long this mapping stays fresh, counted in its holder's own clock. The phone measures it and sends
     * it in `SYNC`; the glasses use the value as given.
     */
    private val validForNanos: Long = VALIDITY_NANOS,
) {
    /**
     * A mapping older than [validForNanos] can no longer be trusted for a fresh SHOW.
     *
     * [nowNanos] is read in the mapping holder's own clock, so this is usable on either side: the phone
     * compares its phone instants and the glasses compare their glasses instants, never each other's.
     */
    fun isExpired(nowNanos: Long): Boolean = nowNanos - establishedAtPhoneNanos >= validForNanos

    /**
     * The earliest instant, in the **glasses** clock, at which a hint must expire.
     *
     * The pessimistic end of the offset interval is used so an over-estimate of the offset can only shorten a
     * deadline, never extend it: a SHOW delayed on the wire therefore cannot refresh a deadline the glasses
     * have already passed.
     *
     * @param phoneSentNanos the phone instant the SHOW was stamped with
     * @param remainingMillis the TTL the sender still believes is left
     */
    fun earliestGlassExpiry(phoneSentNanos: Long, remainingMillis: Long,
        driftMarginNanos: Long = this.driftMarginNanos): Long {
        require(remainingMillis > 0) { "REMAINING_TTL_MUST_BE_POSITIVE" }
        require(phoneSentNanos >= 0) { "PHONE_SENT_MUST_BE_NON_NEGATIVE" }
        val remainingNanos = remainingMillis * 1_000_000
        return phoneSentNanos + remainingNanos + offsetLowerNanos - driftMarginNanos
    }

    /**
     * Interpretes a glasses-clock instant as a phone-clock instant using the pessimistic offset. Callers
     * must have checked [isExpired] first; a stale mapping must not be used to compare clocks.
     */
    fun toPhoneNanos(glassNanos: Long): Long = glassNanos - offsetLowerNanos

    companion object {
        /** A mapping is usable for at most 30 s after it was established. */
        const val VALIDITY_NANOS = 30_000_000_000L
        /** The startup allowance for the offset interval; real calibration may only tighten it. */
        const val INITIAL_MAX_WIDTH_NANOS = 100_000_000L
        /** Conservative allowance for clock drift inside one mapping's lifetime. */
        const val DEFAULT_DRIFT_MARGIN_NANOS = 5_000_000L

        fun fromExchange(syncId: String, p0: Long, g1: Long, g2: Long, p3: Long,
            driftMarginNanos: Long = DEFAULT_DRIFT_MARGIN_NANOS,
            maxWidthNanos: Long = INITIAL_MAX_WIDTH_NANOS): ClockMapping {
            require(syncId.isNotBlank()) { "SYNC_ID_REQUIRED" }
            require(driftMarginNanos >= 0) { "DRIFT_MARGIN_MUST_BE_NON_NEGATIVE" }
            require(maxWidthNanos > 0) { "MAX_WIDTH_MUST_BE_POSITIVE" }
            val lower = g2 - p3
            val upper = g1 - p0
            require(upper >= lower) { "INCONSISTENT_EXCHANGE" }
            require(upper - lower <= maxWidthNanos) { "OFFSET_INTERVAL_TOO_WIDE" }
            return ClockMapping(syncId, lower, upper,
                // Validity starts when the exchange finished, not when it began.
                establishedAtPhoneNanos = maxOf(p0, p3),
                driftMarginNanos = driftMarginNanos)
        }

        /**
         * The **glasses** side builds its mapping from the `SYNC` message instead: the phone computed the
         * offset interval from the full exchange and tells the glasses when that mapping stops being valid in
         * the glasses' own domain. The glasses therefore only needs [validForNanos] to know how long its copy
         * stays fresh - it cannot, and must not, reconstruct a phone instant.
         *
         * [establishedAtNanos] anchors the mapping in the **holder's own** clock, and it is required rather than
         * defaulted: `isExpired` measures elapsed time from that anchor, so a mapping anchored at zero reads as
         * already expired on any device whose monotonic clock is older than [validForNanos] - true of every real
         * phone and glasses (found on device 2026-10-08, where every `SHOW` was refused as "mapping expired"
         * while the tests stayed green because their fake clock starts at zero).
         */
        fun fromSync(syncId: String, offsetLowerNanos: Long, offsetUpperNanos: Long,
            establishedAtNanos: Long, validForNanos: Long,
            driftMarginNanos: Long = DEFAULT_DRIFT_MARGIN_NANOS,
            maxWidthNanos: Long = INITIAL_MAX_WIDTH_NANOS): ClockMapping {
            require(syncId.isNotBlank()) { "SYNC_ID_REQUIRED" }
            require(offsetUpperNanos >= offsetLowerNanos) { "INCONSISTENT_OFFSET_INTERVAL" }
            require(validForNanos > 0) { "VALIDITY_MUST_BE_POSITIVE" }
            require(establishedAtNanos >= 0) { "ESTABLISHED_AT_MUST_BE_NON_NEGATIVE" }
            require(offsetUpperNanos - offsetLowerNanos <= maxWidthNanos) { "OFFSET_INTERVAL_TOO_WIDE" }
            require(driftMarginNanos >= 0) { "DRIFT_MARGIN_MUST_BE_NON_NEGATIVE" }
            return ClockMapping(syncId, offsetLowerNanos, offsetUpperNanos,
                establishedAtPhoneNanos = establishedAtNanos, driftMarginNanos = driftMarginNanos,
                validForNanos = validForNanos)
        }
    }
}
