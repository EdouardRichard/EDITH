package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ModelEnd

/**
 * Test-only [ModelGateway]: hands back the scripted replies in order and records every [GatewayTurn] verbatim, so a
 * test can assert the exact request shape (history, tools, deadline) the loop produced and the number of turns it took.
 */
internal class ScriptedGateway(private val replies: List<GatewayReply>) : ModelGateway {
    val requests = mutableListOf<GatewayTurn>()

    override fun turn(request: GatewayTurn): GatewayReply {
        requests += request
        val index = requests.size - 1
        check(index < replies.size) { "ScriptedGateway exhausted: turn ${index + 1} requested, ${replies.size} scripted" }
        return replies[index]
    }
}

/** A turn reply fixture; the defaults describe the ordinary "the model answered" turn. */
internal fun reply(
    text: String = "",
    terminal: ModelEnd? = ModelEnd.COMPLETED,
    safeCode: String? = null,
    calls: List<ToolCall> = emptyList(),
) = GatewayReply(toolCalls = calls, text = text, terminal = terminal, safeCode = safeCode)

/** A tool call fixture; [argsJson] is the model's own string and is never rewritten. */
internal fun call(id: String, name: String, argsJson: String = "{}", itemId: String = id) =
    ToolCall(id, name, argsJson, itemId)
