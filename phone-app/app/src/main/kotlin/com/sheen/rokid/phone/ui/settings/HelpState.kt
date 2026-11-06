package com.sheen.rokid.phone.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * One option's `(?)` explanation.
 *
 * [title] is the option name and is what the icon's content description and the popover heading use. [body] is the
 * migrated appendix/design copy (Tasks 9–14 pass [HelpContent]'s constants). [impact] is the optional
 * 「影响：需重启/立即」 line; it is null for read-only or action explanations, never a place for risk text (the risk
 * boundary belongs in [body]).
 */
data class HelpText(
    val title: String,
    val body: String,
    val impact: String? = null,
)

/**
 * Page-level `(?)` state: at most one popover is open, keyed by the option name ([HelpText.title]).
 *
 * Deliberately **not** saveable. Design §4.1 requires rotation/recreation to close the popover, so the state must die
 * with the composition. [open] replaces any previous id instead of adding to it; [close] clears both.
 *
 * [HelpIcon] renders the popover for the icon whose id equals [openId], so the content and the anchor are the same
 * node's — no page-level host has to know where the icon was placed.
 */
@Stable
class HelpState internal constructor() {

    /** The option name of the open popover, or null. */
    var openId: String? by mutableStateOf(null)
        private set

    /** The content handed to [open]; kept so the open popover survives this icon's recomposition unchanged. */
    internal var openText: HelpText? by mutableStateOf(null)
        private set

    /** Opens [text] under [id], replacing any popover that was open before (only one is allowed at a time). */
    fun open(id: String, text: HelpText) {
        openText = text
        openId = id
    }

    /** Closes the open popover, if any. */
    fun close() {
        openId = null
        openText = null
    }
}

/** The page-level `(?)` state; see [HelpState] for why this is `remember` rather than `rememberSaveable`. */
@Composable
fun rememberHelpState(): HelpState = remember { HelpState() }
