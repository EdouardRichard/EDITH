package com.sheen.rokid.phone.app

import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.plugin.VerificationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3 Phase 2.1 Task 4 fix + final whole-branch fix: the automatic-vision profile gate.
 *
 * Two properties are pinned here:
 * 1. The gate resolves the profile the **turn** is configured with, not the assembly-time settings choice. The turn
 *    configuration reads the repository's per-plugin selection first (`AndroidProfileRepository.selections()`) and only
 *    falls back to the settings-resolved profile when there is no usable selection.
 * 2. Declaring `IMAGE` is necessary but not sufficient: the profile's latest `IMAGE_REQUEST` verification for its
 *    **current revision** must be `Verified`, because `CapabilityGate` rejects any request carrying an image otherwise
 *    and that rejection ends the whole turn. The selection rule is copied from `CapabilityGate` (same revision, max
 *    `testedAtEpochMillis`, list index as the tie-break) so the gate cannot admit an image the request gate rejects.
 *
 * A text-only selection must suppress vision even when the settings fallback declares `IMAGE`; a throwing/absent
 * lookup and every unverified/failed/older-revision probe must fail closed (no image).
 */
class AutoVisionProfileTest {

    private val textProfile = profile("text-selected", setOf(InputModality.TEXT))
    private val imageProfile = profile(
        "image-selected",
        setOf(InputModality.TEXT, InputModality.IMAGE),
        verifications = listOf(imageProbe()),
    )
    private val profiles = mapOf(textProfile.id to textProfile, imageProfile.id to imageProfile)

    @Test
    fun `a text repository selection wins over an image settings fallback`() {
        assertFalse(
            "the user selected a text-only profile; auto vision must stay off despite the IMAGE settings fallback",
            autoVisionProfileSupportsImage(
                selection = { textProfile.id },
                load = profiles::get,
                fallback = { imageProfile },
            ),
        )
    }

    @Test
    fun `an image repository selection wins over a text settings fallback`() {
        assertTrue(
            "the user selected a verified IMAGE profile; the text-only settings fallback must not suppress vision",
            autoVisionProfileSupportsImage(
                selection = { imageProfile.id },
                load = profiles::get,
                fallback = { textProfile },
            ),
        )
    }

    @Test
    fun `no repository selection falls back to the settings profile`() {
        assertTrue(
            autoVisionProfileSupportsImage(
                selection = { null },
                load = profiles::get,
                fallback = { imageProfile },
            ),
        )
        assertFalse(
            autoVisionProfileSupportsImage(
                selection = { null },
                load = profiles::get,
                fallback = { textProfile },
            ),
        )
    }

    @Test
    fun `an unreadable selection falls back, and no readable profile at all reads false`() {
        assertTrue(
            "a selection whose profile was deleted is a read failure; the turn falls back to the settings profile",
            autoVisionProfileSupportsImage(
                selection = { "deleted-profile" },
                load = profiles::get,
                fallback = { imageProfile },
            ),
        )
        assertTrue(
            "a throwing selection read is the same fallback: the turn configuration would use the settings profile",
            autoVisionProfileSupportsImage(
                selection = { throw IllegalStateException("the keystore is locked") },
                load = profiles::get,
                fallback = { imageProfile },
            ),
        )
        assertFalse(
            "neither the selection nor the fallback resolves a profile: fail closed",
            autoVisionProfileSupportsImage(
                selection = { "deleted-profile" },
                load = profiles::get,
                fallback = { null },
            ),
        )
    }

    @Test
    fun `a throwing lookup with no fallback fails closed`() {
        val boomSelection: () -> String? = { throw IllegalStateException("the keystore is locked") }
        val boomLoad: (String) -> ModelProfile? = { throw IllegalStateException("the payload is gone") }
        val boomFallback: () -> ModelProfile? = { throw IllegalStateException("the settings pair is gone") }
        assertFalse(
            "a throwing selection read with no fallback fails closed",
            autoVisionProfileSupportsImage(selection = boomSelection, load = profiles::get, fallback = { null }),
        )
        assertFalse(
            "a throwing profile read with no fallback fails closed",
            autoVisionProfileSupportsImage(selection = { imageProfile.id }, load = boomLoad, fallback = { null }),
        )
        assertFalse(
            "a throwing fallback fails closed",
            autoVisionProfileSupportsImage(selection = { null }, load = profiles::get, fallback = boomFallback),
        )
    }

    @Test
    fun `the gate reads the selection on every call`() {
        var selected = textProfile.id
        val gate = {
            autoVisionProfileSupportsImage(
                selection = { selected },
                load = profiles::get,
                fallback = { imageProfile },
            )
        }

        assertFalse("the initially selected text profile suppresses vision", gate())
        selected = imageProfile.id
        assertTrue("a selection changed after assembly takes effect on the next read", gate())
    }

