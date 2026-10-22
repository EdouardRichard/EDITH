package com.sheen.rokid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * The four loop patterns of Task 12 (R55, spec §4.8, D3 §1.3).
 *
 * [warningCode] is the token in a synthetic warning result (`LOOP_WARNING:<warningCode>`); [safeCode] is the stable
 * code a critical decision returns as the run's `safeCode` (D3 §1.10). Both are wire values, not display strings.
 */
enum class LoopPattern(val warningCode: String, val safeCode: String) {
    GENERIC_REPEAT("GENERIC_REPEAT", "LOOP_REPEAT"),
    POLL_NO_PROGRESS("POLL_NO_PROGRESS", "LOOP_POLL"),
    PING_PONG("PING_PONG", "LOOP_PINGPONG"),
    GLOBAL_CIRCUIT_BREAKER("GLOBAL_CIRCUIT_BREAKER", "LOOP_GLOBAL"),
}

/** What the loop must do with **one** call, decided before it executes (R55: detectors decide before execution). */
sealed interface LoopDecision {
    /** Execute the call. */
    data object Allow : LoopDecision

    /**
     * Do **not** execute this call; feed the model a synthetic tool result carrying
     * [loopWarningPayload] instead. The attempt still counts, so the same signature can reach the critical threshold.
     */
    data class Warn(val pattern: LoopPattern) : LoopDecision

    /**
     * End the run as `NO_PROGRESS` with [LoopPattern.safeCode]. The triggering call is never executed and the model's
     * promise text is never treated as the answer.
     */
    data class Critical(val pattern: LoopPattern) : LoopDecision
}

/**
 * What the loop learns **after** one executed call.
 *
 * [notice] is the one-time "you have stopped making progress" sentence appended to that call's tool result (the call
 * itself still ran, R55). [critical] ends the run with `LOOP_GLOBAL`.
 */
data class LoopAfterCall(val notice: Boolean = false, val critical: Boolean = false)

/**
 * The message a warning-skipped call feeds back to the model instead of executing (R55).
 *
 * It is a tool result, not a chat turn: the model sees it in the same tool round, so a warning costs **no** extra model
 * round trip, and a skipped call never produces an observation fingerprint or a global no-progress count.
 */
internal fun loopWarningPayload(pattern: LoopPattern): String =
    "LOOP_WARNING:${pattern.warningCode}: the same step has repeated. " +
        "Observe again before acting, or change the action."

/** The one-time global circuit-breaker notice appended to the eighth no-progress result (R55). */
internal const val GLOBAL_NO_PROGRESS_NOTICE =
    "LOOP_NOTICE:GLOBAL_CIRCUIT_BREAKER: several steps made no progress. " +
        "Re-observe, change the approach, or stop and report what you know."

private const val MAX_ANALYZED_ATTEMPTS = 64

private val canonicalJson = Json { isLenient = true }

/** A stable, comparable identity for one call: the tool plus the SHA-256 of its canonical arguments (never the body). */
internal fun callIdentity(tool: String, argsJson: String): String =
    "$tool#${sha256Hex(canonicalArguments(argsJson)).take(16)}"

/**
 * Canonical JSON: object members sorted by name, arrays kept in order, scalar spelling kept.
 *
 * The model may order two otherwise identical argument objects differently; without this, `{"a":1,"b":2}` and
 * `{"b":2,"a":1}` would be different call identities and `generic_repeat` would never see the repeat. An unparsable
 * string (the registry would have refused it later anyway) falls back to its trimmed self, so this function never throws.
 */
internal fun canonicalArguments(argsJson: String): String =
    runCatching { canonicalize(canonicalJson.parseToJsonElement(argsJson)).toString() }.getOrDefault(argsJson.trim())

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}

