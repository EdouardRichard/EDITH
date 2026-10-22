package com.sheen.rokid.phone.session
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.model.RequestConsent
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
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
 * 0.25 R75 narrows the remaining seam: only `text` is added unconditionally. `image` is granted by the **profile**
 * declaring `IMAGE` (the session cannot start on a text-only profile at all, R74) and never by the running session
 * alone; `audio` stays ungranted in this version.
 */
class UploadConsentRemovedTest {

    private val nothingAllowed = RequestConsent(text = false, image = false, audio = false, syntheticProbe = false)

    private fun profile(vararg inputs: InputModality) = ModelProfile("p", 0, "synthetic", "synthetic",
        "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
        object : SecretHandle { override val id = "opaque" }, 4096, 2048, 1024, 128, 5000,
        inputs.toSet(), setOf(OutputModality.TEXT), emptyList())

    @Test
    fun aRealTurnIsAllowedByTheRunningSessionAlone() {
        val source = HostCompositionRoot.consentSource { nothingAllowed }

        assertTrue("运行中的会话不需要第二个开关", source(profile(InputModality.TEXT)).text)
    }

    @Test
    fun theProbeFlagIsNeverInventedByTheSession() {
        val probeAllowed = RequestConsent(text = false, image = false, audio = false, syntheticProbe = true)
        val source = HostCompositionRoot.consentSource { probeAllowed }

        val merged = source(profile(InputModality.TEXT))
        assertTrue("the probe flow's own flag passes through", merged.syntheticProbe)
        assertTrue("and text is allowed by the running session", merged.text)
    }

    @Test
    fun theSessionStillDoesNotGrantImageOrAudio() {
        // Only `text` is added by the session. An image upload needs the profile to declare IMAGE (R75/R74); a
        // text-only profile must never gain it through this merge, and audio is not part of this version.
        val source = HostCompositionRoot.consentSource { nothingAllowed }

        val textOnly = source(profile(InputModality.TEXT))
        assertFalse("image stays ungranted for a text profile", textOnly.image)
        assertFalse("audio stays ungranted", textOnly.audio)
        assertTrue("an image-capable profile is the one thing that grants image upload",
            source(profile(InputModality.TEXT, InputModality.IMAGE)).image)
    }
}
