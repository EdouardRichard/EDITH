package com.sheen.rokid.phone.overlay

import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings

/**
 * 0.5.1 (`C7`): the whole-bubble alpha of one message, as one pure rule.
 *
 * The renderer puts card, text, tail and shadow inside a single `Modifier.alpha(...)` layer, so the layer's value is
 * the **whole** bubble's opacity:
 *
 * - newest message: the configured bubble opacity (75% default, 30–100 adjustable);
 * - old message: [PhoneOnlyAppearance.oldMessageAlpha] (0.42 × the bubble opacity) times the model's time fade.
 *
 * Keeping the arithmetic here instead of in Compose is what lets the JVM tests pin the exact numbers the owner
 * approved, and keeps the renderer from hard-coding the ratio a second time.
 */
object BubbleRenderMath {

    /**
     * The whole-bubble alpha.
     *
     * @param newest true for the newest message: [bubbleAlpha] is already its whole-bubble alpha.
     * @param bubbleAlpha the configured [PhoneOnlyAppearance.bubbleAlpha], 0..1.
     * @param fade the 0..1 time factor from [fade]; 1 means full strength.
     * @param oldMessageAlpha the superseded base. The renderer passes [PhoneOnlyAppearance.oldMessageAlpha] so the
     *   drawn value cannot drift from the settings; the default is the model's fixed ratio, which is the same rule.
     */
    fun layerAlpha(
        newest: Boolean,
        bubbleAlpha: Float,
        fade: Float,
        oldMessageAlpha: Float = bubbleAlpha.coerceIn(0f, 1f) * SmartSettings.OLD_MESSAGE_ALPHA_RATIO,
    ): Float {
        val base = if (newest) bubbleAlpha.coerceIn(0f, 1f) else oldMessageAlpha.coerceIn(0f, 1f)
        return (base * fade.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /**
     * The 0..1 time fade the whole-bubble alpha multiplies.
     *
     * The newest message is always at full strength: its 200 ms entry fade is how the model phases a new row in, not
     * a second opacity. A superseded message's model alpha is relative to the bubble opacity, while the renderer's old
     * base is already 0.42 × that opacity; dividing re-normalizes the model's 0.42 hold to 1 and its 0.42 → 0 removal
     * fade to 1 → 0. The model's 180 ms supersede ramp starts at 1.0 (2.38 after the division), so it saturates at the
     * old base by design: an old bubble may never render brighter than [PhoneOnlyAppearance.oldMessageAlpha].
     */
    fun fade(newest: Boolean, relativeAlpha: Float): Float =
        if (newest) 1f else (relativeAlpha / SmartSettings.OLD_MESSAGE_ALPHA_RATIO).coerceIn(0f, 1f)
}
