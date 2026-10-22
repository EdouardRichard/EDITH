package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent

/**
 * Host-only synthetic connection testing (0.25 §4.10 / R76).
 *
 * The production implementation sends a fixed probe request through the gated model client and records the
 * capability evidence the profile gate later reads. The probe never carries user content or a user-chosen prompt.
 */
interface ProfileTester {
    /** Sends the fixed synthetic **text** probe and records `TEXT_REQUEST` for the tested revision. */
    fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle

    /**
     * Sends the fixed synthetic **image** probe and records `IMAGE_REQUEST` for the tested revision (R76).
     *
     * The default refuses visibly: a tester that cannot send an image must not make the UI report an image capability
     * it never verified. The production host overrides this with [ControlledProfileTester].
     */
    fun testImage(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
        throw ContractError.CapabilityMissing("Image probe unavailable")
}

/**
 * The one transport entry the probe tester needs, implemented by [ControlledModelClient] and faked by tests.
 *
 * It is a separate small port rather than a second concrete dependency so the tester's verification logic (which
 * terminal records what) can be exercised without an HTTP round trip; the real admission path is still
 * `ControlledModelClient.admit(probe = true)`.
 */
fun interface ProfileProbeTransport {
    fun probe(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle
}
