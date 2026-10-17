package com.sheen.rokid.model

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelStreamEvent

/** Host-only synthetic text testing; concrete transport and verification are implemented in T12. */
interface ProfileTester {
    fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle
}
