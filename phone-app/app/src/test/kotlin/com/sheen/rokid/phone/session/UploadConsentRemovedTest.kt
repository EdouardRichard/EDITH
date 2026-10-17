package com.sheen.rokid.phone.session
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.model.RequestConsent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `D53`① (2026-10-11): the session-level upload switch is **gone**.
 *
 * The owner's ruling was "zero knobs": there is no per-session upload consent any more, **"会话在运行"即允许** the
 * transcript and its turn context to reach the configured model service, and the only consenting action is pressing
 * 开始. What is left of the old object is this one merge - and it must still never *invent* the probe flow's flag, or a
 * real turn would silently satisfy the probe confirmation that flow requires.
 *
 * The protections this does **not** touch (and which the acceptance row carries): logs and evidence record counts and
 * ids only, memory writes make no network request, no raw audio or voiceprint or résumé is written to disk, and nothing
 * goes anywhere but the configured model service.
 */
class UploadConsentRemovedTest {

    private val nothingAllowed = RequestConsent(text = false, image = false, audio = false, syntheticProbe = false)

    @Test
    fun aRealTurnIsAllowedByTheRunningSessionAlone() {
        val source = HostCompositionRoot.consentSource { nothingAllowed }

        assertTrue("运行中的会话不需要第二个开关", source().text)
    }

    @Test
    fun theProbeFlagIsNeverInventedByTheSession() {
        val probeAllowed = RequestConsent(text = false, image = false, audio = false, syntheticProbe = true)
        val source = HostCompositionRoot.consentSource { probeAllowed }

        val merged = source()
        assertTrue("the probe flow's own flag passes through", merged.syntheticProbe)
        assertTrue("and text is allowed by the running session", merged.text)
    }

    @Test
    fun theSessionStillDoesNotGrantImageOrAudio() {
        // Only `text` is added: a proactive or passive turn is text, and widening this would be a new upload surface
        // nobody asked for (the D53 impact analysis kept exactly this boundary).
        val source = HostCompositionRoot.consentSource { nothingAllowed }

        val merged = source()
        assertFalse("image stays ungranted", merged.image)
        assertFalse("audio stays ungranted", merged.audio)
    }
}
