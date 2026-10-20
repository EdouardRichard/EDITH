package edith.phone.a11yprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Click-now's decisions, pinned before the probe runs on a dialog that cannot be read any other way.
 *
 * These are the questions an unattended run cannot answer after the fact: which of the listed labels counted as a match,
 * what the command line actually asked for, and whether "nothing was found" means a missing dialog or a missing wording.
 * On the MIUI install confirmation the failure evidence is a log line, so the line's shape and the decision behind it have
 * to be fixed here — a run that prints something ambiguous costs the whole session it was meant to save.
 */
class A11yClickVerdictTest {

    // ------------------------------------------------------------------ the override: what the command line asked for

    /**
     * The absent extra and the empty extra are different answers, and the difference is a safety property rather than a
     * style choice: null means "no override, keep A1's default affirmative list", while an empty list means the operator
     * named no candidates. Collapsing the second into the first would click 「允许」/「OK」 on a dialog nobody named —
     * exactly the mis-click this mode was added to avoid.
     */
    @Test
    fun parseLabelListSeparatesAnAbsentOverrideFromAnEmptyOne() {
        assertNull("no extra at all: the caller keeps its default", parseLabelList(null))
        assertEquals("an empty extra means no candidates, not the default", emptyList<String>(), parseLabelList(""))
        assertEquals(emptyList<String>(), parseLabelList("   "))
        assertEquals("separators alone yield nothing usable", emptyList<String>(), parseLabelList(",，;;"))
    }

    /**
     * The MIUI installer wording, split the way the operator will type it: trimmed, and split on the full-width comma as
     * well. A Chinese IME produces `，` without the operator noticing, and splitting only on the ASCII comma would turn
     * `继续安装，安装` into one label that matches nothing — a `no_match` run with a command line that looks right.
     */
    @Test
    fun parseLabelListSplitsTrimsAndAcceptsTheFullWidthComma() {
        assertEquals(
            listOf("继续安装", "安装", "确定"),
            parseLabelList(" 继续安装 , 安装 ,确定 "),
        )
        assertEquals(listOf("继续安装", "安装"), parseLabelList("继续安装，安装"))
        assertEquals(listOf("Allow", "OK"), parseLabelList("Allow\nOK"))
    }

    /**
     * Duplicates are removed and order is preserved. Order *is* the priority decision (see [firstCandidateIn]), so a
     * de-duplication that reordered the list would silently change which button gets clicked first.
     */
    @Test
    fun parseLabelListDeduplicatesWithoutReordering() {
        assertEquals(listOf("继续安装", "安装"), parseLabelList("继续安装,安装,继续安装"))
        assertEquals(listOf("安装", "继续安装"), parseLabelList("安装,继续安装,安装"))
    }

    /**
     * `am` delivers a different type per spelling — `--esal` an `ArrayList`, `--esa` a `String[]`, `--es` one `String` —
     * and an operator uses whichever they remember. All three have to mean the same list, and an unexpected type must
     * read as "absent" rather than crash a run nobody is watching.
     */
    @Test
    fun normalizeLabelsAcceptsEveryAmSpellingAndIgnoresAnythingElse() {
        assertNull(normalizeLabels(null))
        assertEquals(listOf("继续安装", "安装"), normalizeLabels(arrayListOf("继续安装", "安装")))
        assertEquals(listOf("继续安装", "安装"), normalizeLabels(arrayOf("继续安装", "安装")))
        assertEquals(listOf("继续安装", "安装"), normalizeLabels("继续安装,安装"))
        assertNull("an unusable value must not become an empty candidate list by accident", normalizeLabels(42))
    }

    // ------------------------------------------------------------------ which candidate matched

    /**
     * The installer's own wording, and the exact-match trap that comes with it: 「安装」 is a **substring** of
     * 「继续安装」, so a substring rule here would click the installer's continue button when the operator listed a label
     * that is on no button at all. The probe must match exactly, and candidate order decides when several are present.
     */
    @Test
    fun firstCandidateMatchesTheInstallerWordingExactlyAndInCandidateOrder() {
        val dialog = listOf("取消", "继续安装", "安装")
        assertEquals("继续安装", firstCandidateIn(listOf("继续安装", "安装", "确定"), dialog))
        assertEquals("安装", firstCandidateIn(listOf("安装", "继续安装"), dialog))
        assertNull("「安装」 is a substring of 「继续安装」, not a match for it", firstCandidateIn(listOf("安装"), listOf("继续安装")))
        assertNull("a dialog that never appeared matches nothing", firstCandidateIn(listOf("继续安装"), emptyList()))
    }

    @Test
    fun aCountdownSuffixMatchesItsExactLabelWithoutBecomingASubstringRule() {
        assertEquals("允许", firstCandidateIn(listOf("允许"), listOf("拒绝", "急停", "允许 (91)")))
        assertEquals("允许", firstCandidateIn(listOf("允许"), listOf("允许（9）")))
        assertNull(
            "the countdown exception must not turn 安装 into a match for 继续安装",
            firstCandidateIn(listOf("安装"), listOf("继续安装")),
        )
        assertNull(firstCandidateIn(listOf("允许"), listOf("允许其他")))
    }

    // ------------------------------------------------------------------ the poll evidence

