package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationRole

/**
 * Which side of the phone's assistant page one message bubble sits on (0.23 U2).
 *
 * The wearer's own words are on the right and the assistant's on the left. This is the phone-only rule: the glasses
 * render every row on the left (0.23 U9), so it deliberately does not live in `output-runtime`.
 */
enum class BubbleSide { START, END }

/** The refresh icon's content description, shared by the UI and its test so the two cannot drift. */
const val REFRESH_DESCRIPTION = "刷新对话"

/**
 * The widest a bubble may grow, as a fraction of the chat box's width.
 *
 * Short messages stay content-sized; long ones stop at 80% so the left/right alignment remains readable.
 */
const val BUBBLE_MAX_WIDTH_FRACTION = 0.8f

/** The side the bubble for [role] is aligned to (U2: assistant left, wearer right). */
fun sideFor(role: ConversationRole): BubbleSide = when (role) {
    ConversationRole.ASSISTANT -> BubbleSide.START
    ConversationRole.USER -> BubbleSide.END
}

/**
 * The visible role description carried by the bubble's avatar (owner follow-up 2026-10-17).
 *
 * The avatars are what lets a reader name the two sides at a glance; the content description carries the same words to
 * accessibility services, so "left is the assistant, right is the wearer" is not only a colour/position cue.
 */
fun roleDescription(role: ConversationRole): String = when (role) {
    ConversationRole.ASSISTANT -> "助手"
    ConversationRole.USER -> "你"
}

/** What a screen reader says for one bubble: who spoke, then the words. */
fun spokenMessage(role: ConversationRole, text: String): String = "${roleDescription(role)}说：$text"

/** The full name of the route shown in the chat box header; the segmented buttons keep the shorter 唤醒 / 主动 labels. */
fun routeLabel(route: AssistantRoute): String = when (route) {
    AssistantRoute.WAKE -> "唤醒对话"
    AssistantRoute.PROACTIVE -> "主动辅助对话"
}

/**
 * Whether a count increase should scroll the list to the newest entry.
 *
 * Only a reader already at the bottom is followed; someone reading older messages keeps their place.
 */
fun shouldAutoScroll(atBottom: Boolean, previousCount: Int, newCount: Int): Boolean =
    atBottom && newCount > previousCount

/**
 * Whether a changed last entry should scroll the list to it.
 *
 * The count alone is not enough: `ConversationLog` is bounded at 40 entries per route, so once the cap is reached an
 * append evicts the oldest entry and leaves the count unchanged. The last entry's id still changes, and that is the
 * fact the page follows. A revision that keeps the same id (a hint re-sent under its own id) is not a new entry.
 */
fun shouldFollowNewEntry(atBottom: Boolean, previousLastId: String?, currentLastId: String?): Boolean =
    atBottom && currentLastId != null && currentLastId != previousLastId

/** One visible `LazyColumn` item: its key and the screen offset of its bottom edge (`offset + size`). */
data class VisibleItem(val key: Any?, val endOffset: Int)

/**
 * Whether the reader was at the bottom before a new entry arrived.
 *
 * The **end of the previous last entry being visible** is the fact that matters, never an index/count comparison:
 *  - `ConversationLog` is bounded at 40 entries, so an append at the cap evicts the oldest entry and leaves the count
 *    unchanged; a count comparison cannot see the new entry.
 *  - A long last bubble whose tail is still below the viewport is being read, not finished; its key being visible is
 *    not enough.
 *  - A fully visible last entry means the end of the list, even when empty space sits below it.
 *
 * A null [previousLastId] means the list was empty, so the first entry is followed. A non-null previous id with no
 * visible items means the list has not been laid out yet, which is not the same thing as a reader who scrolled away.
 */
fun wasAtBottom(
    previousLastId: String?,
    visibleItems: List<VisibleItem>,
    viewportEndOffset: Int,
    bottomPaddingPx: Int,
): Boolean {
    if (previousLastId == null) return true
    val previousLast = visibleItems.firstOrNull { it.key == previousLastId } ?: return false
    // <=, not >=: the item's *end* must already be on screen. A partially visible last item has its end below the
    // viewport and is still being read.
    return previousLast.endOffset <= viewportEndOffset - bottomPaddingPx + 1
}

/** The index a freshly opened route starts at: the newest entry, or `null` when there is nothing to show. */
fun initialScrollTarget(entryCount: Int): Int? = if (entryCount > 0) entryCount - 1 else null
