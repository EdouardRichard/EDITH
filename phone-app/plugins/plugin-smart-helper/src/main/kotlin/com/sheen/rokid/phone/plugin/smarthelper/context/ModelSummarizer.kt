package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.ModelClientGateway
import com.sheen.rokid.agent.GatewayTurn
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.audit.safeFailureCode

/**
 * The production [Summarizer] (spec §4.4 step 3): one request to the plugin's own configured profile, one structured
 * summary back (the six-part template of design §6.4), or `null`.
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
 *
 * **Telemetry (0.3 Phase 2 final review I4).** The gateway is built with [P3Log.line], so a summary request reports its
 * own count-only `P3_CACHE` line whenever the provider returns usage. A summary has no assembled `RequestFrame` and
 * does not run through `AgentLoop.onRequestBuilt`, so it emits **no** `P3_PROMPT` line; that is the documented
 * asymmetry, not a missing line.
 */
class ModelSummarizer(
    private val client: ModelClient,
    private val profileLookup: (String) -> ModelProfile?,
    private val profileId: String,
    /**
     * 0.3 design §6.4 / Phase 4A A7: the structured summary instruction. Production passes
     * `PromptCatalog.summarizer()`; the prompt is a required constructor value rather than a compiled-in constant, so a
     * caller cannot silently keep an older instruction and the prose lives in one authored resource.
     */
    private val prompt: String,
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val envelopeFor: (requestId: String, startNanos: Long, endNanos: Long) -> EventEnvelope,
    /**
     * The request's deadline, resolved at call time. A lambda because the wall clock belongs to the caller: the
     * compressor decides how long a summary may take, and a value captured at construction would be stale.
     */
    private val deadlineNanos: () -> Long,
) : Summarizer {

    override fun summarize(transcript: String, maxOutputTokens: Int): String? {
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
                // Final review I4: the summary is a model request too, so its cache accounting is reported. Without this
                // sink the `P3_CACHE` "every request with usage" contract silently skipped the asynchronous summary.
                metricLine = P3Log::line,
            ).turn(
                GatewayTurn(
                    turnId = REQUEST_ID_PREFIX + clock.nowNanos(),
                    profileId = profileId,
                    instructions = prompt,
                    history = listOf(ModelHistoryItem.Text(MessageRole.USER, text)),
                    // No tools at all: a summary may produce a sentence and nothing else. `ModelClientGateway`'s
                    // `ModelRequest` requires `input` or `history` to be non-empty, and the transcript is the history.
                    tools = emptyList(),
                    deadlineNanos = deadlineNanos(),
                    // 0.3 design §5.2/§6.4: the summary declares its own output cap rather than inheriting the
                    // profile's window reservation, so the next turn keeps the room the summary did not spend.
                    maxOutputTokens = maxOutputTokens,
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
    }
}
