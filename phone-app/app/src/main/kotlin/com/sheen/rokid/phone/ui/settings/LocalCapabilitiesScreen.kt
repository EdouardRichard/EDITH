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
import com.sheen.rokid.phone.actions.ExecutionChannelStatus
import com.sheen.rokid.phone.actions.executionChannelLabel
import com.sheen.rokid.phone.app.capabilityLabel
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

    /** The two local models the assistant cannot run without (appendix A20 / design §3.6). */
    val LOCAL_MODELS = listOf(Capability.SystemOne, Capability.Embedding)

    /** One status cell: 可用, or 尚不可用 with the dictionary's label (or the raw code, if the code is unknown). */
    fun statusText(available: Boolean, reason: String?): String = when {
        available -> AVAILABLE
        reason != null -> "$UNAVAILABLE（${capabilityReasonLabel(reason)}）"
        else -> UNAVAILABLE
    }

    /** A local model row: absence from `capabilityReasons` means the environment answers this capability. */
    fun modelStatus(reason: String?): String = statusText(reason == null, reason)

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

/**
 * 0.52 design §3.6: the read-only local-capability status page.
 *
 * It answers "is the local stack usable, and if not, why": the two local models (决策/嵌入) and the execution channels
 * (指令执行/无障碍/Shizuku/Root), then any other capability the environment currently refuses. Values come from
 * [com.sheen.rokid.phone.session.SessionUiState] - the page is a pure function of them and has no host dependency, no
 * save, no switch and no action. Being read-only, it also has no back guard: leaving is always safe.
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

        // The host publishes channel rows around a session's start/stop. A composition that has not seen one yet
        // (the settings tab can be opened before the first session) has no rows to show, and this page will not
        // invent availability it was not given - so it omits the section rather than drawing a blank one.
        if (channels.isNotEmpty()) {
            SettingsGroup(LocalCapabilitiesUi.CHANNELS_TITLE) {
                channels.forEachIndexed { index, channel ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CapabilityRow(
                        label = executionChannelLabel(channel.id),
                        available = channel.available,
                        status = LocalCapabilitiesUi.channelStatus(channel),
                    )
                }
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

/** One status row: the platform [ListItem] shape the legacy panel used, with a check/info leading icon. */
@Composable
private fun CapabilityRow(label: String, available: Boolean, status: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (available) {
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
