package edith.agent

import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ToolSpec

/**
 * The one model port of the agent loop: a [turn] is one stateless model round trip.
 *
 * A transport failure is the implementation's to translate: return a [GatewayReply] with
 * `terminal = ModelEnd.FAILED` and a stable [GatewayReply.safeCode]. An exception that escapes [turn] escapes
 * `AgentLoop.run` too — the loop deliberately knows nothing about transports, and a silent catch here would turn a
 * dead connection into a finished turn.
 */
interface ModelGateway {
    fun turn(request: GatewayTurn): GatewayReply
}

/**
 * One model turn request.
 *
 * [profileId] is the model profile this run belongs to; the loop only forwards it, so the adapter that owns a profile
 * has the source of truth (P3.1's loop reads no profile itself).
 *
 * [history] is the stateless transcript so far: the loop opens it with the request's utterance and appends one
 * `[assistant text, tool call*, tool output*]` group per tool round plus `[assistant text, user instruction]` per
 * retry. [tools] is empty when the run offers no tool; the loop still sends the turn.
 */
data class GatewayTurn(
    val turnId: String,
    val profileId: String,
    val instructions: String,
    val history: List<ModelHistoryItem>,
    val tools: List<ToolSpec>,
    val deadlineNanos: Long,
)

/**
 * One model turn reply.
 *
 * [terminal] **must be read before the payload** (P3.0 measured this): an `INCOMPLETE` turn can carry text that looks
 * like an answer, and `FAILED`/`DEADLINE`/`CANCELLED` are verdicts, not empty turns. [safeCode] is the provider's
 * machine-readable reason when it sent one (`incomplete_details.reason` on the wire).
 */
data class GatewayReply(
    val toolCalls: List<ToolCall>,
    val text: String,
    val terminal: ModelEnd?,
    val safeCode: String?,
)
