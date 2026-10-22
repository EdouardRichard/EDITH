package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase

/**
 * Phase C (spec §6.6 / §6.7): the plugin's side of the conversation the wearer reads back from.
 *
 * This is what turns "the assistant is doing something" into two visible things: the phone's own record of the
 * conversation, and the traffic that puts it on the glasses. The plugin knows *when* — a turn opened, an utterance was
 * accepted, a hint went out, a turn ended in silence — and this port is how that knowledge leaves the plugin without
 * the plugin ever touching the wire.
 *
 * **It is optional.** A host with no display and no conversation record simply never provides
 * [Capability.ConversationDisplay], and the plugin treats every call as best-effort: a missing port must never stop a
 * turn, exactly as a missing speech player must not stop a hint from being shown.
 *
 * Two facts about the shapes:
 *
 * - [userMessage] and [assistantMessage] are **statements about the conversation**; the glasses render the wearer's
 *   side from the former and the assistant's side from the hint itself, so an assistant message is not sent as an
 *   utterance.
 * - [working] and [idle] are a **level**, not a pair of events with meaning in their own right: the later statement
 *   wins, and a caller that reports the same level twice has said nothing new. The receiving side bounds the level in
 *   time as well, because a single missing `idle` would otherwise leave a spinner turning and a panel lit forever.
 *
 * 0.24 (spec §5.5) adds the official conversation's finer-grained statements — the status level, the official idle,
 * streaming revisions, the tool row, and panel open/close. They are **default no-ops**: an implementation written
 * before 0.24 keeps compiling and behaving exactly as it did, and a host with nowhere to put a signal ignores it.
 */
interface ConversationDisplay {

    /** One utterance the wearer made, for the conversation named by [route]. */
    fun userMessage(route: AssistantRoute, text: String)

    /** One message the assistant is telling the wearer, for the conversation named by [route]. */
    fun assistantMessage(route: AssistantRoute, text: String)

    /** The assistant has started working on [route]. */
    fun working(route: AssistantRoute)

    /** The assistant has stopped working on [route]: a hint went out, the turn was cancelled, or it timed out. */
    fun idle(route: AssistantRoute)

    /**
     * 0.24 spec §5.5: the official conversation status for [route], as the wire status int (0–7; the phone passes
     * `OfficialStatus.wire`). A later [sequence] replaces an earlier one within the same turn.
     *
     * Status 0 is the official `showNoneView`, **not** the idle state; [showIdle] carries `showIdleStatus`. Default
     * no-op: a host that predates the official conversation has nowhere to put a level and must keep its old behavior.
     */
    fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {}

    /**
     * 0.24 spec §5.5: the official `showIdleStatus` for [route] — the waiting/thinking rows go away and the current
     * answer is finished, while the panel and the conversation stay in place.
     *
     * This is not [idle]: that reports the busy level. Default no-op for hosts that only know the busy level.
     */
    fun showIdle(route: AssistantRoute, sequence: Long) {}

    /**
     * 0.24 spec §5.5: the wearer's streaming ASR text for [turnId], one [revision] at a time.
     *
     * [userMessage] remains the final statement; this only revises the line the glasses are already showing.
     */
    fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) {}

    /**
     * 0.24 spec §5.5/§6: the wearer's **final** utterance for [turnId], recorded under the exact [entryId] its
     * streaming revisions used (`asr-${turnId}` by convention).
     *
     * Unlike [userMessage] this carries the turn and the stable entry id, so the final replaces the line the glasses
     * already showed instead of appending a second row. Default no-op: a host that predates the official conversation
     * keeps its old behavior.
     */
    fun userFinal(route: AssistantRoute, turnId: String, entryId: String, text: String) {}

    /**
     * 0.24 spec §5.5: the assistant's streaming answer for [hintId] within [turnId], one [revision] at a time.
     *
     * [assistantMessage] remains the final statement; this only revises the answer line the wearer is reading.
     */
    fun assistantPartial(route: AssistantRoute, turnId: String, hintId: String, revision: Long, text: String) {}

    /**
     * 0.24 spec §5.5/§6: the assistant's **final** answer for [turnId], recorded under the exact entry id its
     * streaming revisions used (`say-${hintId}` by convention).
     *
     * Task 16: the final answer already reaches the glasses as the hint (`SHOW`), so this carries only the phone's own
     * record. Like [userFinal] it names the turn and the hint, so the final replaces the line
     * [assistantPartial] opened even when no revision was ever streamed. Default no-op for a host that predates the
     * official conversation.
     */
    fun assistantFinal(route: AssistantRoute, turnId: String, hintId: String, text: String) {}

    /**
     * 0.24 S4 Task 16 (I1): an assistant statement that is **not** this turn's answer - the explicit-memory
     * acknowledgement is the current producer.
     *
     * The host records it as its own conversation entry. It must never replace or finalise the answer entry
     * (`say-${hintId}`): the acknowledgement can be emitted after a `notify_user` answer, or while a closing answer is
     * still being assembled. The statement already reaches the glasses as the hint (`SHOW`), so this carries only the
     * phone's own record. Default no-op for a host that predates the official conversation.
     */
    fun assistantNotice(route: AssistantRoute, text: String) {}

    /**
     * 0.24 spec §5.5: a tool row for the action labelled [label], before ([ToolPhase.START]) and after
     * ([ToolPhase.END]) it runs.
     *
     * [ToolPhase] lives in `core-contracts` so this port and the wire codec cannot drift into two enums that spell
     * the same phase differently. Default no-op for hosts with no tool row.
     */
    fun toolStatus(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) {}

    /** 0.24 spec §5.5: show the conversation panel for [route] without changing its status. */
    fun panelOpen(route: AssistantRoute) {}

    /** 0.24 spec §5.5: close the conversation panel for [route] and drop the display's cached rows. */
    fun closePanel(route: AssistantRoute) {}
}
