package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.actions.A11Y_CHANNEL
import com.sheen.rokid.phone.actions.ExecutionChannelStatus
import com.sheen.rokid.phone.actions.INTENT_CHANNEL
import com.sheen.rokid.phone.actions.ROOT_CHANNEL
import com.sheen.rokid.phone.actions.SHIZUKU_CHANNEL
import com.sheen.rokid.phone.actions.executionChannelLabel
import com.sheen.rokid.phone.app.capabilityLabel
import com.sheen.rokid.phone.model.ModelStartGate
import com.sheen.rokid.phone.model.ProvisioningState
import com.sheen.rokid.phone.ui.capabilityReasonLabel
import com.sheen.rokid.plugin.Capability

/**
 * 0.52 design §3.6: the 本地能力 page's copy and status rules.
 *
 * The vocabulary is the legacy panel's (appendix A20), not new copy: 「可用」 and 「尚不可用（原因）」, with the reason
 * routed through the one dictionary ([capabilityReasonLabel]) and the channel names through [executionChannelLabel].
 * The two local models are the only capabilities this page calls out by name; every other unavailable capability is
 * listed under 其他能力 so a down service cannot hide by simply not being one of the six rows the page highlights.
 */
internal object LocalCapabilitiesUi {

    const val MODELS_TITLE = "本地模型"
    const val CHANNELS_TITLE = "执行通道"
    const val OTHER_TITLE = "其他能力"

    const val AVAILABLE = "可用"
    const val UNAVAILABLE = "尚不可用"

    /**
     * What a channel row says before the host has published any: the probes run around a session, so a process that has
     * not started one has no answer. Unknown is not 不可用 - the page must not turn "not checked yet" into a failure.
     */
    const val CHANNEL_NOT_PROBED = "尚未运行，开始会话后检测"

    /** The two local models the assistant cannot run without (appendix A20 / design §3.6). */
    val LOCAL_MODELS = listOf(Capability.SystemOne, Capability.Embedding)

    /** The four execution channels in the legacy panel's order; named even before the first session publishes rows. */
    val EXECUTION_CHANNELS = listOf(INTENT_CHANNEL, A11Y_CHANNEL, SHIZUKU_CHANNEL, ROOT_CHANNEL)

    /** One status cell: 可用, or 尚不可用 with the dictionary's label (or the raw code, if the code is unknown). */
    fun statusText(available: Boolean, reason: String?): String = when {
        available -> AVAILABLE
        reason != null -> "$UNAVAILABLE（${capabilityReasonLabel(reason)}）"
        else -> UNAVAILABLE
    }

    /**
     * A local model row: absence from the map [localCapabilityReasons] merges means the environment answers the
     * capability **and** the bundled-model gate reports it ready.
     */
    fun modelStatus(reason: String?): String = statusText(reason == null, reason)

    /**
     * The channel lines to draw.
     *
     * The host publishes `executionChannels` around a session's start/stop, so before the first session the state is
     * empty. Rendering nothing would hide the whole section (the fix round 1 finding); rendering 可用 would invent an
     * answer. The page therefore names the four known channels with [CHANNEL_NOT_PROBED] and never probes `su` itself:
     * opening Settings must not make a root manager prompt. A published list is rendered exactly as the probes gave it.
     */
    fun channelLines(channels: List<ExecutionChannelStatus>): List<ChannelLine> =
        if (channels.isEmpty()) {
            EXECUTION_CHANNELS.map { id ->
                ChannelLine(
                    label = executionChannelLabel(id),
                    available = false,
                    status = CHANNEL_NOT_PROBED,
                    unknown = true,
                )
            }
        } else {
            channels.map { channel ->
                ChannelLine(
                    label = executionChannelLabel(channel.id),
                    available = channel.available,
                    status = channelStatus(channel),
                    unknown = false,
                )
            }
        }

    /**
     * An execution channel row. Kept byte-for-byte in behaviour with the legacy panel: the Root row's [note] is the
     * probe's own sentence (it distinguishes no-su, timeout and not-root, which the single `ROOT_UNAVAILABLE` code
     * cannot), so it wins over the generic dictionary label when present.
     */
    fun channelStatus(channel: ExecutionChannelStatus): String = when {
        channel.available -> AVAILABLE
        channel.note != null -> channel.note
        else -> statusText(false, channel.reason)
    }
}