/**
 * The per-run four-detector state machine (R55; D3 §1.1–§1.3).
 *
 * The detectors are deliberately **pure state**: they execute nothing, see no payload bodies and have no clock. The loop
 * calls [beforeCall] before every tool call and [afterCall] only for a call that really executed, which is what makes
 * "a warning-skipped call produces no fingerprint and no global count" structural rather than a comment.
 *
 * ### Precedence
 *
 * A call can match several patterns at once (an observation call repeated with the same args matches both
 * `generic_repeat` and `poll_no_progress`). The more specific pattern wins: poll over generic for an observation call,
 * then generic, then ping-pong. Criticals are checked before warnings, and the first matching critical decides the
 * run's safe code. `global_circuit_breaker` is decided in [afterCall] (its threshold is only reachable once a result
 * exists) and is therefore checked by the loop after the result is recorded.
 *
 * ### Thresholds (authoritative: R55 / D3 §1.2)
 *
 * | pattern | identity | warning | critical |
 * |---|---|---|---|
 * | generic_repeat | consecutive `(tool, canonical args)` attempts | 3rd attempt | 5th attempt |
 * | poll_no_progress | same identity + unchanged observation fingerprint | 3rd attempt | 5th attempt |
 * | ping_pong | trailing `A,B,A,B…` complete cycles | 2 cycles | 3 cycles **and** no observation change in the segment |
 * | global_circuit_breaker | whole-run executed no-progress results | 8 (notice only) | 12 |
 *
 * The generic streak is reset by a **write** whose post-action fingerprint really changed (progress is not a repeat).
 * The poll streak is reset by a changed observation fingerprint. The global counter is never reset.
 */
