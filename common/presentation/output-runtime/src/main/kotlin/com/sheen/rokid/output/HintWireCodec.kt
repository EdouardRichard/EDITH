package com.sheen.rokid.output

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.ToolPhase

/**
 * Versioned hint wire shared by the phone and the glasses CustomApp (wire version 1).
 *
 * Clock domains are distinguished in the field names on purpose: `phoneSentNanos` is a phone instant and
 * `validUntilGlassNanos` is a glasses instant, so a raw phone nanosecond can never be mistaken for a glasses
 * clock reading. The transfer is carried inside a `Caps` string via `rk_custom_client`; the reply channel is
 * `rk_custom_key`.
 *
 * This type is the untrusted boundary. The SDK promises nothing about ordering, size or duplication, so
 * [decode] refuses anything it does not fully understand and never throws at the caller.
 */
sealed interface HintCommand {
    data class Hello(val sessionId: String, val generation: Long) : HintCommand
    data class Ping(val syncId: String, val p0: Long) : HintCommand
    data class Pong(val syncId: String, val p0: Long, val g1: Long, val g2: Long) : HintCommand
    data class Sync(val syncId: String, val offsetLowerNanos: Long, val offsetUpperNanos: Long,
        val validUntilGlassNanos: Long) : HintCommand

    /**
     * Puts [text] on the glasses for the conversation named by [route], and asks to be told what happened to it.
     *
     * [route] is **last** so that the eight callers that predate it keep compiling positionally; on the wire it is
     * written right after `generation`, because the encoder fixes the field order and a reader should meet the
     * routing decision before the payload. An absent `route` reads as [AssistantRoute.WAKE] — that is what a peer
     * built before Phase C was sending — while a route this build does not know is refused outright.
     */
    data class Show(val sessionId: String, val generation: Long, val sequence: Long, val hintId: String,
        val revision: Long, val syncId: String, val phoneSentNanos: Long, val remainingTtlMillis: Long,
        val text: String, val route: AssistantRoute = AssistantRoute.WAKE) : HintCommand

    data class Clear(val sessionId: String, val generation: Long, val sequence: Long) : HintCommand
    data class Ack(val sessionId: String, val generation: Long, val sequence: Long, val hintId: String,
        val revision: Long, val status: OutputStatus) : HintCommand

    /**
     * One thing the wearer said, for the conversation named by [route] (Phase C, spec §6.6 左AI右用户).
     *
     * A record, not a display request: there is no deadline, no sync id and no acknowledgement, because an utterance
     * does not expire and nothing needs confirming about it. [entryId] is the identity the receiving side dedupes on
     * — the same utterance delivered twice must not appear twice in the conversation.
     */
    data class Said(val sessionId: String, val generation: Long, val sequence: Long, val route: AssistantRoute,
        val entryId: String, val text: String) : HintCommand

    /**
     * Whether the assistant is working on the conversation named by [route] (Phase C, spec §6.6 加载动画).
     *
     * A **level, not an event**: there is no sequence, because two `BUSY` messages for the same route are the same
     * statement and the later one simply wins. The glasses bound it locally as well — a level that is never cleared
     * must not be able to spin the animation and hold the panel awake forever.
     */
    data class Busy(val sessionId: String, val generation: Long, val route: AssistantRoute,
        val busy: Boolean) : HintCommand

    /**
     * 0.23 P5 (spec §5.3.3, D134): the wearer's double-click on the glasses, travelling **glasses -> phone**.
     *
     * Additive on `wireVersion = 1`: an event, not a display request, so there is no sequence and no ACK, and a repeat
     * is idempotent. An old decoder answers null for an unknown command (safe failure); the phone validates the pair
     * against the session it established before it cancels anything.
     */
    data class Stop(val sessionId: String, val generation: Long) : HintCommand

    /**
     * A status level for [route] (0.24 spec §5.4, official status 0–7).
     *
     * `status` 0 is the official `showNoneView` — **not** idle; IDLE is its own command because it also finishes the
     * current item and hides the Hi/thinking/"…" rows. [sequence] shares IDLE's domain: a later status for the same
     * turn replaces an earlier one, and a state older than the last sequence the glasses applied is dropped there.
     */
    data class State(val sessionId: String, val generation: Long, val route: AssistantRoute,
        val turnId: String, val status: Int, val sequence: Long) : HintCommand

