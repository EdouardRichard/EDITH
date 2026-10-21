package com.sheen.rokid.phone.app

import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3 Phase 2.1 Task 4 fix: the automatic-vision profile gate must resolve the profile the **turn** is configured
 * with, not the assembly-time settings choice.
 *
 * The turn configuration reads the repository's per-plugin selection first (`AndroidProfileRepository.selections()`)
 * and only falls back to the settings-resolved profile when there is no usable selection. These cases pin that order
 * on the pure resolver the composition lambda delegates to: a text-only selection must suppress vision even when the
 * settings fallback declares `IMAGE`, and a throwing/absent lookup must fail closed (no image).
 */
class AutoVisionProfileTest {

    private val textProfile = profile("text-selected", setOf(InputModality.TEXT))
    private val imageProfile = profile("image-selected", setOf(InputModality.TEXT, InputModality.IMAGE))
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
            "the user selected an IMAGE profile; the text-only settings fallback must not suppress vision",
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

    private fun profile(id: String, inputs: Set<InputModality>) = ModelProfile(
        id = id,
        revision = 1,
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
        verifications = emptyList(),
    )
}
