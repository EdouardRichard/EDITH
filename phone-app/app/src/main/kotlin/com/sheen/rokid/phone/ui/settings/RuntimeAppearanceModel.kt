package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult

/**
 * 0.52 design §3.4/§4.2: the pure copy and result projection behind [RuntimeAppearanceScreen].
 *
 * The page itself only lays controls out. Every line a refusal produces is decided here, so the JVM test
 * (`SettingsFeedbackTest`) drives exactly the functions the composable calls:
 *
 * - speech uses [SettingsFeedback]'s central mapping; a refusal can never render as 「已保存」;
 * - opacity has no mapping of its own: `PhoneOnlyOpacityResult.error` carries the same stable codes as
 *   [SettingsSaveResult], so the existing mapping is reused instead of a second copy of the Chinese sentences;
 * - the phone-only switch keeps [DetachUi]'s wording, including the "session was stopped first" side effect that a
 *   save refusal must not hide;
 * - the running/failure lines are the 0.5.1 copy, kept here so the page and its test share one source.
 *
 * No Compose or Android imports: this object is exercised by a plain JVM test.
 */
internal object RuntimeAppearanceUi {

    /** The mode-off note under the sliders (A14: the screen still saves, the overlay applies it later). */
    const val MODE_OFF_NOTE = "模式开启后生效：纯手机模式关闭中，滑块只预览数值并保存设置。"

    /** The reset control the old settings section had; the values stay the shipped 82/75 (design §3.4). */
    const val RESET_OPACITY = "恢复默认透明度"

    /** 0.5.1 (`C12`/`C14`): only the service's own running transition reads as 「已启用」. */
    const val RUNNING = "桌面悬浮球已启用；长按悬浮球开始说话。"

    /** Shown while the mode is on but the service has not reported running yet. */
    const val NOT_RUNNING = "悬浮球尚未运行（若刚开启模式，可能还在启动）。"

    /** Prefix of the visible start-failure line; the reason itself is `phoneOnlyStartFailureText`'s copy. */
    const val START_FAILURE_PREFIX = "悬浮球启动失败："

    /** Switch and slider labels that are also their accessibility descriptions. */
    const val PHONE_ONLY_SWITCH = "纯手机模式"
    const val SPEECH_SWITCH = "语音朗读"
    const val BALL_SLIDER = "悬浮球透明度滑块"
    const val BUBBLE_SLIDER = "气泡透明度滑块"

    /**
     * The speech switch's result line.
     *
     * Success is the common "no restart needed" line; a refusal is [SettingsFeedback]'s mapped sentence. The host moves
     * the live switch before it persists (so the user's tap is not lost), which is exactly why the refusal must be
     * visible rather than inferred from the switch position.
     */
    fun speechText(result: SettingsSaveResult): String =
        if (result.saved) {
            SettingsFeedback.savedRestartText(0)
        } else {
            requireNotNull(SettingsFeedback.saveFailedText(result))
        }

    /**
     * The detach auto-on speech refusal, or null when there is nothing to report (0.52 fix round 1, design §4.2).
     *
     * The host turns 「语音朗读」on inside the phone-only transition (`HostCompositionRoot.onDetachModeChanged`) and
     * publishes that write's result separately from the page's own switch callback. It is still a save failure, so it
     * goes through the same central mapping; a success answers null (nothing extra is drawn). Without this the page
     * could show the live switch on while the refusal existed only in the `P5_TTS persisted=false` log line.
     */
    fun autoOnSpeechText(result: SettingsSaveResult?): String? =
        result?.let { SettingsFeedback.saveFailedText(it) }

    /**
     * The opacity save line.
     *
     * [saved] comes from `PhoneOnlyOpacityResult`; on success the line names both committed values and whether the
     * overlay is already following them ([detached] = mode on ⇒ 「立即生效」, mode off ⇒ 「模式开启后生效」, A14).
     * On failure the result's stable code is fed through the one central mapping, so an unknown code is still surfaced
     * instead of being swallowed.
     */
    fun opacityText(
        saved: Boolean,
        error: String?,
        ballPercent: Int,
        bubblePercent: Int,
        detached: Boolean,
    ): String =
        if (saved) {
            val effect = if (detached) "立即生效。" else "模式开启后生效。"
            "已保存：悬浮球 $ballPercent% / 气泡 $bubblePercent%，$effect"
        } else {
            requireNotNull(
                SettingsFeedback.saveFailedText(SettingsSaveResult(saved = false, errorCode = error)),
            )
        }

    /**
     * The phone-only switch's result line.
     *
     * The two sides of the result are both the gate's own facts: [stoppedSession] distinguishes "the mode was saved
     * and the running session was ended first" from "the mode was saved", and on a refusal the same flag keeps the
     * already-completed stop visible ([DetachUi.errorText]).
     */
    fun detachText(saved: Boolean, stoppedSession: Boolean, error: String?): String =
        if (saved) {
            DetachUi.successText(stoppedSession)
        } else {
            DetachUi.errorText(error ?: "UNKNOWN", stoppedSession)
        }

    /**
     * The runtime status line, or null while the mode is off.
     *
     * A running service wins over a stale failure (the runtime clears the failure when it reaches running, but a
     * defensive projection must not contradict the truth); with the mode on and nothing running, a recorded failure is
     * the reason, otherwise the ball is simply still starting.
     */
    fun runningText(detached: Boolean, running: Boolean, startFailure: String?): String? = when {
        !detached -> null
        running -> RUNNING
        startFailure != null -> START_FAILURE_PREFIX + startFailure
        else -> NOT_RUNNING
    }
}