    /**
     * The official `showIdleStatus` for [route]: hide Hi/thinking/"…", finish the current item, keep the panel and its
     * content. It does not open a listening turn and carries the same [sequence] domain as [State].
     */
    data class Idle(val sessionId: String, val generation: Long, val route: AssistantRoute,
        val sequence: Long) : HintCommand

    /**
     * A live user-transcript increment for [route]; the final utterance still travels as [Said].
     *
     * [revision] is monotonic within [turnId]. The encoder keeps only the last
     * [MAX_TEXT_CODEPOINTS] code points (without splitting a surrogate pair), because this is a display increment:
     * the glasses accept what arrives instead of rejecting an otherwise valid row for length.
     */
    data class Asr(val sessionId: String, val generation: Long, val route: AssistantRoute,
        val turnId: String, val revision: Long, val text: String) : HintCommand

    /**
     * A live answer-text increment for [route]; the final answer still travels as [Show].
     *
     * [hintId] ties the increment to the final hint, and [revision] is monotonic within [turnId]. Text is trimmed
     * to the last [MAX_TEXT_CODEPOINTS] code points at encode time, like [Asr].
     */
    data class Answer(val sessionId: String, val generation: Long, val route: AssistantRoute,
        val turnId: String, val hintId: String, val revision: Long, val text: String) : HintCommand

    /** A tool row for [route]: [phase] is spelled `start`/`end` on the wire. */
    data class Tool(val sessionId: String, val generation: Long, val route: AssistantRoute,
        val turnId: String, val phase: ToolPhase, val label: String) : HintCommand

    /**
     * Close the panel for [route] and clear the glasses cache; a `null` [route] means every route.
     *
     * The wire spells the whole-panel close as the literal `ALL`, which is not an [AssistantRoute] value.
     */
    data class Close(val sessionId: String, val generation: Long, val route: AssistantRoute?) : HintCommand

    /**
     * Glasses -> phone: the wearer pinned the conversation named by [route].
     *
     * One-way by design — once pinned, the panel is not unpinned by a message — so there is no boolean on the wire.
     */
    data class Pin(val sessionId: String, val generation: Long, val route: AssistantRoute) : HintCommand

    /** Show the panel for [route] without changing its status. */
    data class PanelOpen(val sessionId: String, val generation: Long, val route: AssistantRoute) : HintCommand

    /** Glasses -> phone: the panel for [route] is visible; the phone may start its listening timer. */
    data class PanelShown(val sessionId: String, val generation: Long, val route: AssistantRoute) : HintCommand

    /** Glasses -> phone: the panel service is alive and can receive commands. Not route-scoped. */
    data class UiReady(val sessionId: String, val generation: Long) : HintCommand

    /** Glasses -> phone: the panel for [route] is hidden; the resident service is still alive. */
    data class UiClosed(val sessionId: String, val generation: Long, val route: AssistantRoute) : HintCommand

    /**
     * 0.25 Task 11 (R51): whether a confirmation question is up, travelling **phone -> glasses**.
     *
     * A **level, not an event**, like [Busy]: there is no sequence, because two statements with the same [active]
     * value are the same statement and the later one simply wins. A repeat is therefore idempotent. [requestId] is
     * the confirmation's own address (the plugin's UUID, not a hint or tool id); the glasses echo it back in the
     * [ConfirmDecision] so a late key press cannot answer a different question.
     */
    data class ConfirmState(val sessionId: String, val generation: Long, val requestId: String,
        val active: Boolean) : HintCommand

    /**
     * 0.25 Task 11 (R51): the wearer's ring decision on a confirmation, travelling **glasses -> phone**.
     *
     * An event: no sequence and no ACK, because it settles a question that [ConfirmState] already addressed. A repeat
     * is idempotent - the phone's presenter settles at most once - and the phone validates the session/generation
     * before delivering it, exactly like [Stop]/[Pin].
     */
    data class ConfirmDecision(val sessionId: String, val generation: Long, val requestId: String,
        val approved: Boolean) : HintCommand
}

object HintWireCodec {
    const val WIRE_VERSION = 1

