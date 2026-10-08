package edith.glasses.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two `CXRServiceBridge.sendMessage` overloads are not interchangeable, and the earlier uplink work only ever
 * used one of them.
 *
 * The distinction is not cosmetic. `javap` on the pinned AAR shows three overloads, two of them `native`, and the
 * native library exports **different** symbols for them - `NativeCXRBridge::sendMessage(char const*, Caps const&)`
 * for the two-argument form and an overload taking an extra `unsigned char const*`/`unsigned` pair for the form that
 * carries a separate byte array. The library also contains `sendMessageStream`, `sendMessageShort`, `streamDataCall`,
 * `startStream` and `endStream`, which the two-argument form has no reason to touch.
 *
 * That matters because the audio uplink was measured to report success on every frame while delivering nothing. The
 * two-argument form serialises the payload **into** the `Caps` (a header-sized container); a 2.5 KB audio frame has no
 * business travelling as a header value. The overload that takes the frame as its own byte array is the one built for
 * a payload, and it was never exercised.
 *
 * These tests pin the *selection* rule so the choice is a named decision rather than an accident of which overload
 * someone typed.
 */
class CxrSendVariantTest {

    @Test fun theDefaultVariantIsTheOneThatCarriesThePayloadSeparately() {
        // The route to test is the data-carrying overload: the two-argument form is already a measured negative, so
        // defaulting to it would silently re-run a refuted experiment and report it as the new one.
        assertEquals(CxrSendVariant.CapsWithDataArray, CxrSendVariant.DEFAULT)
    }

    @Test fun eachVariantKeepsItsOwnNameSoARunSaysWhichOneItUsed() {
        // A result is only interpretable if the log names the variant, because the whole point is to compare two of
        // them. Two variants sharing a label would make the comparison worthless.
        val labels = CxrSendVariant.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("capsOnly", CxrSendVariant.CapsOnly.label)
        assertEquals("capsWithDataArray", CxrSendVariant.CapsWithDataArray.label)
    }

    @Test fun anUnknownLabelIsRefusedRatherThanFallingBackToADefault() {
        // Falling back would turn a typo in a run command into a run of the wrong experiment - which, given that one
        // variant has already been measured negative, would look like a fresh failure instead of a void run.
        val failure = runCatching { CxrSendVariant.fromLabel("capsWithDataArry") }.exceptionOrNull()
        assertTrue("an unknown label must be refused, was: $failure", failure is IllegalArgumentException)
    }

    @Test fun theLabelsThatRunsActuallyUseResolveBackToTheirVariants() {
        // Round-trip, so the documented `--es sendVariant <label>` values in the probe cannot drift from the type.
        CxrSendVariant.entries.forEach { variant ->
            assertEquals(variant, CxrSendVariant.fromLabel(variant.label))
        }
    }

    @Test fun aBlankLabelIsRefusedBecauseItMeansTheRunSpecifiedNothing() {
        val failure = runCatching { CxrSendVariant.fromLabel("   ") }.exceptionOrNull()
        assertTrue("a blank label must be refused, was: $failure", failure is IllegalArgumentException)
    }

    @Test fun theTwoVariantsAreNotTheSameValue() {
        // Guards against a "refactor" collapsing them, which would leave only one overload reachable while the
        // comparison still appeared to exist.
        assertFalse(CxrSendVariant.CapsOnly == CxrSendVariant.CapsWithDataArray)
    }
}
