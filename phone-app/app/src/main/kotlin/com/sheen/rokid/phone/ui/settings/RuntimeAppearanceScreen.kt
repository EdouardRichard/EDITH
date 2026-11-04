package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.app.HostCompositionRoot.PhoneOnlyOpacityResult
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachSetResult
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import kotlin.math.roundToInt

/**
 * 0.52 design §3.4/§4.2: the runtime and appearance page.
 *
 * It is host-free by design, like [AssistantBehaviorScreen]: the shell passes the live values and three callbacks, and
 * the page never reaches into `HostCompositionRoot`. The only host-owned types it names are the two **results** the
 * callbacks return ([DetachSetResult], [PhoneOnlyOpacityResult]); the copy those results become is decided by the pure
 * [RuntimeAppearanceUi].
 *
 * The three save paths are deliberately different, per the design's save table (§4.2):
 *
 * - **phone-only mode** is a switch: one tap calls the gate, and its single result (saved / stopped first / refused)
 *   is shown inline. The long consequences (no CXR, uploads still happen, next session takes effect) live in `(?)`.
 * - **opacity** previews on every drag tick through `onPreviewOpacity` (so the ball follows the finger), and persists
 *   once on release through `onCommitOpacity`. A refusal is visible, and the sliders stay visible with the mode off;
 *   the note says the saved values apply when the mode is next on. 「恢复默认透明度」 keeps the shipped 82/75.
 * - **speech** is a live switch; a persistence refusal is shown inline even though the live player already changed.
 *
 * Running state comes from the service's published state (never inferred from the overlay permission): the shell maps
 * `PhoneOnlyOverlayService.startFailure` to its short text and re-syncs on `ON_RESUME`, so returning from a system
 * settings screen does not leave a stale state here.
 */
