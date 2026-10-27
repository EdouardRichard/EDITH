package edith.phone.a11yprobe

import java.security.MessageDigest
import java.util.Base64

/**
 * A text value's **fingerprint**, for every line this arm writes: length in UTF-16 units, Unicode code-point count and
 * SHA-256 over the UTF-8 bytes. The text itself never leaves the process.
 *
 * Two lengths rather than one because they answer different questions. `length` is what `String.length` (and therefore
 * most Java-side read-backs) report; `chars` is the number of Unicode code points, which is what a human means by
 * "characters" and what an emoji or a rare CJK character changes. Printing both lets a reader compare a fingerprint
 * taken here with one taken by a caller in Python or by the app, without either side having to log the value.
 *
 * SHA-256 is over UTF-8, not UTF-16: UTF-8 is the encoding the `am` command line and the probe's JSON/log pipeline use,
 * so the hash is reproducible from any host. This is a **privacy fingerprint**, not a security primitive: it lets the
 * acceptance compare "the field holds the text I sent" without recording that text (D53 / spec §17.5).
 */
data class TextFingerprint(val length: Int, val chars: Int, val sha256: String) {
    companion object {
        fun of(text: String): TextFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val hex = CharArray(digest.size * 2)
            val digits = "0123456789abcdef"
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF
                hex[index * 2] = digits[value ushr 4]
                hex[index * 2 + 1] = digits[value and 0x0F]
            }
            return TextFingerprint(
                length = text.length,
                chars = text.codePointCount(0, text.length),
                sha256 = String(hex),
            )
        }
    }
}

/**
 * The text a caller asked to write or compare against, from either transport.
 *
 * `--es text` is the documented extra and always wins. `textB64` exists for the cases the plain spelling cannot cover
 * safely: a Windows console or a PowerShell layer that re-encodes the command line (Chinese arrives as replacement
 * characters), a value containing shell metacharacters, or a multi-line string. Both are decoded to the same Kotlin
 * `String`, so nothing downstream knows which arm was used.
 *
 * Strict base64, deliberately: the MIME decoder silently drops characters outside the alphabet, so a corrupt value
 * would decode to *something* and the run would write a different text than the caller sent. `null` means "no usable
 * text", never "empty text" - clearing a field is requested with an explicit empty `--es text ""`.
 */
fun decodeText(rawText: Any?, rawBase64: Any?): String? = when {
    rawText is String -> rawText
    rawText is CharSequence -> rawText.toString()
    rawBase64 is String -> runCatching { String(Base64.getDecoder().decode(rawBase64), Charsets.UTF_8) }.getOrNull()
    else -> null
}

/** What a run is allowed to aim at. The two modes share the selector rules; they differ in what counts as eligible. */
enum class TextMode { SET, READ }

/** How the target was chosen, recorded in the verdict so a wrong-field read is reviewable without the field's text. */
enum class TextTargetKind { ID, LABEL, HINT, FOCUS, FIRST_EDITABLE, FIRST_TEXT_VIEW }

/** The verdict's spelling of a kind: `first_editable`, `first_text_view`, ... (stable, greppable). */
fun TextTargetKind.token(): String = name.lowercase()

/**
 * One visible node's facts, as plain values.
 *
 * The node itself stays in the service layer; this is the shape the selection decision is allowed to see. Keeping the
 * decision on strings and booleans is what makes "which node did the probe aim at" a JVM test instead of a fact only
 * observable on a phone with an app in front.
 */
data class TextCandidateFacts(
    val key: Int,
    val viewId: String?,
    val text: String?,
    val hint: String?,
    val contentDescription: String?,
    val className: String?,
    val editable: Boolean,
    val focused: Boolean,
    val visible: Boolean,
)

/**
 * The caller's selector, exactly as the `am` extras describe it.
 *
 * At most one of [id]/[label]/[hint]/[focusFirst] is expected to be used; when several are supplied the order in
 * [chooseTextTarget] is the priority (id first), because only the id names one node independently of what is on screen.
 */
data class TextTargetRequest(
    val id: String? = null,
    val label: String? = null,
    val hint: String? = null,
    val focusFirst: Boolean = false,
)

/** The selected candidate's key plus how it was selected. */
data class TextTargetSelection(val key: Int, val kind: TextTargetKind)