class LoopDetectors(
    private val genericWarn: Int = 3,
    private val genericStop: Int = 5,
    private val pollWarn: Int = 3,
    private val pollStop: Int = 5,
    private val pingPongWarnCycles: Int = 2,
    private val pingPongStopCycles: Int = 3,
    private val globalNoticeAt: Int = 8,
    private val globalStopAt: Int = 12,
) {
    init {
        require(genericWarn >= 1 && genericStop > genericWarn) { "generic thresholds must ascend" }
        require(pollWarn >= 1 && pollStop > pollWarn) { "poll thresholds must ascend" }
        require(pingPongWarnCycles >= 1 && pingPongStopCycles > pingPongWarnCycles) {
            "ping-pong thresholds must ascend"
        }
        require(globalNoticeAt >= 1 && globalStopAt > globalNoticeAt) { "global thresholds must ascend" }
    }

    private class Attempt(val identity: String, val observation: Boolean) {
        /**
         * `true` — this executed call changed the observed screen; `false` — it ran but changed nothing; `null` — it
         * did not run (warning-skipped/refused) or returned no fingerprint. Ping-pong criticality requires that no true
         * exists in the alternating segment (R55: "任一带来真实观察变化则不 critical").
         */
        var changed: Boolean? = null
    }

    private val attempts = ArrayDeque<Attempt>()

    private var genericStreak = 0
    private var genericIdentity: String? = null

    private var pollStreak = 0
    private var pollIdentity: String? = null
    private var pollHadFingerprint = false
    private var pollFingerprint: String? = null

    /** The last post-result observation fingerprint of any kind — the "before" of the next result. */
    private var lastFingerprint: String? = null
    private var noProgress = 0
    private var noticeSent = false

    /** Test-observable: how many executed results made no progress (the global breaker's own count). */
    internal val noProgressResults: Int get() = noProgress

    /** The canonical identity of one call, exposed so a test can pin the key-order rule directly. */
    fun identity(tool: String, argsJson: String): String = callIdentity(tool, argsJson)

    /**
     * Records one call **attempt** and decides whether it may execute.
     *
     * A warning/critical attempt stays in the record even though it never runs (R55: the count includes skipped
     * attempts), otherwise a model could dodge the thresholds by being warned forever.
     */
    fun beforeCall(tool: String, argsJson: String, observation: Boolean): LoopDecision {
        val attempt = Attempt(identity(tool, argsJson), observation)
        attempts += attempt
        while (attempts.size > MAX_ANALYZED_ATTEMPTS) attempts.removeFirst()

        genericStreak = if (attempt.identity == genericIdentity) genericStreak + 1 else 1
        genericIdentity = attempt.identity

        if (observation) {
            if (attempt.identity == pollIdentity && pollHadFingerprint) {
                pollStreak++
            } else {
                pollIdentity = attempt.identity
                pollStreak = 1
                pollHadFingerprint = false
            }
        } else {
            // A write is not part of a poll chain: the model is not merely re-reading the same screen.
            pollIdentity = null
            pollStreak = 0
            pollHadFingerprint = false
            pollFingerprint = null
        }

        if (observation && pollStreak >= pollStop) return LoopDecision.Critical(LoopPattern.POLL_NO_PROGRESS)
        if (genericStreak >= genericStop) return LoopDecision.Critical(LoopPattern.GENERIC_REPEAT)
        val cycles = alternatingCycles()
        if (cycles >= pingPongStopCycles && !alternatingSegmentProgressed()) {
            return LoopDecision.Critical(LoopPattern.PING_PONG)
        }

        if (observation && pollStreak >= pollWarn) return LoopDecision.Warn(LoopPattern.POLL_NO_PROGRESS)
        if (genericStreak >= genericWarn) return LoopDecision.Warn(LoopPattern.GENERIC_REPEAT)
        if (cycles >= pingPongWarnCycles) return LoopDecision.Warn(LoopPattern.PING_PONG)
        return LoopDecision.Allow
    }

    /**
     * Records the result of the call just attempted.
     *
     * The loop calls this for **every** attempt, with `executed = false` for a refusal/denial; only an executed call
     * contributes a fingerprint, a global no-progress count or ping-pong progress evidence. [write] is the executor's
     * per-call "this call mutates state" declaration (`ToolResult.stateChanging`, R98): only a state-changing call with
     * a changed fingerprint resets the generic streak. A successful read (`stateChanging = false`) is never progress by
     * itself; `device` declares `observation = true` for every call, so the descriptor alone cannot answer this.
     */
    fun afterCall(executed: Boolean, write: Boolean, fingerprint: String?): LoopAfterCall {
        val current = attempts.lastOrNull() ?: return LoopAfterCall()
        if (!executed) return LoopAfterCall()

        val changed = fingerprint != null && lastFingerprint != null && fingerprint != lastFingerprint
        current.changed = if (fingerprint == null) null else changed
        if (write && changed) {
            // Real progress after a write is not a repeat: the next identical signature starts a fresh run.
            genericStreak = 0
        }

        if (current.observation) {
            if (fingerprint == null) {
                pollStreak = 0
                pollHadFingerprint = false
                pollFingerprint = null
            } else {
                if (pollFingerprint != null && pollFingerprint != fingerprint) pollStreak = 1
                pollHadFingerprint = true
                pollFingerprint = fingerprint
            }
        }

        var notice = false
        var critical = false
        if (fingerprint != null) {
            if (lastFingerprint != null && fingerprint == lastFingerprint) {
                noProgress++
                if (noProgress == globalNoticeAt && !noticeSent) {
                    noticeSent = true
                    notice = true
                }
                if (noProgress >= globalStopAt) critical = true
            }
            lastFingerprint = fingerprint
        }
        return LoopAfterCall(notice = notice, critical = critical)
    }

    /**
     * The number of complete `A,B` cycles in the trailing alternating run, or `0` when the run does not alternate
     * between exactly two signatures. A three-signature `A,B,C,B,A` walk is not a ping-pong.
     */
    private fun alternatingCycles(): Int {
        val length = trailingAlternatingLength()
        if (length < 2) return 0
        val segment = attempts.toList().subList(attempts.size - length, attempts.size)
        if (segment.map { it.identity }.distinct().size != 2) return 0
        return length / 2
    }

    /** Whether any executed call in the trailing alternating segment really changed the observed screen. */
    private fun alternatingSegmentProgressed(): Boolean {
        val length = trailingAlternatingLength()
        if (length < 2) return false
        return attempts.toList().subList(attempts.size - length, attempts.size).any { it.changed == true }
    }

    private fun trailingAlternatingLength(): Int {
        if (attempts.isEmpty()) return 0
        var length = 1
        var index = attempts.size - 2
        while (index >= 0 && attempts[index].identity != attempts[index + 1].identity) {
            length++
            index--
        }
        return length
    }
}

// ------------------------------------------------------------------------------------------------ error tracking (R56)

private const val CONFIRMATION_DENIED = "CONFIRMATION_DENIED"
private const val CONFIRMATION_TIMEOUT = "CONFIRMATION_TIMEOUT"

