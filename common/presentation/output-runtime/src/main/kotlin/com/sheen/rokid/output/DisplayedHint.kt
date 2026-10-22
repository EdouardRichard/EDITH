package com.sheen.rokid.output

import com.sheen.rokid.contracts.AssistantRoute

/**
 * A hint the [GlassHintController] accepted and committed to the screen.
 *
 * This is what the render callback receives instead of a bare `String` (Phase C, spec §6.6). The view has to file
 * the hint into the conversation named by [route] and remember which entry it was, and only the controller knows
 * whether the hint was real: it owns the session, generation, sequence, revision, mapping and deadline gates, and a
 * refused `SHOW` must not enter the wearer's conversation.
 *
 * [text] is the raw wire text — the view still lays it out through `HintPresentation`, because turning sentences
 * into lines is a rendering decision, not a wire decision.
 */
data class DisplayedHint(
    val text: String,
    val route: AssistantRoute,
    val hintId: String,
)
