package edith.plugins.smart.context

import edith.agent.ModelClientGateway
import edith.agent.GatewayTurn
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.MonotonicClock
import edith.plugins.smart.P3Log
import edith.plugins.smart.safeFailureCode

/**
 * The production [Summarizer] (spec §4.4 step 3): one request to the plugin's own configured profile, one sentence
 * back, or `null`.
 *
 * It is a plain class rather than a lambda because there is something real to test — which profile the request is
 * built from, that it offers **no tools**, and that no failure escapes — while the interesting behaviour (deferring
 * the call, writing the placeholder, ordering it after the memory write) belongs to [ContextCompressor].
 *
 * **It blocks**, which is why the compressor hands it to the plugin's task scope. It is not itself asynchronous: a
 * function that could choose its own thread would put the choice in two places.
 *
 * **The summary body is never logged; the summary's *outcome* is.** [ContextCompressor] logs
 * `P3_CONTEXT summary=ok chars=…` / `summary=none`, and that line is why this class used to log nothing at all. But
 * `summary=none` has four different causes — a profile the host cannot resolve, an answer that arrived empty, a
 * request the model path refuses **by throwing**, and a deadline — and on 2026-10-15 the device produced `none`
 * without saying which, so B3's asynchronous half could not be accepted or refuted at all. [P3Log] therefore carries
 * one line per attempt with the outcome, the elapsed time, the character count and the **sanitized** failure code:
 * counts and codes, never the sentence (the same rule `P3_RUN` follows).
 *
 * **Assembly is the gateway's, not a second one.** `ModelClientGateway` already turns a transcript into a stateless
 * `ModelRequest`, owns the deadline and accumulates the stream; re-implementing that here would be a second answer
 * to "what does one request look like" and the first thing to drift. This class therefore supplies exactly the two
 * things only the plugin knows: the **profile** (its configured id) and the **transcript** to condense.
 */
class ModelSummarizer(
    private val client: ModelClient,
    private val profileLookup: (String) -> ModelProfile?,
    private val profileId: String,
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val envelopeFor: (requestId: String, startNanos: Long, endNanos: Long) -> EventEnvelope,
    /**
     * The request's deadline, resolved at call time. A lambda because the wall clock belongs to the caller: the
     * compressor decides how long a summary may take, and a value captured at construction would be stale.
     */
    private val deadlineNanos: () -> Long,
) : Summarizer {

    override fun summarize(transcript: String): String? {
        val text = transcript.trim()
        if (text.isEmpty()) return null
        val startedNanos = clock.nowNanos()
        return runCatching {
            // The gateway's own `PROFILE_MISSING` refusal lands here as a non-`COMPLETED` terminal, so an
            // unconfigured profile degrades to truncation instead of throwing into the turn.
            val reply = ModelClientGateway(
                client = client,
                profileLookup = profileLookup,
                clock = clock,
                scheduler = scheduler,
                envelopeFor = envelopeFor,
            ).turn(
                GatewayTurn(
                    turnId = REQUEST_ID_PREFIX + clock.nowNanos(),
                    profileId = profileId,
                    instructions = INSTRUCTIONS,
                    history = listOf(ModelHistoryItem.Text(MessageRole.USER, text)),
                    // No tools at all: a summary may produce a sentence and nothing else. `ModelClientGateway`'s
                    // `ModelRequest` requires `input` or `history` to be non-empty, and the transcript is the history.
                    tools = emptyList(),
                    deadlineNanos = deadlineNanos(),
                ),
            )
            val summary = reply.text
                .takeIf { reply.terminal == com.sheen.rokid.plugin.ModelEnd.COMPLETED }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            // The outcome, never the answer: `terminal` names which of the four causes this was, and `safeCode` is
            // the provider's own machine-readable reason when the provider sent one.
            if (summary == null) {
                P3Log.line(
                    "P3_SUMMARY ok=false ms=${elapsedMillis(startedNanos)} chars=${reply.text.length} " +
                        "terminal=${reply.terminal ?: "-"} code=${reply.safeCode ?: "-"}",
                )
            } else {
                P3Log.line("P3_SUMMARY ok=true ms=${elapsedMillis(startedNanos)} chars=${summary.length}")
            }
            summary
        }.getOrElse { failure ->
            // A request the model path refused by throwing (an over-budget explicit context, a profile revision
            // mismatch, an invalid endpoint, an unavailable credential) arrives here; the class name alone cannot say
            // which one it was, so the same shape-validated code `P3_RUN` uses is logged instead of the message.
            P3Log.line(
                "P3_SUMMARY ok=false ms=${elapsedMillis(startedNanos)} chars=0 " +
                    "error=${failure.safeFailureCode("UNSPECIFIED")}",
            )
            null
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long = (clock.nowNanos() - startedNanos) / 1_000_000L

    private companion object {
        /** Identifies a summary request in an envelope the host also carries turns on; not a transcript. */
        const val REQUEST_ID_PREFIX = "summary-"

        /**
         * The whole instruction: one sentence, no tools, no invention. The transcript is prepended to it as the
         * request's own history, so the model sees "怎么压缩" before "压缩什么" — the shape a summarisation prompt
         * needs.
         */
        val INSTRUCTIONS = listOf(
            "你是对话压缩器。把下面这段即将被丢弃的对话压缩成一句中文摘要。",
            "只保留对后续对话有用的信息：用户的要求、已经完成的动作、以及结论。",
            "不要编造没有出现过的内容，不要补充建议，不要提问。",
            "只输出这一句摘要本身，不要任何前缀、标题或解释。",
        ).joinToString("")
    }
}