    /**
     * The evidence line must name the window it read as well as the labels, because "the dialog never came up" and "we
     * read some other window" are the same `no_match` verdict and need different fixes — the second is what starting the
     * clicker too late looks like, and the package name is the only thing that tells them apart.
     */
    @Test
    fun clickPollLineNamesTheWindowAndTheLabelsInOneLine() {
        assertEquals(
            "A1CLICK_POLL tick=3 package=com.miui.packageinstaller visible=2 labels=取消|继续安装",
            clickPollLine(tick = 3, window = "com.miui.packageinstaller", labels = boundedLabels(listOf("取消", "继续安装"))),
        )
    }

    /**
     * De-duplication happens **before** the bound: the MIUI dialog exposes the same word on several nodes, and a bound
     * applied first would let those copies spend the whole budget while the one unknown label — the reason for looking —
     * fell off the end. The fixtures are the shape that defect would hide.
     */
    @Test
    fun boundedLabelsDeduplicatesBeforeApplyingTheBoundAndDropsBlanks() {
        val repeated = List(50) { "取消" } + listOf("继续安装")
        val bounded = boundedLabels(repeated, max = 3)
        assertEquals(listOf("取消", "继续安装"), bounded)
        assertEquals(40, boundedLabels(List(100) { "标签$it" }).size)
        assertEquals(listOf("a"), boundedLabels(listOf("", "  ", "a")))
    }

    // ------------------------------------------------------------------ the verdict

    /**
     * The four outcomes are kept apart because each implies a different next move: an empty label list is a command-line
     * fix, a full budget with no match is either a missing dialog or unknown wording, a refused click is a capability
     * question, and a dispatched click is the only success this run can claim — it cannot see whether the install
     * proceeded, and saying it did would be the T3b "the screen changed, therefore success" mistake in a new place.
     */
    @Test
    fun clickOutcomeSeparatesEveryWayTheRunCanEnd() {
        assertEquals("clicked", clickOutcome(listOf("继续安装"), matched = "继续安装", clicked = true))
        assertEquals("click_refused", clickOutcome(listOf("继续安装"), matched = "继续安装", clicked = false))
        assertEquals("no_match", clickOutcome(listOf("继续安装"), matched = null, clicked = false))
        assertEquals("no_candidates", clickOutcome(emptyList(), matched = null, clicked = false))
        assertEquals(
            "an empty list stays an input problem even if a match was somehow reported",
            "no_candidates",
            clickOutcome(emptyList(), matched = "继续安装", clicked = true),
        )
    }

    /**
     * The line has to be computable by eye and greppable by prefix: every field is present even when empty or zero, and
     * the prefix is `P3_A1CLICK_VERDICT` rather than `P3_A1_VERDICT` so the existing A1 evidence and its greps keep
     * meaning exactly what they meant.
     */
    @Test
    fun clickVerdictLineCarriesEveryFieldOnASuccessfulClick() {
        val candidates = listOf("继续安装", "安装")
        val line = clickVerdictLine(
            candidates = candidates,
            outcome = clickOutcome(candidates, matched = "继续安装", clicked = true),
            matched = "继续安装",
            clicked = true,
            polls = 7,
            waitMillis = 15_000L,
            window = "com.miui.packageinstaller",
            visibleCount = 4,
        )
        assertEquals(
            "P3_A1CLICK_VERDICT outcome=clicked labels=继续安装|安装 matched=继续安装 clicked=true polls=7 " +
                "waitMillis=15000 window=com.miui.packageinstaller visible=4",
            line,
        )
        assertTrue("the line must not be mistakable for the existing A1 verdict", line.startsWith("P3_A1CLICK_VERDICT "))
    }

    /**
     * The run that found nothing — the case this mode exists for — still prints a full line: `matched=none` and the
     * window it was reading, so the operator can tell a dialog that never appeared from one whose wording is unknown.
     * A field that vanished here would make the failure and a truncated log look identical.
     */
    @Test
    fun clickVerdictLineKeepsItsFieldsWhenNothingEverMatchedWithinTheBudget() {
        val line = clickVerdictLine(
            candidates = listOf("继续安装"),
            outcome = clickOutcome(listOf("继续安装"), matched = null, clicked = false),
            matched = null,
            clicked = false,
            polls = 100,
            waitMillis = 15_000L,
            window = "edith.phone.a11yprobe",
            visibleCount = 0,
        )
        assertEquals(
            "P3_A1CLICK_VERDICT outcome=no_match labels=继续安装 matched=none clicked=false polls=100 " +
                "waitMillis=15000 window=edith.phone.a11yprobe visible=0",
            line,
        )
    }

    /** A run given no labels polls nothing and says so: `polls=0` with an empty `labels=` field is the honest record. */
    @Test
    fun clickVerdictLineRecordsAnEmptyCandidateListAsNoCandidates() {
        val line = clickVerdictLine(
            candidates = emptyList(),
            outcome = clickOutcome(emptyList(), matched = null, clicked = false),
            matched = null,
            clicked = false,
            polls = 0,
            waitMillis = 15_000L,
            window = "unknown",
            visibleCount = 0,
        )
        assertEquals(
            "P3_A1CLICK_VERDICT outcome=no_candidates labels= matched=none clicked=false polls=0 " +
                "waitMillis=15000 window=unknown visible=0",
            line,
        )
    }
}
