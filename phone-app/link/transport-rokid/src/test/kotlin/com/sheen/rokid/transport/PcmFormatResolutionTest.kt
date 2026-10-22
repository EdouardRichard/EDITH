package com.sheen.rokid.transport

import com.sheen.rokid.contracts.PcmEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pairing the product actually ships on must resolve to a format, and the reason must be legible in the id.
 *
 * This test pins a **user-approved** decision, not a controlled measurement: on 2026-10-08 the user directed that the
 * official Demo is sufficient support for the format and that no further verification tests should be run. The
 * evidence behind the resolved value is therefore
 *
 *  - the vendor's documented CXR audio contract (16 kHz, mono, 16 bit PCM) **plus** their own Demo working on this
 *    exact hardware, and
 *  - our own device measurements on this pairing: **exactly 32,000 B/s** with `bytes == 2 x samples`, both runs.
 *
 * What it is **not** is a successful controlled-vowel capture - three attempts were refused as `SPECTRUM_MISMATCH`
 * (peak about 1.6% of full scale). The `evidenceId` must say so, so a later reader cannot mistake this for a
 * laboratory verification. The previous comment in the source ("no controlled device validation has been accepted for
 * any pairing yet") was true and is deliberately replaced rather than quietly deleted.
 */
class PcmFormatResolutionTest {

    @Test fun theShippingPairingResolvesToTheDocumentedFormat() {
        val resolved = PcmFormatEvidence.resolve(FIRMWARE, SDK)

        assertNotNull("the shipping pairing must resolve so downstream audio is not format-blind", resolved)
        assertEquals(16_000, resolved!!.format.sampleRateHz)
        assertEquals(1, resolved.format.channels)
        assertEquals(16, resolved.format.bitsPerSample)
        assertEquals(PcmEncoding.SIGNED_LE, resolved.encoding)
    }

    @Test fun theDeclaredFormatMatchesWhatTheTransportActuallyReceives() {
        // The one part of this that IS measured: the stream arrives at exactly the declared byte rate, so the
        // resolved geometry must agree with MonoPcmFormat rather than restating it independently.
        val resolved = requireNotNull(PcmFormatEvidence.resolve(FIRMWARE, SDK))
        assertEquals(MonoPcmFormat.SAMPLE_RATE_HZ, resolved.format.sampleRateHz)
        assertEquals(MonoPcmFormat.CHANNELS, resolved.format.channels)
        assertEquals(MonoPcmFormat.BITS_PER_SAMPLE, resolved.format.bitsPerSample)
    }

    @Test fun theEvidenceIdNamesTheRealBasisRatherThanClaimingAControlledCapture() {
        // A future reader must be able to tell "documented + approved" from "measured in a controlled run".
        val id = requireNotNull(PcmFormatEvidence.resolve(FIRMWARE, SDK)).evidenceId.lowercase()

        assertEquals("official-spec+user-decision", id)
        listOf("controlled", "vowel", "verified-capture").forEach { claim ->
            org.junit.Assert.assertFalse(
                "the id must not claim a controlled capture it does not have: '$id'",
                id.contains(claim),
            )
        }
    }

    @Test fun anUnknownPairingStaysUnknownInsteadOfBorrowingTheShippedFormat() {
        // Resolution is per pairing: another firmware or SDK must not inherit a decision made for this one.
        assertNull(PcmFormatEvidence.resolve("0.0.000-19700101-000000", "client-l:none"))
    }

    @Test fun theSdkCoordinateIsTheOneActuallyPinnedInTheBuild() {
        // If the pinned SDK moves, this resolution must be revisited rather than silently covering a new artifact.
        val resolved = PcmFormatEvidence.resolve(FIRMWARE, PINNED_SDK)
        assertNotNull("the pinned SDK coordinate must be the one that resolves", resolved)
    }

    private companion object {
        const val FIRMWARE = "1.26.011-20260929-150201"
        const val SDK = "1.1.2"
        const val PINNED_SDK = "1.1.2"
    }
}
