package com.sheen.rokid.phone.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.model.RequestConsent
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.ProfileRepository
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * The user's confirmation for one probe.
 *
 * [image] distinguishes the synthetic image probe (0.25 R76) from the text probe: the two operations are verified
 * separately (`TEXT_REQUEST` / `IMAGE_REQUEST`), so the confirmation dialog and the gate admission must name which one
 * the user is about to allow.
 *
 * 0.52: the type lives beside the gate rather than beside the retired profile editor, because the gate is the only
 * production consumer of a confirmation.
 */
internal data class ProbeConsent(
    val id: String,
    val revision: Long,
    val domain: String,
    val image: Boolean = false,
)

/** The host a confirmed URL points at, in the form compared against [ProbeConsent.domain]. */
internal fun domain(url: String): String {
    val uri = URI(url)
    require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.fragment == null)
    return uri.host.lowercase(java.util.Locale.ROOT) + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
}

/**
 * The confirmation/admission gate in front of the synthetic probes (P5; extended in 0.25 R75/R76).
 *
 * A probe only runs after the user confirmed the exact profile revision and target domain; while it runs, the thread's
 * admission is what makes the model client's consent answer `text`/`syntheticProbe` true. The image verdict is
 * **derived from the profile**: only a profile that declares `IMAGE` may have image upload granted, and the explicit
 * synthetic-image widening is only set by the image probe's own admission. The production session never ORs image in
 * blindly - its consent source asks the profile (R75).
 */
internal class ProfileProbeGate(private val repository: ProfileRepository) {
    private val confirmation = AtomicReference<ProbeConsent?>()
    private val admission = ThreadLocal<ProbeConsent>()
    fun grant(consent: ProbeConsent?) { confirmation.set(consent) }

    /**
     * The consent the gated model client sees.
     *
     * Inside a probe the matching admission is the profile's own revision/domain; outside one nothing is admitted here
     * (the production host adds `text` because the session is running, and image because the profile declares `IMAGE`).
     * `image` is true only for an image-capable profile. `syntheticImageProbe` is true only for an admitted **image**
     * probe, never for a text probe on the same profile - probe mode must stay text-only except for that one explicit
     * exception (R77).
     */
    fun requestConsent(): RequestConsent {
        val consent = admission.get()
        val profile = consent?.let { repository.get(it.id) }
        val allowed = consent != null && profile != null && consent.revision == profile.revision &&
            consent.domain == domain(profile.baseUrl)
        val imageCapable = if (allowed) InputModality.IMAGE in requireNotNull(profile).inputs else false
        return RequestConsent(
            text = allowed,
            image = imageCapable,
            audio = false,
            syntheticProbe = allowed,
            syntheticImageProbe = imageCapable && consent?.image == true,
        )
    }

    fun test(tester: ProfileTester, profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
        run(tester, profileId, listener, image = false)

    /** R76: same confirmation/admission checks as the text probe, then the synthetic image request. */
    fun testImage(tester: ProfileTester, profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
        run(tester, profileId, listener, image = true)

    private fun run(
        tester: ProfileTester,
        profileId: String,
        listener: (ModelStreamEvent) -> Unit,
        image: Boolean,
    ): CancellationHandle {
        val consent = confirmation.getAndSet(null)
            ?: throw ContractError.ConfigurationRejected("Probe confirmation required")
        val profile = repository.get(profileId)
            ?: throw ContractError.ConfigurationRejected("Profile unavailable")
        if (consent.id != profile.id || consent.revision != profile.revision ||
            consent.domain != domain(profile.baseUrl) || consent.image != image
        ) {
            throw ContractError.ConfigurationRejected("Probe confirmation expired")
        }
        admission.set(consent)
        try {
            return if (image) tester.testImage(profileId, listener) else tester.testText(profileId, listener)
        } finally {
            admission.remove()
        }
    }
}
