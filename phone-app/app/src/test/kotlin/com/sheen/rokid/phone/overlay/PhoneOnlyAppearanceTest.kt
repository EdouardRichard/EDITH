package com.sheen.rokid.phone.overlay

import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5 (`D210`/`D211`): the one clamp/alpha rule shared by the settings store and the overlay renderer.
 *
 * The old-message fade is deliberately not a setting: it is always the configured bubble alpha times the fixed ratio,
 * which is the owner's ruling. These tests pin that relation, so a future "configurable old opacity" cannot slip in as a
 * third field.
 */
class PhoneOnlyAppearanceTest {

    @Test
    fun defaultsAreTheApprovedPreviewValues() {
        val appearance = PhoneOnlyAppearance.DEFAULT

        assertEquals(82, appearance.ballOpacityPercent)
        assertEquals(75, appearance.bubbleOpacityPercent)
        assertEquals(0.82f, appearance.ballAlpha, 0.0001f)
        assertEquals(0.75f, appearance.bubbleAlpha, 0.0001f)
    }

    @Test
    fun normalizationClampsBothRanges() {
        assertEquals(PhoneOnlyAppearance(20, 30), PhoneOnlyAppearance.normalized(0, 0))
        assertEquals(PhoneOnlyAppearance(20, 30), PhoneOnlyAppearance.normalized(-40, 10))
        assertEquals(PhoneOnlyAppearance(100, 100), PhoneOnlyAppearance.normalized(200, 1_000))
        assertEquals(PhoneOnlyAppearance(82, 75), PhoneOnlyAppearance.normalized(82, 75))
    }

    @Test
    fun theOldMessageFadeIsTheConfiguredBubbleAlphaTimesTheFixedRatio() {
        val appearance = PhoneOnlyAppearance(ballOpacityPercent = 50, bubbleOpacityPercent = 60)

        assertEquals(0.60f, appearance.bubbleAlpha, 0.0001f)
        assertEquals(0.60f * SmartSettings.OLD_MESSAGE_ALPHA_RATIO, appearance.oldMessageAlpha, 0.0001f)
        // No independent old-message field exists: the ratio is the single authority.
        assertEquals(0.42f, SmartSettings.OLD_MESSAGE_ALPHA_RATIO, 0.0001f)
    }

    /**
     * 0.5.1 (`C7`): the two numbers the renderer must produce from the settings. New messages are the configured
     * bubble opacity; old messages are 0.42 × that opacity, whole bubble (card, text, tail, shadow).
     */
    @Test
    fun theSliderDefaultsAndMinimumsProduceTheDocumentedWholeBubbleAlphas() {
        val defaults = PhoneOnlyAppearance.DEFAULT
        assertEquals(0.75f, defaults.bubbleAlpha, 0.0001f)
        assertEquals(0.315f, defaults.oldMessageAlpha, 0.0001f)

        val minimum = PhoneOnlyAppearance(bubbleOpacityPercent = SmartSettings.MIN_BUBBLE_OPACITY)
        assertEquals(0.30f, minimum.bubbleAlpha, 0.0001f)
        assertEquals(0.126f, minimum.oldMessageAlpha, 0.0001f)
    }

    @Test
    fun fromSettingsTakesThePersistedValuesAndClampsThem() {
        val appearance = PhoneOnlyAppearance.fromSettings(
            SmartSettings(ballOpacityPercent = 5, bubbleOpacityPercent = 250),
        )

        assertEquals(PhoneOnlyAppearance(20, 100), appearance)
    }
}