@Composable
internal fun RuntimeAppearanceScreen(
    detached: Boolean,
    onDetach: (Boolean) -> DetachSetResult,
    ballOpacity: Int,
    bubbleOpacity: Int,
    onPreviewOpacity: (Int, Int) -> Unit,
    onCommitOpacity: (Int, Int) -> PhoneOnlyOpacityResult,
    speechEnabled: Boolean,
    onSpeech: (Boolean) -> SettingsSaveResult,
    phoneOnlyRunning: Boolean,
    startFailure: String?,
    help: HelpState,
) {
    var detachMessage by remember { mutableStateOf<String?>(null) }
    var detachFailed by remember { mutableStateOf(false) }
    // The slider positions are local while dragging; the live flow updates them again on every preview, so the two can
    // not disagree once the drag ends. Keying the effect on the values keeps an external seed/reset visible too.
    var ball by remember { mutableStateOf(ballOpacity.toFloat()) }
    var bubble by remember { mutableStateOf(bubbleOpacity.toFloat()) }
    var opacityMessage by remember { mutableStateOf<String?>(null) }
    var opacityFailed by remember { mutableStateOf(false) }
    var speechMessage by remember { mutableStateOf<String?>(null) }
    var speechFailed by remember { mutableStateOf(false) }
    LaunchedEffect(ballOpacity) { ball = ballOpacity.toFloat() }
    LaunchedEffect(bubbleOpacity) { bubble = bubbleOpacity.toFloat() }

    fun commitOpacity(committedBall: Int, committedBubble: Int) {
        val result = onCommitOpacity(committedBall, committedBubble)
        opacityFailed = !result.saved
        opacityMessage = RuntimeAppearanceUi.opacityText(
            saved = result.saved,
            error = result.error,
            ballPercent = committedBall,
            bubblePercent = committedBubble,
            detached = detached,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup("模式") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(RuntimeAppearanceUi.PHONE_ONLY_SWITCH, style = MaterialTheme.typography.bodyLarge)
                    HelpIcon(HelpContent.PHONE_ONLY, help)
                }
                Switch(
                    checked = detached,
                    onCheckedChange = { checked ->
                        val result = onDetach(checked)
                        detachFailed = !result.saved
                        detachMessage = RuntimeAppearanceUi.detachText(
                            saved = result.saved,
                            stoppedSession = result.stoppedSession,
                            error = result.error,
                        )
                    },
                    modifier = Modifier.semantics {
                        contentDescription = RuntimeAppearanceUi.PHONE_ONLY_SWITCH
                    },
                )
            }
            RuntimeAppearanceUi.runningText(detached, phoneOnlyRunning, startFailure)?.let { status ->
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (startFailure != null && !phoneOnlyRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            InlineResult(detachMessage, detachFailed)
        }

        SettingsGroup("外观") {
            OpacitySlider(
                title = HelpContent.BALL_OPACITY.title,
                value = ball,
                range = SmartSettings.MIN_BALL_OPACITY.toFloat()..SmartSettings.MAX_OPACITY.toFloat(),
                contentDescription = RuntimeAppearanceUi.BALL_SLIDER,
                helpText = HelpContent.BALL_OPACITY,
                help = help,
                onValueChange = { value ->
                    ball = value
                    opacityMessage = null
                    opacityFailed = false
                    onPreviewOpacity(value.roundToInt(), bubble.roundToInt())
                },
                onValueChangeFinished = { commitOpacity(ball.roundToInt(), bubble.roundToInt()) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            OpacitySlider(
                title = HelpContent.BUBBLE_OPACITY.title,
                value = bubble,
                range = SmartSettings.MIN_BUBBLE_OPACITY.toFloat()..SmartSettings.MAX_OPACITY.toFloat(),
                contentDescription = RuntimeAppearanceUi.BUBBLE_SLIDER,
                helpText = HelpContent.BUBBLE_OPACITY,
                help = help,
                onValueChange = { value ->
                    bubble = value
                    opacityMessage = null
                    opacityFailed = false
                    onPreviewOpacity(ball.roundToInt(), value.roundToInt())
                },
                onValueChangeFinished = { commitOpacity(ball.roundToInt(), bubble.roundToInt()) },
            )
            if (!detached) {
                Text(
                    RuntimeAppearanceUi.MODE_OFF_NOTE,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    val defaultBall = SmartSettings.DEFAULT_BALL_OPACITY
                    val defaultBubble = SmartSettings.DEFAULT_BUBBLE_OPACITY
                    ball = defaultBall.toFloat()
                    bubble = defaultBubble.toFloat()
                    onPreviewOpacity(defaultBall, defaultBubble)
                    commitOpacity(defaultBall, defaultBubble)
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(RuntimeAppearanceUi.RESET_OPACITY)
            }
            InlineResult(opacityMessage, opacityFailed)
        }

        SettingsGroup("语音") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(RuntimeAppearanceUi.SPEECH_SWITCH, style = MaterialTheme.typography.bodyLarge)
                    HelpIcon(HelpContent.SPEECH, help)
                }
                Switch(
                    checked = speechEnabled,
                    onCheckedChange = { checked ->
                        val result = onSpeech(checked)
                        speechFailed = !result.saved
                        speechMessage = RuntimeAppearanceUi.speechText(result)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = RuntimeAppearanceUi.SPEECH_SWITCH
                    },
                )
            }
            InlineResult(speechMessage, speechFailed)
        }
    }
}

/**
 * One opacity slider: title + `(?)` + the current percent above the track.
 *
 * The value is a `Float` because that is the slider's own granularity; the page rounds only at the callbacks, so the
 * preview and the commit always describe the same integer the host stores.
 */
@Composable
private fun OpacitySlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    contentDescription: String,
    helpText: HelpText,
    help: HelpState,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            HelpIcon(helpText, help)
            Text(
                "${value.roundToInt()}%",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { this.contentDescription = contentDescription },
        )
    }
}

/** One inline result line, success in the primary colour and refusal in error; nothing is drawn before a save. */
@Composable
private fun InlineResult(message: String?, failed: Boolean) {
    message?.let { line ->
        Text(
            line,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