    /**
     * Control text is bounded so a hint can never be used to push a document onto the glasses.
     *
     * **Raised from 120 to 400 by the owner's 2026-10-14 ruling**: 超长通知不能截断，要推送眼镜端滚动显示. The old
     * bound was doing double duty as a *display* bound, and the glasses' six-line window is not a display bound at all
     * — a taller hint is slid through the window by `HintAutoScroll`, which is why a 120-codepoint answer was already
     * being scrolled before this change. 400 codepoints is about 35 lines (5 screens) at the measured 12 characters
     * per line: a paragraph, not a document. The phone's own acceptance bound (`com.sheen.rokid.host.HintPolicy`) is kept equal
     * to this one, and `HintWireCodecTest` asserts the two agree so neither side can drift into refusing a hint the
     * other would have allowed.
     */
    const val MAX_TEXT_CODEPOINTS = 400
    /** Hard bound on an inbound message, checked before parsing. */
    const val MAX_MESSAGE_CHARS = 65_536

    private val TEXT_BEARING = setOf("SHOW")

    /**
     * Encodes a command. Refuses anything that would violate the wire contract rather than sending it: a
     * blank identifier, control text over [MAX_TEXT_CODEPOINTS] codepoints, or a payload over the bound.
     */
    fun encode(command: HintCommand): String {
        validate(command)
        val fields = LinkedHashMap<String, Any>()
        fields["wireVersion"] = WIRE_VERSION
        when (command) {
            is HintCommand.Hello -> {
                fields["command"] = "HELLO"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
            }
            is HintCommand.Ping -> {
                fields["command"] = "PING"
                fields["syncId"] = command.syncId
                fields["p0"] = command.p0
            }
            is HintCommand.Pong -> {
                fields["command"] = "PONG"
                fields["syncId"] = command.syncId
                fields["p0"] = command.p0
                fields["g1"] = command.g1
                fields["g2"] = command.g2
            }
            is HintCommand.Sync -> {
                fields["command"] = "SYNC"
                fields["syncId"] = command.syncId
                fields["offsetLowerNanos"] = command.offsetLowerNanos
                fields["offsetUpperNanos"] = command.offsetUpperNanos
                fields["validUntilGlassNanos"] = command.validUntilGlassNanos
            }
            is HintCommand.Show -> {
                fields["command"] = "SHOW"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                fields["sequence"] = command.sequence
                fields["hintId"] = command.hintId
                fields["revision"] = command.revision
                fields["syncId"] = command.syncId
                fields["phoneSentNanos"] = command.phoneSentNanos
                fields["remainingTtlMillis"] = command.remainingTtlMillis
                fields["text"] = command.text
            }
            is HintCommand.Clear -> {
                fields["command"] = "CLEAR"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["sequence"] = command.sequence
            }
            is HintCommand.Ack -> {
                fields["command"] = "ACK"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["sequence"] = command.sequence
                fields["hintId"] = command.hintId
                fields["revision"] = command.revision
                fields["status"] = command.status.name
            }
            is HintCommand.Said -> {
                fields["command"] = "SAID"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["sequence"] = command.sequence
                fields["route"] = command.route.name
                fields["entryId"] = command.entryId
                fields["text"] = command.text
            }
            is HintCommand.Busy -> {
                fields["command"] = "BUSY"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                // The flat reader carries strings and integers only, so a boolean travels as 1/0.
                fields["busy"] = if (command.busy) 1 else 0
            }
            is HintCommand.Stop -> {
                fields["command"] = "STOP"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
            }
            is HintCommand.State -> {
                fields["command"] = "STATE"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                fields["turnId"] = command.turnId
                fields["status"] = command.status
                fields["sequence"] = command.sequence
            }
            is HintCommand.Idle -> {
                fields["command"] = "IDLE"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                fields["sequence"] = command.sequence
            }
            is HintCommand.Asr -> {
                fields["command"] = "ASR"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                fields["turnId"] = command.turnId
                fields["revision"] = command.revision
                fields["text"] = command.text.takeLastCodePoints(MAX_TEXT_CODEPOINTS)
            }
            is HintCommand.Answer -> {
                fields["command"] = "ANSWER"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                fields["turnId"] = command.turnId
                fields["hintId"] = command.hintId
                fields["revision"] = command.revision
                fields["text"] = command.text.takeLastCodePoints(MAX_TEXT_CODEPOINTS)
            }
            is HintCommand.Tool -> {
                fields["command"] = "TOOL"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
                fields["turnId"] = command.turnId
                fields["phase"] = when (command.phase) {
                    ToolPhase.START -> "start"
                    ToolPhase.END -> "end"
                }
                fields["label"] = command.label
            }
            is HintCommand.Close -> {
                fields["command"] = "CLOSE"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                // `null` means every route; "ALL" is a wire literal, not an AssistantRoute name.
                fields["route"] = command.route?.name ?: "ALL"
            }
            is HintCommand.Pin -> {
                fields["command"] = "PIN"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
            }
            is HintCommand.PanelOpen -> {
                fields["command"] = "PANEL_OPEN"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
            }
            is HintCommand.PanelShown -> {
                fields["command"] = "PANEL_SHOWN"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
            }
            is HintCommand.UiReady -> {
                fields["command"] = "UI_READY"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
            }
            is HintCommand.UiClosed -> {
                fields["command"] = "UI_CLOSED"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["route"] = command.route.name
            }
            is HintCommand.ConfirmState -> {
                fields["command"] = "CONFIRM_STATE"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["requestId"] = command.requestId
                // The flat reader carries strings and integers only, so a boolean travels as 1/0 (as BUSY does).
                fields["active"] = if (command.active) 1 else 0
            }
            is HintCommand.ConfirmDecision -> {
                fields["command"] = "CONFIRM_DECISION"
                fields["sessionId"] = command.sessionId
                fields["generation"] = command.generation
                fields["requestId"] = command.requestId
                fields["approved"] = if (command.approved) 1 else 0
            }
        }
        val json = buildString {
            append('{')
            fields.entries.forEachIndexed { position, (key, value) ->
                if (position > 0) append(',')
                append('"').append(key).append("\":")
                when (value) {
                    is String -> append('"').append(escape(value)).append('"')
                    is Long -> append(value)
                    is Int -> append(value)
                    else -> error("unsupported wire value for $key")
                }
            }
            append('}')
        }
        require(json.length <= MAX_MESSAGE_CHARS) { "MESSAGE_TOO_LARGE" }
        return json
    }

