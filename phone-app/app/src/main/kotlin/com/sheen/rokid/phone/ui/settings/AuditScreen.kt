package com.sheen.rokid.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 0.52 design §3.7: the audit trail's page copy and its legacy render limit.
 *
 * The page is a **migration** of the old `AuditSection`, so the entries are the old ones: the newest 50 lines, the
 * 「暂无记录」 empty state and the immediate 「清空审计记录」 button. The document keeps its own 200-line bound (that is
 * what [HelpContent.AUDIT] says and this page does not change it); the 200/50 difference is the recorded legacy
 * discrepancy, not something this task "fixes".
 */
internal object AuditUi {

    /** Shown instead of the list when the document is empty or unreadable. */
    const val EMPTY = "暂无记录"

    /** The one action: remove the whole document immediately, with no second confirmation (design §3.7). */
    const val CLEAR = "清空审计记录"

    /** 页面 50: the legacy page rendered at most this many newest lines; the store still keeps 200. */
    const val PAGE_LIMIT = 50
}

/**
 * 0.52 design §3.7/G6: the audit trail page - **the only place that reads the log**.
 *
 * [loadLines] is the caller's snapshot reader (task 12's shell passes `AuditLogView.lines`, which is newest-first and
 * limited to 50). It is called from [remember] keyed on `revision`, i.e. once when the page enters composition and once
 * after each clear - never on a plain recomposition and never while the home is the only screen composed.
 *
 * [onClear] is the document writer. The page does not own the document and deliberately has no second confirmation:
 * design §3.7 keeps the old immediate behaviour, and a future confirmation is a separate review.
 *
 * The explanation is appendix A10 verbatim, taken from [HelpContent.AUDIT] so the visible text and any `(?)` copy cannot
 * drift apart. Rows stay plain `Text` nodes so the whole list is selectable and copyable through the page dialog.
 */
@Composable
internal fun AuditScreen(
    loadLines: () -> List<String>,
    onClear: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    // One read per page open and per clear; the loader's own rule is newest-first, this page keeps the legacy 50 cap.
    val lines = remember(revision) { loadLines().take(AuditUi.PAGE_LIMIT) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(HelpContent.AUDIT.body, style = MaterialTheme.typography.bodySmall)
        if (lines.isEmpty()) {
            Text(AuditUi.EMPTY, style = MaterialTheme.typography.bodyMedium)
        } else {
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = {
            onClear()
            revision++
        }) {
            Text(AuditUi.CLEAR)
        }
    }
}