/**
 * Which visible node a SET_TEXT/READ_TEXT run should act on, or null when nothing eligible is on screen.
 *
 * The rules, in priority order:
 *
 *  1. **id** - full `package:id/name`, bare `name`, or `id/name`; the match is a resource-id match, never a text match.
 *  2. **label** - exact equality with the node's text, contentDescription or hint. Exact, because a substring match
 *     ("输入" inside "输入消息") would let a later selector hit the wrong field while looking correct.
 *  3. **hint** - exact equality with the platform's separate `hintText`; this is the only selector that can distinguish
 *     an empty input from a field that has a placeholder.
 *  4. **focusFirst** - the currently focused node, editable preferred; if none is focused, the ordinary default applies,
 *     because the probe can perform ACTION_FOCUS itself.
 *  5. **default** - first visible editable node. In [TextMode.READ] only, a visible `TextView`/`EditText` with non-blank
 *     text is the last resort, so the format can read a displayed value when it is not an input.
 *
 * An explicit selector that matches only a **non-editable** node is still returned: the caller then refuses with
 * `target_not_editable` instead of silently setting text in some other field. SET's default never picks a non-editable
 * node ([TextMode.SET]); that is S1 A07's `ret=false` case, refused before an action is dispatched.
 *
 * Invisible candidates are never targets, whatever the selector says.
 */
fun chooseTextTarget(
    candidates: List<TextCandidateFacts>,
    request: TextTargetRequest,
    mode: TextMode,
): TextTargetSelection? {
    val visible = candidates.filter { it.visible }
    if (visible.isEmpty()) return null

    request.id?.let { id ->
        return selection(visible.filter { viewIdMatches(it.viewId, id) }, TextTargetKind.ID)
    }
    request.label?.let { label ->
        return selection(
            visible.filter { candidate ->
                candidate.text == label || candidate.contentDescription == label || candidate.hint == label
            },
            TextTargetKind.LABEL,
        )
    }
    request.hint?.let { hint ->
        return selection(visible.filter { it.hint == hint }, TextTargetKind.HINT)
    }
    if (request.focusFirst) {
        val focused = visible.filter { it.focused }
        val preferred = when (mode) {
            TextMode.SET -> focused.firstOrNull { it.editable }
            TextMode.READ -> focused.firstOrNull { it.editable } ?: focused.firstOrNull()
        }
        if (preferred != null) return TextTargetSelection(preferred.key, TextTargetKind.FOCUS)
    }

    visible.firstOrNull { it.editable }?.let {
        return TextTargetSelection(it.key, TextTargetKind.FIRST_EDITABLE)
    }
    if (mode == TextMode.READ) {
        visible.firstOrNull { isReadableTextClass(it.className) && !it.text.isNullOrBlank() }?.let {
            return TextTargetSelection(it.key, TextTargetKind.FIRST_TEXT_VIEW)
        }
    }
    return null
}

/** Prefer an editable node when the selector matches several; otherwise the first match in tree order. */
private fun selection(matches: List<TextCandidateFacts>, kind: TextTargetKind): TextTargetSelection? {
    val chosen = matches.firstOrNull { it.editable } ?: matches.firstOrNull() ?: return null
    return TextTargetSelection(chosen.key, kind)
}

/**
 * Whether a node's reported resource id answers [requested].
 *
 * `am` callers type all of these spellings and the platform reports only the fully qualified form:
 * `pkg:id/name`, `id/name`, and `name`. The suffix tests are anchored on the `:`/`/` before the name so a bare
 * `input` cannot match a different id that merely ends with the same letters.
 */
private fun viewIdMatches(raw: String?, requested: String): Boolean {
    if (raw == null) return false
    if (raw == requested) return true
    if (raw.endsWith(":id/$requested")) return true
    if (raw.endsWith("/$requested")) return true
    return raw.endsWith(":$requested")
}

/** The one class family READ may fall back to when no editable node exists. */
private fun isReadableTextClass(className: String?): Boolean {
    val simple = className?.substringAfterLast('.') ?: return false
    return simple == "TextView" || simple == "EditText"
}

/** The outcome token plus the reason token, kept apart so `no_field` and `refused` never need guessing. */
data class TextDecision(val outcome: String, val reason: String)

/**
 * SET_TEXT's verdict token, decided in the order the run can fail.
 *
 * `service_not_enabled` and `missing_text` are **refusals** (nothing was attempted, and the caller can fix both from
 * the command line/settings); `no_field` means the wait expired with no eligible target; `target_not_editable` and
 * `set_action_false` mean a target was found but the platform would not take the write. `outcome=set` means
 * `performAction(ACTION_SET_TEXT)` returned true - it is **not** proof the app kept the text; the read-back fingerprint
 * and the separate READ_TEXT arm are what establish efficacy, exactly as S1 A09's "return true but did not apply" case
 * requires.
 */