/** The stable-code vocabulary this run may recognise. Keys are the code; values are the one-time hint sentences. */
private val ERROR_HINTS = mapOf(
    "NODE_STALE" to "the node reference is stale; observe again before acting.",
    "NODE_NOT_FOUND" to "the node is no longer on screen; observe again.",
    "CHANNEL_UNAVAILABLE" to "that channel is unavailable; try another channel or re-establish it.",
    "ACTION_TIMEOUT" to "the action timed out; observe first to see whether it took effect.",
    "DEVICE_SCREEN_OFF" to "the screen is off; ask the user to turn it on.",
    "DEVICE_LOCKED" to "the device is locked; ask the user to unlock it.",
    "VERIFY_TIMEOUT" to "the expected state never appeared; observe the current screen before deciding.",
    "ACCEPTED_NO_EFFECT" to "the platform accepted the action but the screen did not change; observe again.",
)

private val STABLE_TOKEN = Regex("[A-Z][A-Z0-9_]{1,}")
private val PREFIXED_CODE = Regex("^([A-Z][A-Z0-9_]{1,})\\s*:")
private val REFUSED_PREFIXES = listOf("Refused:", "Failed:")

/**
 * Extracts the **identity** of a failed result without keeping its body (R56: D117 allows a hash, never the text).
 *
 * This is the legacy-detail fallback used when a result carries no `ToolExecution.code`. In order:
 * 1. `Refused: CODE` / `Failed: CODE` — the pre-0.3 registry prefix, accepted here only for legacy details (a
 *    persisted transcript or an unmigrated executor's old string); the current registry never adds it;
 * 2. a bare all-caps token (`UNKNOWN_TOOL`, `REFUSED_BY_POLICY`);
 * 3. a leading `CODE:` (`ACTION_TIMEOUT: may or may not have taken effect`);
 * 4. an `"error":{"code":"CODE"}` member of the payload's JSON (the device/shell tools render their stable code there);
 * 5. otherwise SHA-256 truncated to 16 hex characters. A JSON body is canonicalised and its `timing` member removed
 *    first, so the same failure with different timing values (the device envelope embeds per-call timings) hashes
 *    identically instead of looking like a new error on every attempt.
 *
 * A `Refused: the channel …` detail whose first token is prose falls through to the hash: the first token of a sentence
 * is not a stable code, and treating every "the" as the same identity would end runs on unrelated failures.
 *
 * [legacyCodeOf] is the code-only half of this extraction, for the loop's content-free step/audit label.
 */
internal fun stableCodeOf(payload: String): String {
    val identity = legacyIdentity(payload)
    identity.code?.let { return it }
    if (identity.body.startsWith("{")) return sha256Hex(canonicalJsonWithoutTiming(identity.body)).take(16)
    // Prose behind a prefix (or a bare sentence) keeps the old whole-payload identity; there is nothing to strip.
    return sha256Hex(payload.trim()).take(16)
}

/**
 * The stable code an unmigrated **legacy detail** names, or `null` when it names none.
 *
 * This is the code-only extraction the loop's `AgentStep`/audit label uses **after** the explicit execution code: a
 * detail such as `APP_NOT_VISIBLE` keeps its identity, and a prose detail yields `null` so the label falls back to the
 * outcome token. Unlike [stableCodeOf], prose is never turned into a hash here: a hash is a fine streak identity but
 * not a meaningful count-only log label.
 */
internal fun legacyCodeOf(payload: String): String? = legacyIdentity(payload).code

/** A legacy detail split into its prefix-free body and the code-shaped identity it names, if any. */
private class LegacyIdentity(val body: String, val code: String?)

private fun legacyIdentity(payload: String): LegacyIdentity {
    val text = payload.trim()
    val prefix = REFUSED_PREFIXES.firstOrNull { text.startsWith(it) }
    val body = if (prefix == null) text else text.substring(prefix.length).trim()
    val code = if (prefix != null) {
        val token = body.substringBefore(' ').substringBefore('\n')
        token.takeIf { STABLE_TOKEN.matches(it) }
    } else {
        when {
            STABLE_TOKEN.matches(body) -> body
            else -> PREFIXED_CODE.find(body)?.groupValues?.get(1)
        }
    }
    return LegacyIdentity(body, code ?: jsonErrorCode(body))
}

