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

    /** 0.0–1.0 for the message card background (and its tail); the text stays system-default opaque. */
    val bubbleAlpha: Float get() = bubbleOpacityPercent / 100f

    /** The whole-card alpha of a superseded message: the configured bubble alpha times the fixed ratio. */
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
