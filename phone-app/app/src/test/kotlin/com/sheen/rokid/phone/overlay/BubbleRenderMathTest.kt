package com.sheen.rokid.phone.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5.1 (`C7`): the whole-bubble alpha the renderer applies as a single `Modifier.alpha` layer.
 *
 * New message: the configured bubble opacity (75% → 0.75, 30% → 0.30). Old message: 0.42 × that opacity
 * (0.315 / 0.126) times the model's time fade. Card, text, tail and shadow all sit inside the same layer, so this
 * function is the only arithmetic the UI needs; `PhoneOnlyBubbleContent` only wraps the bubble in it.
 */
class BubbleRenderMathTest {

    @Test
    fun `new message whole alpha is the bubble opacity`() {
        assertEquals(0.75f, BubbleRenderMath.layerAlpha(newest = true, bubbleAlpha = 0.75f, fade = 1f), 0.0001f)
        assertEquals(0.30f, BubbleRenderMath.layerAlpha(newest = true, bubbleAlpha = 0.30f, fade = 1f), 0.0001f)
        assertEquals(1f, BubbleRenderMath.layerAlpha(newest = true, bubbleAlpha = 1f, fade = 1f), 0.0001f)
    }

    @Test
    fun `old message whole alpha is point four two times the bubble opacity`() {
        assertEquals(0.315f, BubbleRenderMath.layerAlpha(newest = false, bubbleAlpha = 0.75f, fade = 1f), 0.0001f)
        assertEquals(0.126f, BubbleRenderMath.layerAlpha(newest = false, bubbleAlpha = 0.30f, fade = 1f), 0.0001f)

        // The renderer passes the appearance's own old base, so the drawn value cannot drift from the setting.
        val appearance = PhoneOnlyAppearance(bubbleOpacityPercent = 75)
        assertEquals(
            appearance.oldMessageAlpha,
            BubbleRenderMath.layerAlpha(false, appearance.bubbleAlpha, 1f, appearance.oldMessageAlpha),
            0.0001f,
        )

        // A distinct old base must be used as-is, not recomputed from bubbleAlpha: this fails if the fourth parameter
        // is ignored, which the equality above cannot catch (both sides are the same product there).
        assertEquals(
            0.5f,
            BubbleRenderMath.layerAlpha(newest = false, bubbleAlpha = 0.75f, fade = 1f, oldMessageAlpha = 0.5f),
            0.0001f,
        )
    }

    @Test
    fun `the time fade scales the base and the result stays inside zero to one`() {
        assertEquals(0.1575f, BubbleRenderMath.layerAlpha(false, 0.75f, 0.5f), 0.0001f)
        assertEquals(0.375f, BubbleRenderMath.layerAlpha(true, 0.75f, 0.5f), 0.0001f)
        assertEquals(0f, BubbleRenderMath.layerAlpha(true, 0.75f, 0f), 0.0001f)
        assertEquals(0.75f, BubbleRenderMath.layerAlpha(true, 0.75f, 4f), 0.0001f)
    }

    @Test
    fun `the old fade normalizes the model ratio and clamps the supersede ramp`() {
        assertEquals(1f, BubbleRenderMath.fade(newest = false, relativeAlpha = 0.42f), 0.0001f)
        assertEquals(0.5f, BubbleRenderMath.fade(newest = false, relativeAlpha = 0.21f), 0.0001f)
        assertEquals(0f, BubbleRenderMath.fade(newest = false, relativeAlpha = 0f), 0.0001f)
        // The model's 180 ms supersede ramp starts at 1.0; the whole bubble may never exceed the old base.
        assertEquals(1f, BubbleRenderMath.fade(newest = false, relativeAlpha = 1f), 0.0001f)
        // The newest message is always at its full configured opacity; its entry is not a second fade.
        assertEquals(1f, BubbleRenderMath.fade(newest = true, relativeAlpha = 0.2f), 0.0001f)
    }
}