/** The `error.code` member of a JSON payload, when it is present and shaped like a stable code. */
private fun jsonErrorCode(body: String): String? {
    val root = runCatching { canonicalJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val error = root["error"] as? JsonObject ?: return null
    val code = (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return code.takeIf { STABLE_TOKEN.matches(it) }
}

/**
 * The payload with its `timing` member removed, canonicalised (sorted keys, normalised whitespace).
 *
 * Only reached when a JSON payload carries no stable code. Timing is the one part of an otherwise identical failure
 * that legitimately changes on every call, so it must not leak into the identity; dropping it keeps the error streak
 * and the per-code hints reachable. Out-of-memory/parse failures fall back to the raw body, which is still hashed.
 */
private fun canonicalJsonWithoutTiming(body: String): String {
    val parsed = runCatching { canonicalJson.parseToJsonElement(body) }.getOrNull() ?: return body.trim()
    val stable = if (parsed is JsonObject) JsonObject(parsed.filterKeys { it != "timing" }) else parsed
    return canonicalize(stable).toString()
}

/** What one failed/successful result means for the run's error streak. */
internal data class ErrorAssessment(
    /** The stable code this result was classified as, or `null` for a success. */
    val stableCode: String? = null,
    /** Whether this result counts toward the consecutive same-error streak. */
    val counted: Boolean = false,
    /** The consecutive same-error streak reached 3: the run must fail with `REPEATED_ERROR`. */
    val repeatTerminated: Boolean = false,
    /** A one-line hint the loop appends to the model-facing tool output, or `null`. */
    val hint: String? = null,
)

/**
 * Consecutive-identical-error tracking (R56, D3 §1.4).
 *
 * The identity is `tool + ":" + stableCode`. Three in a row terminate the run as `FAILED/REPEATED_ERROR`; a **successful
 * write** resets the streak while a successful observation does not (otherwise "observe ok → tap fails" could alternate
 * forever). `CONFIRMATION_DENIED` / `CONFIRMATION_TIMEOUT` are the user's decision, not an execution error: they are
 * never counted and carry a "do not retry automatically" hint. `REFUSED_BY_POLICY` does count — it is deterministic and
 * the model must change course.
 *
 * Hints for known stable codes are injected once per code per run; the body of the payload is never retained. The
 * identity is the execution's own `code` when the tool named one; only an unmigrated result falls back to parsing its
 * legacy detail ([stableCodeOf]).
 */
internal class ErrorTracker(private val limit: Int = 3) {
    init {
        require(limit >= 1) { "ErrorTracker limit must be at least 1, was $limit" }
    }

    private var lastIdentity: String? = null
    private var streak = 0
    private val hinted = mutableSetOf<String>()

    fun assess(tool: String, result: ToolResult, writeSucceeded: Boolean): ErrorAssessment {
        if (result.ok) {
            if (writeSucceeded) {
                lastIdentity = null
                streak = 0
            }
            return ErrorAssessment()
        }
        // edith-0.3 Phase 2.2: a migrated result states its stable code in the execution facts, so the body is never
        // parsed. An unmigrated tool's legacy `data` is the fallback identity (the same parser the old payload used).
        val code = result.execution.code ?: stableCodeOf(result.data)
        if (code == CONFIRMATION_DENIED || code == CONFIRMATION_TIMEOUT) {
            // The wearer answered (or the question expired): retrying is exactly the wrong response, and the result is
            // not evidence the tool is broken.
            return ErrorAssessment(
                stableCode = code,
                counted = false,
                repeatTerminated = false,
                hint = "LOOP_HINT:$code do not retry automatically; wait for the user's next step.",
            )
        }
        val identity = "$tool:$code"
        streak = if (identity == lastIdentity) streak + 1 else 1
        lastIdentity = identity
        val hint = ERROR_HINTS[code]?.takeIf { hinted.add(code) }?.let { "LOOP_HINT:$code $it" }
        return ErrorAssessment(
            stableCode = code,
            counted = true,
            repeatTerminated = streak >= limit,
            hint = hint,
        )
    }
}

/** SHA-256 as lowercase hex; the only form in which text ever reaches an identity, a fingerprint or a log line. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
