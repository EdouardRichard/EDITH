package edith.phone.a11yprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 13a: the SET_TEXT / READ_TEXT arm's decisions, pinned before the probe touches the K40.
 *
 * The device can only be asked for a verdict line, so every judgement that line depends on is decided here first:
 * which node counts as the target, whether the run failed because there was no field or because the field refused the
 * action, and how a text value is fingerprinted without ever writing the text itself to logcat.
 *
 * Privacy is part of the contract, not a review note: [textVerdictLine] carries length, code-point count and SHA-256
 * only, and a test asserts that the text it was handed does not occur anywhere in the line.
 */
class A11yTextVerdictTest {

    // ------------------------------------------------------------------ fingerprint: length, chars, hash

    /**
     * `len` is the UTF-16 length (what `String.length` reports), `chars` is the Unicode code-point count. They differ
     * for anything outside the BMP (emoji, rare CJK): the device's own read-back uses one or the other depending on the
     * API, so the verdict has to print both or a reader cannot compare fingerprints from two paths.
     */
    @Test
    fun fingerprintCountsUtf16LengthAndCodePoints() {
        val ascii = TextFingerprint.of("abc")
        assertEquals(3, ascii.length)
        assertEquals(3, ascii.chars)

        val mixed = TextFingerprint.of("A🙂好")
        assertEquals("A(1) + surrogate pair(2) + 好(1)", 4, mixed.length)
        assertEquals("A + 🙂 + 好", 3, mixed.chars)

        val chinese = TextFingerprint.of("你好")
        assertEquals(2, chinese.length)
        assertEquals(2, chinese.chars)
    }

