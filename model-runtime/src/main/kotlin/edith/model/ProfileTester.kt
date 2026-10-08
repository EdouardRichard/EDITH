package edith.model

import edith.plugin.CancellationHandle
import edith.plugin.ModelStreamEvent

/** Host-only synthetic text testing; concrete transport and verification are implemented in T12. */
interface ProfileTester {
    fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle
}