    // ── declared is necessary, current-revision IMAGE_REQUEST=Verified is sufficient ─────────────

    @Test
    fun `a declared image profile without an IMAGE_REQUEST verification reads false`() {
        val declaredOnly = profile(
            "declared-only",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            // Another operation's verification must not stand in for the image probe.
            verifications = listOf(verification("TEXT_REQUEST", VerificationState.Verified)),
        )

        assertFalse(
            "declaring IMAGE without passing the image probe would end the first auto-image turn in CapabilityMissing",
            gate(declaredOnly),
        )
    }

    @Test
    fun `a failed IMAGE_REQUEST reads false`() {
        val failed = profile(
            "image-failed",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            verifications = listOf(imageProbe(state = VerificationState.Failed)),
        )

        assertFalse("a failed image probe must keep auto vision off", gate(failed))
    }

    @Test
    fun `an IMAGE_REQUEST verified at an older revision reads false`() {
        val revised = profile(
            "image-revised",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            revision = 2,
            verifications = listOf(imageProbe(revision = 1, state = VerificationState.Verified)),
        )

        assertFalse(
            "a verification of the previous revision does not cover the current profile revision",
            gate(revised),
        )
    }

    @Test
    fun `an IMAGE_REQUEST verified at the current revision reads true`() {
        val revised = profile(
            "image-reverified",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            revision = 2,
            verifications = listOf(imageProbe(revision = 2, state = VerificationState.Verified)),
        )

        assertTrue("the current revision has a Verified image probe", gate(revised))
    }

    @Test
    fun `the newest IMAGE_REQUEST wins over an older verified one`() {
        val overridden = profile(
            "image-overridden",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            verifications = listOf(
                imageProbe(state = VerificationState.Verified, testedAt = 100L),
                imageProbe(state = VerificationState.Failed, testedAt = 200L),
            ),
        )

        assertFalse("the latest probe is Failed, so an older Verified probe must not admit an image", gate(overridden))
    }

    @Test
    fun `an equal-timestamp tie is decided by list order`() {
        val verifiedLast = profile(
            "tie-verified-last",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            verifications = listOf(
                imageProbe(state = VerificationState.Failed, testedAt = 100L),
                imageProbe(state = VerificationState.Verified, testedAt = 100L),
            ),
        )
        val failedLast = profile(
            "tie-failed-last",
            setOf(InputModality.TEXT, InputModality.IMAGE),
            verifications = listOf(
                imageProbe(state = VerificationState.Verified, testedAt = 100L),
                imageProbe(state = VerificationState.Failed, testedAt = 100L),
            ),
        )

        assertTrue("same timestamp: the later list entry is the latest probe", gate(verifiedLast))
        assertFalse("same timestamp: the later failed entry overrides the earlier verified one", gate(failedLast))
    }

    @Test
    fun `a text-only profile reads false even with a verified image probe`() {
        val textWithProbe = profile(
            "text-with-image-probe",
            setOf(InputModality.TEXT),
            verifications = listOf(imageProbe()),
        )

        assertFalse("a profile that does not declare IMAGE never enables auto vision", gate(textWithProbe))
    }

    private fun gate(profile: ModelProfile): Boolean = autoVisionProfileSupportsImage(
        selection = { profile.id },
        load = { id -> profile.takeIf { it.id == id } },
        fallback = { null },
    )

    private fun imageProbe(
        revision: Long = 1,
        state: VerificationState = VerificationState.Verified,
        testedAt: Long = 100L,
    ): CapabilityVerification = verification("IMAGE_REQUEST", state, revision, testedAt)

    private fun verification(
        operation: String,
        state: VerificationState,
        revision: Long = 1,
        testedAt: Long = 100L,
    ): CapabilityVerification = CapabilityVerification(
        profileRevision = revision,
        operation = operation,
        state = state,
        testedAtEpochMillis = testedAt,
    )

    private fun profile(
        id: String,
        inputs: Set<InputModality>,
        revision: Long = 1,
        verifications: List<CapabilityVerification> = emptyList(),
    ) = ModelProfile(
        id = id,
        revision = revision,
        name = "Synthetic $id",
        service = "Synthetic",
        baseUrl = "https://synthetic.example/v1",
        protocolId = "DEEPSEEK_RESPONSES",
        model = "synthetic-model",
        secret = object : SecretHandle { override val id = "synthetic-secret" },
        usableWindowTokens = 4096,
        inputBudgetTokens = 2048,
        maxOutputTokens = 1024,
        safetyTokens = 128,
        timeoutMillis = 5000,
        inputs = inputs,
        outputs = setOf(OutputModality.TEXT),
        verifications = verifications,
    )
}