    /**
     * The hash is over the **UTF-8 bytes**, not the JVM's UTF-16 code units: that is the encoding a caller sees and the
     * only one that can be reproduced from a log line on any host. Known vectors pin the implementation to SHA-256.
     */
    @Test
    fun fingerprintHashesUtf8Sha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TextFingerprint.of("").sha256,
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TextFingerprint.of("abc").sha256,
        )
        assertEquals(
            "670d9743542cae3ea7ebe36af56bd53648b0a1126162e78d81a32934a711302e",
            TextFingerprint.of("你好").sha256,
        )
    }

    // ------------------------------------------------------------------ text transport

    /**
     * `--es text` is the documented path. The base64 arm exists for the caller whose shell mangles UTF-8 and for
     * multiline content; when both are supplied the plain extra wins, because it is the one the caller named first.
     */
    @Test
    fun plainTextWinsAndBase64IsDecodedAsUtf8() {
        assertEquals("你好", decodeText("你好", "5Lit5paH6L6T5YWl"))
        assertEquals("中文输入", decodeText(null, "5Lit5paH6L6T5YWl"))
        assertEquals("", decodeText("", null))
        assertNull(decodeText(null, null))
    }

    /** A malformed base64 extra is an absent value, never an empty string that would silently clear a field. */
    @Test
    fun malformedBase64IsAbsent() {
        assertNull(decodeText(null, "not-base64!!"))
    }

    // ------------------------------------------------------------------ target selection

    /** A fully qualified view id and the bare name the operator is more likely to type both address the same node. */
    @Test
    fun idSelectorMatchesFullAndBareViewIds() {
        val full = viewId("com.sheen.rokid.phone:id/message_input")
        assertEquals(1, targetKey(id = "com.sheen.rokid.phone:id/message_input"))
        assertEquals(1, targetKey(id = "message_input"))
        assertEquals(1, targetKey(id = "id/message_input"))
        assertEquals(1, targetKey(id = full.substringAfter(':')))
        assertNull(chooseTextTarget(listOf(facts(1, viewId = full)), request(id = "other"), TextMode.SET))
    }

    /**
     * `id` outranks `label`/`hint`/focus when several are supplied: the id is the only selector that names one node
     * across screens, so it is the caller's most specific instruction.
     */
    @Test
    fun idOutranksTheOtherSelectors() {
        val candidates = listOf(
            facts(0, viewId = "pkg:id/other", label = "发送"),
            facts(1, viewId = "pkg:id/message_input", label = "输入消息"),
        )
        assertEquals(1, targetKey(candidates = candidates, id = "message_input", label = "发送"))
    }

    /**
     * Labels are matched **exactly** against text, contentDescription and hint. Substring matching would let a label
     * hit the wrong field ("输入" inside "输入消息") and is not what the caller asked for; the candidate order is the
     * caller's priority, not the tree's.
     */
    @Test
    fun labelMatchesTextDescriptionAndHintExactly() {
        val candidates = listOf(
            facts(0, text = "输入消息（草稿）"),
            facts(1, label = "输入消息"),
            facts(2, description = "输入框"),
        )
        assertEquals(1, targetKey(candidates, label = "输入消息"))
        assertEquals(2, targetKey(candidates, label = "输入框"))
        assertNull(chooseTextTarget(candidates, request(label = "输入"), TextMode.SET))
    }

    /** The explicit hint selector reads the platform's `hintText`, the only field that distinguishes an empty input. */
    @Test
    fun hintSelectorMatchesOnlyTheHint() {
        val candidates = listOf(facts(0, text = "已有内容"), facts(1, hint = "输入消息"))
        assertEquals(1, targetKey(candidates, hint = "输入消息"))
        assertNull(chooseTextTarget(candidates, request(hint = "已有内容"), TextMode.SET))
    }

    /**
     * Focus-first is the production-friendly selector for an app whose input row has no stable resource id (the WAKE
     * row is Compose). A focused editable target wins; otherwise the walk falls back to the first editable node so the
     * probe can focus it itself.
     */
    @Test
    fun focusFirstPrefersTheFocusedEditableThenTheFirstEditable() {
        val candidates = listOf(
            facts(0, text = "message input", focused = false),
            facts(1, text = "focused input", focused = true),
        )
        assertEquals(1, targetKey(candidates, focusFirst = true))

        val unfocused = listOf(facts(0, text = "first input"), facts(1, text = "second input"))
        assertEquals(0, targetKey(unfocused, focusFirst = true))
    }

    /** SET_TEXT never aims at a non-editable node; that is S1 A07's `ret=false` failure mode, refused before dispatch. */
    @Test
    fun setModeChoosesOnlyEditableNodes() {
        val candidates = listOf(
            facts(0, text = "static", className = "android.widget.TextView", editable = false),
            facts(1, text = "editable"),
        )
        assertEquals(1, targetKey(candidates = candidates, mode = TextMode.SET))
        assertNull(chooseTextTarget(candidates.take(1), request(), TextMode.SET))
    }

    /** An explicitly named non-editable node is still returned, so the caller can refuse with its own reason. */
    @Test
    fun explicitIdStillSelectsANonEditableNode() {
        val candidates = listOf(
            facts(0, viewId = "pkg:id/title", className = "android.widget.TextView", editable = false),
            facts(1, viewId = "pkg:id/input"),
        )
        assertEquals(0, targetKey(candidates, id = "title", mode = TextMode.SET))
    }

    /**
     * READ_TEXT may read a TextView as well as an EditText: Task 13's second arm is "read the input back", but reading a
     * visible text node is exactly the same platform capability. Editables win by default so an ordinary read targets
     * the input rather than the first heading.
     */
    @Test
    fun readModeFallsBackToTextViewOnlyWhenNoEditableExists() {
        val withEdit = listOf(
            facts(0, text = "heading", className = "android.widget.TextView", editable = false),
            facts(1, text = "input"),
        )
        assertEquals(1, targetKey(withEdit, mode = TextMode.READ))

        val withoutEdit = listOf(
            facts(0, text = "heading", className = "android.widget.TextView", editable = false),
            facts(1, text = "footer", className = "android.widget.TextView", editable = false),
        )
        assertEquals(0, targetKey(withoutEdit, mode = TextMode.READ))
        assertNull(chooseTextTarget(emptyList(), request(), TextMode.READ))
    }

    /** Invisible nodes are never targets, even when an id or label matches: the platform would act on something unshown. */
    @Test
    fun invisibleCandidatesAreNeverTargets() {
        val candidates = listOf(facts(0, viewId = "pkg:id/input", visible = false))
        assertNull(chooseTextTarget(candidates, request(id = "input"), TextMode.SET))
        assertNull(chooseTextTarget(candidates, request(), TextMode.READ))
    }

    // ------------------------------------------------------------------ outcomes

    /**
     * SET_TEXT's reason classes stay distinct because each implies a different next action: enable the service, fix the
     * command line, wait longer, choose an editable node, or investigate a refused platform action.
     */
    @Test
    fun setDecisionNamesEachDistinctFailure() {
        assertEquals(TextDecision("refused", "service_not_enabled"), setTextDecision(false, true, true, true, true))
        assertEquals(TextDecision("refused", "missing_text"), setTextDecision(true, false, true, true, true))
        assertEquals(TextDecision("no_field", "wait_timeout"), setTextDecision(true, true, false, false, false))
        assertEquals(TextDecision("refused", "target_not_editable"), setTextDecision(true, true, true, false, false))
        assertEquals(TextDecision("refused", "set_action_false"), setTextDecision(true, true, true, true, false))
        assertEquals(TextDecision("set", "none"), setTextDecision(true, true, true, true, true))
    }

    @Test
    fun readDecisionNamesEachDistinctFailure() {
        assertEquals(TextDecision("refused", "service_not_enabled"), readTextDecision(false, true))
        assertEquals(TextDecision("no_field", "wait_timeout"), readTextDecision(true, false))
        assertEquals(TextDecision("read", "none"), readTextDecision(true, true))
    }

    /**
     * Exact match is the only comparison the read-back arm can honestly make without logging content. An absent
     * `expected` is `none` (a measurement was made but nothing was compared); a missing actual text is `false`, never
     * `none`, because the caller asked and the probe did not get a value.
     */
    @Test
    fun exactMatchHasThreeStates() {
        assertNull(exactMatch(actual = "你好", expected = null))
        assertEquals(false, exactMatch(actual = null, expected = "你好"))
        assertEquals(true, exactMatch(actual = "你好", expected = "你好"))
        assertEquals(false, exactMatch(actual = "你好啊", expected = "你好"))
        assertEquals(true, exactMatch(actual = "", expected = ""))
        assertEquals(false, exactMatch(actual = "", expected = "你好"))
    }

    /**
     * An empty EditText on this device reads its **hint** back as `.text` (S1 A06). The flag lets a reader tell "the
     * field is empty and the platform echoed its hint" from "the field really holds this text" without logging either.
     */
    @Test
    fun hintEchoIsFlaggedWithoutLoggingTheHint() {
        assertEquals(true, isHintEcho(actual = "输入消息", hint = "输入消息"))
        assertEquals(false, isHintEcho(actual = "你好", hint = "输入消息"))
        assertNull(isHintEcho(actual = null, hint = "输入消息"))
        assertNull(isHintEcho(actual = "你好", hint = null))
    }

    // ------------------------------------------------------------------ the verdict line

    /**
     * The line is the deliverable, and privacy is in its contract: every field is present, the text's length/chars/hash
     * are present, and the text itself occurs nowhere. A caller that finds the content in this line has found a bug.
     */
    @Test
    fun setVerdictCarriesFingerprintsAndNeverTheText() {
        val text = "绝密中文内容ABC"
        val fingerprint = TextFingerprint.of(text)
        val line = textVerdictLine(
            TextVerdict(
                op = "set",
                outcome = "set",
                reason = "none",
                pkg = "com.sheen.rokid.phone",
                target = "id",
                targetClass = "EditText",
                focusRet = true,
                setRet = true,
                polls = 2,
                waitMillis = 5000,
                text = fingerprint,
                read = fingerprint,
                match = true,
                isHint = false,
            ),
        )
        assertFalse("the text must never be logged", line.contains(text))
        assertFalse("no substring of the text either", line.contains("绝密"))
        assertTrue(line.startsWith("P3_A1TEXT_VERDICT op=set outcome=set reason=none"))
        assertTrue(line.contains("pkg=com.sheen.rokid.phone"))
        assertTrue(line.contains("target=id"))
        assertTrue(line.contains("targetClass=EditText"))
        assertTrue(line.contains("focus=true set=true"))
        assertTrue(line.contains("polls=2 waitMillis=5000"))
        assertTrue(line.contains("len=${text.length} chars=${fingerprint.chars} sha=${fingerprint.sha256}"))
        assertTrue(line.contains("match=true"))
        assertTrue(line.contains("isHint=false"))
    }

    /**
     * The read arm prints the actual fingerprint and the expected fingerprint, so a `match=false` can be reviewed
     * without either string: a wrong field, a stale read and a rejected write produce different pairs.
     */
    @Test
    fun readVerdictCarriesBothFingerprintsAndTheMatch() {
        val expected = "你好，EDITH 中文输入"
        val line = textVerdictLine(
            TextVerdict(
                op = "read",
                outcome = "read",
                reason = "none",
                pkg = "edith.phone.a11yprobe",
                target = "first_editable",
                targetClass = "EditText",
                polls = 1,
                waitMillis = 5000,
                text = TextFingerprint.of(expected),
                expected = TextFingerprint.of(expected),
                match = true,
                isHint = false,
            ),
        )
        assertFalse(line.contains(expected))
        assertTrue(line.contains("op=read outcome=read"))
        assertTrue(line.contains("len=${expected.length}"))
        assertTrue(line.contains("expectedLen=${expected.length}"))
        assertTrue(line.contains("expectedSha=${TextFingerprint.of(expected).sha256}"))
        assertTrue(line.contains("match=true"))
    }

    /**
     * A degenerate run must still print every field as `none`/empty, exactly like the earlier probes' verdict lines:
     * a field that disappears when there is nothing to report makes a failed run and a truncated log indistinguishable.
     */
    @Test
    fun noFieldVerdictKeepsEveryField() {
        val line = textVerdictLine(
            TextVerdict(op = "set", outcome = "no_field", reason = "wait_timeout", pkg = "com.miui.home", polls = 40, waitMillis = 6000),
        )
        assertTrue(line.startsWith("P3_A1TEXT_VERDICT op=set outcome=no_field reason=wait_timeout"))
        for (field in listOf("pkg=com.miui.home", "target=none", "targetClass=none", "focus=none", "set=none")) {
            assertTrue("missing $field in $line", line.contains(field))
        }
        for (field in listOf("len=none", "chars=none", "sha=none", "readLen=none", "readChars=none", "readSha=none")) {
            assertTrue("missing $field in $line", line.contains(field))
        }
        for (field in listOf("expectedLen=none", "expectedChars=none", "expectedSha=none", "match=none", "isHint=none")) {
            assertTrue("missing $field in $line", line.contains(field))
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun facts(
        key: Int,
        viewId: String? = null,
        text: String? = null,
        hint: String? = null,
        label: String? = null,
        description: String? = null,
        className: String = "android.widget.EditText",
        editable: Boolean = true,
        focused: Boolean = false,
        visible: Boolean = true,
    ): TextCandidateFacts = TextCandidateFacts(
        key = key,
        viewId = viewId,
        text = text ?: label,
        hint = hint,
        contentDescription = description,
        className = className,
        editable = editable,
        focused = focused,
        visible = visible,
    )

    private fun request(
        id: String? = null,
        label: String? = null,
        hint: String? = null,
        focusFirst: Boolean = false,
    ): TextTargetRequest = TextTargetRequest(id = id, label = label, hint = hint, focusFirst = focusFirst)

    private fun targetKey(
        candidates: List<TextCandidateFacts> = listOf(facts(1, viewId = "com.sheen.rokid.phone:id/message_input")),
        id: String? = null,
        label: String? = null,
        hint: String? = null,
        focusFirst: Boolean = false,
        mode: TextMode = TextMode.SET,
    ): Int? = chooseTextTarget(candidates, request(id, label, hint, focusFirst), mode)?.key

    private fun viewId(value: String): String = value
}
