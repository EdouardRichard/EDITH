package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * 0.52 design §3.2: one card of compact settings rows under a group label.
 *
 * The home has no switches and no explanatory paragraphs; each group is a rounded surface and each row is a
 * [ListItem] so the touch target, typography and screen-reader shape are the platform's rather than this file's.
 */
@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * One navigation row: the whole row is the click target, the chevron only says "opens".
 *
 * [summaryIsError] paints the summary with `colorScheme.error` and publishes `stateDescription = 错误`, so the
 * unavailable model state is not carried by colour alone (the accessibility tree says it too).
 * [trailingStatus] is the row's red count (「N 项待授权」/「N 项不可用」), not another tap target.
 */
@Composable
internal fun SettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    summaryIsError: Boolean = false,
    trailingStatus: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (summaryIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "打开$title", onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (summaryIsError) stateDescription = "错误"
            },
    )
}
