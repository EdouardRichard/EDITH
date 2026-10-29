package com.sheen.rokid.phone.overlay

import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings

/**
 * 0.5 (`D210`/`D211`): the two user-adjustable opacities of the phone-only model, as one immutable value.
 *
 * Pure and Android-free so the settings store, the overlay renderer and the JVM tests agree on one clamp rule. The old
 * message's fade is deliberately **not** a third field: [oldMessageAlpha] is [bubbleAlpha] multiplied by the fixed
 * [SmartSettings.OLD_MESSAGE_ALPHA_RATIO], which is the owner's ruling that old messages get no separate configuration.
 */
data class PhoneOnlyAppearance(
    val ballOpacityPercent: Int = SmartSettings.DEFAULT_BALL_OPACITY,
    val bubbleOpacityPercent: Int = SmartSettings.DEFAULT_BUBBLE_OPACITY,
) {
    /** 0.0–1.0 for the ball's icon/background; the thinking light ring keeps its own strength. */
    val ballAlpha: Float get() = ballOpacityPercent / 100f

    /**
     * 0.0–1.0 for the **whole** newest bubble: card, text, tail and shadow all sit inside one alpha layer, so the text
     * fades with the card instead of staying fully opaque (0.5.1 `C7`).
     */
    val bubbleAlpha: Float get() = bubbleOpacityPercent / 100f

    /**
     * The whole-bubble alpha of a superseded message before its time fade: the configured bubble alpha times the fixed
     * ratio. The renderer consumes this value directly, so there is one authority for the 0.42 relation.
     */
    val oldMessageAlpha: Float get() = bubbleAlpha * SmartSettings.OLD_MESSAGE_ALPHA_RATIO

    companion object {
        /** The shipped defaults. */
        val DEFAULT = PhoneOnlyAppearance()

        /** Clamps both values to the shipped ranges; used by every setter and by the store's reader. */
        fun normalized(ballPercent: Int, bubblePercent: Int): PhoneOnlyAppearance = PhoneOnlyAppearance(
            ballOpacityPercent = ballPercent.coerceIn(SmartSettings.MIN_BALL_OPACITY, SmartSettings.MAX_OPACITY),
            bubbleOpacityPercent = bubblePercent.coerceIn(SmartSettings.MIN_BUBBLE_OPACITY, SmartSettings.MAX_OPACITY),
        )

        /** The persisted values, clamped; a hand-edited blob cannot make either renderer trust an out-of-range number. */
        fun fromSettings(settings: SmartSettings): PhoneOnlyAppearance = normalized(
            settings.ballOpacityPercent,
            settings.bubbleOpacityPercent,
        )
    }
}