fun setTextDecision(
    serviceEnabled: Boolean,
    textProvided: Boolean,
    targetFound: Boolean,
    targetEditable: Boolean,
    setRet: Boolean,
): TextDecision = when {
    !serviceEnabled -> TextDecision("refused", "service_not_enabled")
    !textProvided -> TextDecision("refused", "missing_text")
    !targetFound -> TextDecision("no_field", "wait_timeout")
    !targetEditable -> TextDecision("refused", "target_not_editable")
    !setRet -> TextDecision("refused", "set_action_false")
    else -> TextDecision("set", "none")
}

/** READ_TEXT's verdict token. A read of any eligible node is `read`; only "no service" and "no node" are failures. */
fun readTextDecision(serviceEnabled: Boolean, targetFound: Boolean): TextDecision = when {
    !serviceEnabled -> TextDecision("refused", "service_not_enabled")
    !targetFound -> TextDecision("no_field", "wait_timeout")
    else -> TextDecision("read", "none")
}

/**
 * Exact-match verdict, with the three states the acceptance needs: `null` = no expected value was supplied and nothing
 * was compared; `false` = a comparison was asked for and the field's value is absent or different. The caller may log
 * only the boolean and the two hashes, never either string.
 */
fun exactMatch(actual: String?, expected: String?): Boolean? = when {
    expected == null -> null
    actual == null -> false
    else -> actual == expected
}

/**
 * Whether the value read back is the platform's **hint echo** rather than real content.
 *
 * S1 A06 measured that an empty EditText on the K40 reports its hint through `.text`; without this flag a reader sees
 * only `len=9` and cannot tell "the field is empty" from "the field holds the hint string". `null` means no hint or no
 * value, i.e. the question cannot be answered for this read.
 */
fun isHintEcho(actual: String?, hint: String?): Boolean? = when {
    actual == null -> null
    hint == null -> null
    else -> actual == hint
}

/**
 * The one greppable line for both arms.
 *
 * Every field is printed even when unknown (`none`), for the same reason as the earlier probes' verdicts: a field that
 * vanishes on a degenerate run makes that run and a truncated log indistinguishable. [text] is the intended text for
 * SET and the actual value for READ; [read] is SET's read-back; [expected] is READ's caller-supplied value. Only
 * [TextFingerprint]s are ever accepted here, so the line cannot carry content by construction.
 */
data class TextVerdict(
    val op: String,
    val outcome: String,
    val reason: String,
    val pkg: String = "none",
    val target: String = "none",
    val targetClass: String = "none",
    val focusRet: Boolean? = null,
    val setRet: Boolean? = null,
    val polls: Int = 0,
    val waitMillis: Long = 0,
    val text: TextFingerprint? = null,
    val read: TextFingerprint? = null,
    val expected: TextFingerprint? = null,
    val match: Boolean? = null,
    val isHint: Boolean? = null,
)

fun textVerdictLine(verdict: TextVerdict): String {
    fun bool(value: Boolean?): String = when (value) {
        null -> "none"
        true -> "true"
        false -> "false"
    }
    return "P3_A1TEXT_VERDICT op=${verdict.op} outcome=${verdict.outcome} reason=${verdict.reason} " +
        "pkg=${verdict.pkg} target=${verdict.target} targetClass=${verdict.targetClass} " +
        "focus=${bool(verdict.focusRet)} set=${bool(verdict.setRet)} polls=${verdict.polls} " +
        "waitMillis=${verdict.waitMillis} len=${verdict.text?.length ?: "none"} " +
        "chars=${verdict.text?.chars ?: "none"} sha=${verdict.text?.sha256 ?: "none"} " +
        "readLen=${verdict.read?.length ?: "none"} readChars=${verdict.read?.chars ?: "none"} " +
        "readSha=${verdict.read?.sha256 ?: "none"} " +
        "expectedLen=${verdict.expected?.length ?: "none"} " +
        "expectedChars=${verdict.expected?.chars ?: "none"} " +
        "expectedSha=${verdict.expected?.sha256 ?: "none"} " +
        "match=${bool(verdict.match)} isHint=${bool(verdict.isHint)}"
}
