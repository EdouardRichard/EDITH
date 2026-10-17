package com.sheen.rokid.output

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.OutputStatus

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
        if (fields["wireVersion"] != WIRE_VERSION.toString()) return null
        return when (val command = fields["command"]) {
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
                busy = when (fields.text("busy")) {
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
        }
    }

    /**
     * The route a message names, with the pre-Phase-C shape still readable.
     *
     * Two different absences must not be confused: **no `route` field at all** is a peer built before the field
     * existed, and every such message was a wake hint, so it reads as [AssistantRoute.WAKE]; a `route` field whose
     * value this build does not know is a message we cannot file correctly, and the only honest answer is to refuse
     * it. Callers spell that as `fields.route() ?: return null`.
     */
    private fun Map<String, String>.route(): AssistantRoute? {
        val named = get("route") ?: return AssistantRoute.WAKE
        return AssistantRoute.fromWire(named)
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    /**
     * Minimal flat-object reader. The wire carries only a flat object of strings and integers, so a full JSON
     * parser is unnecessary surface; anything nested, duplicated or escaped beyond the supported set fails
     * the parse and the caller refuses the message.
     */
    private fun parseFlatObject(json: String): Map<String, String>? {
        val text = json.trim()
        if (text.length < 2 || text.first() != '{' || text.last() != '}') return null
        val body = text.substring(1, text.length - 1)
        if (body.isEmpty()) return emptyMap()
        val fields = LinkedHashMap<String, String>()
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
                    fields[key.toString()] = value.toString()
                }
                else -> {
                    val start = index
                    if (body.getOrNull(index) == '-') index++
                    while (index < body.length && body[index] in '0'..'9') index++
                    if (index == start) return null // only integers are carried
                    fields[key.toString()] = body.substring(start, index)
                }
            }
        }
        return fields
    }

    private fun Map<String, String>.long(key: String): Long? = get(key)?.toLongOrNull()
    private fun Map<String, String>.text(key: String): String? = get(key)

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