    /** Returns null for anything not fully understood: malformed JSON, wrong version, missing or mistyped. */
    fun decode(json: String): HintCommand? {
        if (json.isBlank() || json.length > MAX_MESSAGE_CHARS) return null
        val fields = parseFlatObject(json) ?: return null
        if (fields.rawNumber("wireVersion") != WIRE_VERSION.toString()) return null
        return when (val command = fields.text("command")) {
            "HELLO" -> HintCommand.Hello(
                sessionId = fields.text("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null)
            "PING" -> HintCommand.Ping(
                syncId = fields.text("syncId") ?: return null,
                p0 = fields.long("p0") ?: return null)
            "PONG" -> HintCommand.Pong(
                syncId = fields.text("syncId") ?: return null,
                p0 = fields.long("p0") ?: return null,
                g1 = fields.long("g1") ?: return null,
                g2 = fields.long("g2") ?: return null)
            "SYNC" -> HintCommand.Sync(
                syncId = fields.text("syncId") ?: return null,
                offsetLowerNanos = fields.long("offsetLowerNanos") ?: return null,
                offsetUpperNanos = fields.long("offsetUpperNanos") ?: return null,
                validUntilGlassNanos = fields.long("validUntilGlassNanos") ?: return null)
            "SHOW" -> {
                val text = fields.text("text") ?: return null
                if (text.codePointCount() > MAX_TEXT_CODEPOINTS) return null
                HintCommand.Show(
                    sessionId = fields.text("sessionId") ?: return null,
                    generation = fields.long("generation") ?: return null,
                    sequence = fields.long("sequence") ?: return null,
                    hintId = fields.text("hintId") ?: return null,
                    revision = fields.long("revision") ?: return null,
                    syncId = fields.text("syncId") ?: return null,
                    phoneSentNanos = fields.long("phoneSentNanos") ?: return null,
                    remainingTtlMillis = fields.long("remainingTtlMillis") ?: return null,
                    text = text,
                    route = fields.route() ?: return null)
            }
            "CLEAR" -> HintCommand.Clear(
                sessionId = fields.text("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                sequence = fields.long("sequence") ?: return null)
            "ACK" -> {
                val status = fields.text("status")?.let { name ->
                    OutputStatus.entries.firstOrNull { it.name == name }
                } ?: return null
                HintCommand.Ack(
                    sessionId = fields.text("sessionId") ?: return null,
                    generation = fields.long("generation") ?: return null,
                    sequence = fields.long("sequence") ?: return null,
                    hintId = fields.text("hintId") ?: return null,
                    revision = fields.long("revision") ?: return null,
                    status = status)
            }
            "SAID" -> {
                val text = fields.text("text") ?: return null
                if (text.codePointCount() > MAX_TEXT_CODEPOINTS) return null
                HintCommand.Said(
                    sessionId = fields.text("sessionId") ?: return null,
                    generation = fields.long("generation") ?: return null,
                    sequence = fields.long("sequence") ?: return null,
                    route = fields.text("route")?.let { AssistantRoute.fromWire(it) } ?: return null,
                    entryId = fields.text("entryId") ?: return null,
                    text = text)
            }
            "BUSY" -> HintCommand.Busy(
                sessionId = fields.text("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.text("route")?.let { AssistantRoute.fromWire(it) } ?: return null,
                busy = when (fields.rawNumber("busy")) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                })
            "STOP" -> HintCommand.Stop(
                sessionId = fields.text("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null)
            "STATE" -> {
                val status = fields.int("status") ?: return null
                if (status !in 0..7) return null
                HintCommand.State(
                    sessionId = fields.id("sessionId") ?: return null,
                    generation = fields.long("generation") ?: return null,
                    route = fields.requiredRoute() ?: return null,
                    turnId = fields.id("turnId") ?: return null,
                    status = status,
                    sequence = fields.nonNegativeLong("sequence") ?: return null)
            }
            "IDLE" -> HintCommand.Idle(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null,
                sequence = fields.nonNegativeLong("sequence") ?: return null)
            "ASR" -> HintCommand.Asr(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null,
                turnId = fields.id("turnId") ?: return null,
                revision = fields.nonNegativeLong("revision") ?: return null,
                text = fields.text("text") ?: return null)
            "ANSWER" -> HintCommand.Answer(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null,
                turnId = fields.id("turnId") ?: return null,
                hintId = fields.id("hintId") ?: return null,
                revision = fields.nonNegativeLong("revision") ?: return null,
                text = fields.text("text") ?: return null)
            "TOOL" -> {
                val phase = when (fields.text("phase")) {
                    "start" -> ToolPhase.START
                    "end" -> ToolPhase.END
                    else -> return null
                }
                HintCommand.Tool(
                    sessionId = fields.id("sessionId") ?: return null,
                    generation = fields.long("generation") ?: return null,
                    route = fields.requiredRoute() ?: return null,
                    turnId = fields.id("turnId") ?: return null,
                    phase = phase,
                    label = fields.text("label") ?: return null)
            }
            "CLOSE" -> {
                val namedRoute = fields.text("route") ?: return null
                val route = if (namedRoute == "ALL") null
                    else (AssistantRoute.fromWire(namedRoute) ?: return null)
                HintCommand.Close(
                    sessionId = fields.id("sessionId") ?: return null,
                    generation = fields.long("generation") ?: return null,
                    route = route)
            }
            "PIN" -> HintCommand.Pin(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null)
            "PANEL_OPEN" -> HintCommand.PanelOpen(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null)
            "PANEL_SHOWN" -> HintCommand.PanelShown(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null)
            "UI_READY" -> HintCommand.UiReady(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null)
            "UI_CLOSED" -> HintCommand.UiClosed(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                route = fields.requiredRoute() ?: return null)
            "CONFIRM_STATE" -> HintCommand.ConfirmState(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                requestId = fields.id("requestId") ?: return null,
                active = when (fields.rawNumber("active")) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                })
            "CONFIRM_DECISION" -> HintCommand.ConfirmDecision(
                sessionId = fields.id("sessionId") ?: return null,
                generation = fields.long("generation") ?: return null,
                requestId = fields.id("requestId") ?: return null,
                approved = when (fields.rawNumber("approved")) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                })
            else -> null
        }
    }

    private fun validate(command: HintCommand) {
        when (command) {
            is HintCommand.Hello -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.Ping -> require(command.syncId.isNotBlank()) { "SYNC_ID_REQUIRED" }
            is HintCommand.Pong -> require(command.syncId.isNotBlank()) { "SYNC_ID_REQUIRED" }
            is HintCommand.Sync -> {
                require(command.syncId.isNotBlank()) { "SYNC_ID_REQUIRED" }
                require(command.offsetUpperNanos >= command.offsetLowerNanos) { "INCONSISTENT_OFFSET_INTERVAL" }
            }
            is HintCommand.Show -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.hintId.isNotBlank()) { "HINT_ID_REQUIRED" }
                require(command.syncId.isNotBlank()) { "SYNC_ID_REQUIRED" }
                require(command.remainingTtlMillis > 0) { "REMAINING_TTL_MUST_BE_POSITIVE" }
                require(command.text.isNotBlank()) { "TEXT_REQUIRED" }
                require(command.text.codePointCount() <= MAX_TEXT_CODEPOINTS) { "TEXT_TOO_LONG" }
            }
            is HintCommand.Clear -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.Ack -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.hintId.isNotBlank()) { "HINT_ID_REQUIRED" }
            }
            is HintCommand.Said -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.entryId.isNotBlank()) { "ENTRY_ID_REQUIRED" }
                require(command.text.isNotBlank()) { "TEXT_REQUIRED" }
                require(command.text.codePointCount() <= MAX_TEXT_CODEPOINTS) { "TEXT_TOO_LONG" }
            }
            is HintCommand.Busy -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.Stop -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.State -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.turnId.isNotBlank()) { "TURN_ID_REQUIRED" }
                require(command.status in 0..7) { "STATUS_OUT_OF_RANGE" }
                require(command.sequence >= 0) { "SEQUENCE_MUST_BE_NON_NEGATIVE" }
            }
            is HintCommand.Idle -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.sequence >= 0) { "SEQUENCE_MUST_BE_NON_NEGATIVE" }
            }
            is HintCommand.Asr -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.turnId.isNotBlank()) { "TURN_ID_REQUIRED" }
                require(command.revision >= 0) { "REVISION_MUST_BE_NON_NEGATIVE" }
            }
            is HintCommand.Answer -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.turnId.isNotBlank()) { "TURN_ID_REQUIRED" }
                require(command.hintId.isNotBlank()) { "HINT_ID_REQUIRED" }
                require(command.revision >= 0) { "REVISION_MUST_BE_NON_NEGATIVE" }
            }
            is HintCommand.Tool -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.turnId.isNotBlank()) { "TURN_ID_REQUIRED" }
            }
            is HintCommand.Close -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.Pin -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.PanelOpen -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.PanelShown -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.UiReady -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.UiClosed -> require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
            is HintCommand.ConfirmState -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.requestId.isNotBlank()) { "REQUEST_ID_REQUIRED" }
            }
            is HintCommand.ConfirmDecision -> {
                require(command.sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
                require(command.requestId.isNotBlank()) { "REQUEST_ID_REQUIRED" }
            }
        }
    }

    /**
     * The route a message names, with the pre-Phase-C shape still readable.
     *
     * Two different absences must not be confused: **no `route` field at all** is a peer built before the field
     * existed, and every such message was a wake hint, so it reads as [AssistantRoute.WAKE]; a `route` field whose
     * value this build does not know is a message we cannot file correctly, and the only honest answer is to refuse
     * it. Callers spell that as `fields.route() ?: return null`.
     *
     * The 0.24 commands never predate the field, so they use [requiredRoute] instead: a missing `route` there is a
     * malformed message, not a wake hint.
     */
    private fun Map<String, WireField>.route(): AssistantRoute? {
        val field = get("route") ?: return AssistantRoute.WAKE
        if (!field.quoted) return null
        return AssistantRoute.fromWire(field.value)
    }

    /** A route that must be present and understood; `null` for a missing or unknown one. */
    private fun Map<String, WireField>.requiredRoute(): AssistantRoute? =
        text("route")?.let { AssistantRoute.fromWire(it) }

    /** A text identifier that must not be blank. */
    private fun Map<String, WireField>.id(key: String): String? =
        text(key)?.takeIf { it.isNotBlank() }

    private fun Map<String, WireField>.nonNegativeLong(key: String): Long? =
        long(key)?.takeIf { it >= 0 }

    /** A JSON number's literal text; a quoted number is a mistyped field, not an integer. */
    private fun Map<String, WireField>.rawNumber(key: String): String? =
        get(key)?.takeIf { !it.quoted }?.value

    private fun Map<String, WireField>.long(key: String): Long? = rawNumber(key)?.toLongOrNull()
    private fun Map<String, WireField>.int(key: String): Int? = rawNumber(key)?.toIntOrNull()
    private fun Map<String, WireField>.text(key: String): String? =
        get(key)?.takeIf { it.quoted }?.value

    private fun String.codePointCount(): Int = codePointCount(0, length)

    /**
     * The last [max] code points, never splitting a surrogate pair.
     *
     * 0.24 spec §5.4: `ASR`/`ANSWER` are display increments, so the phone sends their tail instead of refusing a long
     * partial; `SHOW` remains the authoritative full-text path with its own bound.
     */
    private fun String.takeLastCodePoints(max: Int): String {
        val count = codePointCount()
        if (count <= max) return this
        return substring(offsetByCodePoints(0, count - max))
    }

    /** A parsed flat field: [value] is decoded text for a string, or the literal text of an integer. */
    private data class WireField(val value: String, val quoted: Boolean)

    /**
     * Minimal flat-object reader. The wire carries only a flat object of strings and integers, so a full JSON
     * parser is unnecessary surface; anything nested, duplicated or escaped beyond the supported set fails
     * the parse and the caller refuses the message.
     */
    private fun parseFlatObject(json: String): Map<String, WireField>? {
        val text = json.trim()
        if (text.length < 2 || text.first() != '{' || text.last() != '}') return null
        val body = text.substring(1, text.length - 1)
        if (body.isEmpty()) return emptyMap()
        val fields = LinkedHashMap<String, WireField>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && body[index] == ',') index++
            if (index >= body.length) break
            if (body[index] != '"') return null
            val key = StringBuilder()
            index++
            while (index < body.length && body[index] != '"') {
                val character = body[index]
                if (character == '\\') {
                    val escaped = body.getOrNull(index + 1) ?: return null
                    key.append(
                        when (escaped) {
                            '"' -> '"'; '\\' -> '\\'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'b' -> '\b'; '/' -> '/'
                            'u' -> {
                                val hex = body.substring(index + 2, minOf(index + 6, body.length))
                                if (hex.length < 4) return null
                                val code = hex.toIntOrNull(16) ?: return null
                                index += 4
                                code.toChar()
                            }
                            else -> return null
                        }
                    )
                    index += 2
                } else {
                    key.append(character)
                    index++
                }
            }
            if (index >= body.length) return null
            index++ // closing quote
            if (body.getOrNull(index) != ':') return null
            index++
            if (fields.containsKey(key.toString())) return null // duplicates are ambiguous
            when (body.getOrNull(index)) {
                '"' -> {
                    val value = StringBuilder()
                    index++
                    while (index < body.length && body[index] != '"') {
                        val character = body[index]
                        if (character == '\\') {
                            val escaped = body.getOrNull(index + 1) ?: return null
                            value.append(
                                when (escaped) {
                                    '"' -> '"'; '\\' -> '\\'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                                    'b' -> '\b'; '/' -> '/'
                                    'u' -> {
                                        val hex = body.substring(index + 2, minOf(index + 6, body.length))
                                        if (hex.length < 4) return null
                                        val code = hex.toIntOrNull(16) ?: return null
                                        index += 4
                                        code.toChar()
                                    }
                                    else -> return null
                                }
                            )
                            index += 2
                        } else {
                            value.append(character)
                            index++
                        }
                    }
                    if (index >= body.length) return null
                    index++ // closing quote
                    fields[key.toString()] = WireField(value.toString(), quoted = true)
                }
                else -> {
                    val start = index
                    if (body.getOrNull(index) == '-') index++
                    while (index < body.length && body[index] in '0'..'9') index++
                    if (index == start) return null // only integers are carried
                    fields[key.toString()] = WireField(body.substring(start, index), quoted = false)
                }
            }
        }
        return fields
    }

    private fun escape(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }
}