/** One 执行通道 row; [unknown] separates "not probed yet" from a channel that was probed and is known to be down. */
internal data class ChannelLine(
    val label: String,
    val available: Boolean,
    val status: String,
    val unknown: Boolean = false,
)

/**
 * 0.52 design §3.6 (fix round 1): the capability map the shell hands to [LocalCapabilitiesScreen].
 *
 * `SessionUiState.capabilityReasons` can only name capabilities the host environment does **not** advertise, and the
 * production composition always advertises `Capability.SystemOne`/`Embedding` (the services exist and refuse their own
 * calls). Readiness lives in the bundled-model gate instead, so a page fed the raw map could never tell a failed model
 * bundle from a ready one. Merging the gate's refusal here restores the design's available/unavailable + reason rule
 * without changing the page: the same map-absence-means-可用 path renders it.
 *
 * Null readiness keeps the pre-0.4 behavior for hand-built compositions and JVM rigs: nothing is invented about models.
 */
internal fun localCapabilityReasons(
    capabilityReasons: Map<Capability, String>,
    readiness: ProvisioningState?,
): Map<Capability, String> {
    val refusal = ModelStartGate.refusal(readiness) ?: return capabilityReasons
    return capabilityReasons + LocalCapabilitiesUi.LOCAL_MODELS.associateWith { refusal }
}

/**
 * 0.52 design §3.6: the read-only local-capability status page.
 *
 * It answers "is the local stack usable, and if not, why": the two local models (决策/嵌入) and the execution channels
 * (指令执行/无障碍/Shizuku/Root), then any other capability the environment currently refuses. Values come from
 * [com.sheen.rokid.phone.session.SessionUiState] plus the shell's [localCapabilityReasons] merge (the models are
 * advertised unconditionally, so their availability is the bundle gate, not the capability map) - the page is a pure
 * function of its inputs and has no host dependency, no save, no switch and no action. Being read-only, it also has no
 * back guard: leaving is always safe.
 *
 * [help] is accepted because the approved page interface carries the settings page-level [HelpState]; appendix A20 is
 * status vocabulary rather than a prose explanation, so this page opens no popover today. The parameter stays because
 * Task 15's copy sweep may move a sentence into one, and removing it now would be a source-breaking change for the shell.
 */
@Composable
internal fun LocalCapabilitiesScreen(
    capabilityReasons: Map<Capability, String>,
    channels: List<ExecutionChannelStatus>,
    help: HelpState,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsGroup(LocalCapabilitiesUi.MODELS_TITLE) {
            LocalCapabilitiesUi.LOCAL_MODELS.forEachIndexed { index, capability ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                CapabilityRow(
                    label = capabilityLabel(capability),
                    available = capability !in capabilityReasons,
                    status = LocalCapabilitiesUi.modelStatus(capabilityReasons[capability]),
                )
            }
        }

        // The host publishes channel rows around a session's start/stop. Before the first session the state is empty;
        // the page still names the four known channels, each with the unknown reason, instead of hiding the section
        // (fix round 1). It reads only what it was given and never probes `su` just because Settings was opened.
        SettingsGroup(LocalCapabilitiesUi.CHANNELS_TITLE) {
            LocalCapabilitiesUi.channelLines(channels).forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                CapabilityRow(
                    label = line.label,
                    available = line.available,
                    status = line.status,
                    unknown = line.unknown,
                )
            }
        }

        // Everything else the environment refuses. Sorted by the enum's own order so the list cannot reorder between
        // reads whose map happens to be built in a different order.
        val others = capabilityReasons.entries
            .filter { it.key !in LocalCapabilitiesUi.LOCAL_MODELS }
            .sortedBy { it.key.ordinal }
        if (others.isNotEmpty()) {
            SettingsGroup(LocalCapabilitiesUi.OTHER_TITLE) {
                others.forEachIndexed { index, (capability, reason) ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CapabilityRow(
                        label = capabilityLabel(capability),
                        available = false,
                        status = LocalCapabilitiesUi.statusText(available = false, reason = reason),
                    )
                }
            }
        }
    }
}

/**
 * One status row: the platform [ListItem] shape the legacy panel used, with a check/info leading icon.
 *
 * [unknown] is the third state fix round 1 adds: a row the host has not probed yet. It reads as neutral information,
 * not as the error-red known-unavailable state.
 */
@Composable
private fun CapabilityRow(label: String, available: Boolean, status: String, unknown: Boolean = false) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (available || unknown) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (available) Icons.Default.Check else Icons.Default.Info,
                contentDescription = null,
            )
        },
    )
}
