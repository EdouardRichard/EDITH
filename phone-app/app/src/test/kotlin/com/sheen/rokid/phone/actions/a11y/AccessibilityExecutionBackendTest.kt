package com.sheen.rokid.phone.actions.a11y
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.withP3Sink

import com.sheen.rokid.device.Foreground
import com.sheen.rokid.phone.actions.ExecutionRouter
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility channel (spec §17.2/§17.3): cross-app semantic UI operations, whose success criterion comes from
 * P0's T3b probe.
 *
 * That probe's two hard-won rules are pinned here: a click must aim at the **nearest clickable ancestor** of the node
 * that carries the text (the label itself returns `false` from `ACTION_CLICK`), and success is **the screen changing**,
 * not the action being accepted. "Mock 或编译通过不得当作实机通过证据" - and the same rule cuts the other way: an action
 * that returns `true` while nothing moved is a failed action.
 *
 * T1.5 updated: replaced tap_text/type_text with index-based tools (tap, input_text, etc.).
 * Behavioral assertions preserved per A1 discipline.
 */
class AccessibilityExecutionBackendTest {

    private class FakeUi(
        private val clickable: Boolean = true,
        private val fingerprintBefore: String = "screen-a",
        private val fingerprintAfter: String = "screen-b",
        private val nodes: List<RawSnapshotNode> = listOf(
            RawSnapshotNode(
                text = "WLAN", contentDescription = null, className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 0, top = 0, right = 100, bottom = 50, hintText = null,
            ),
        ),
        /**
         * What every `snapshot` call after the first one answers. Used to prove that a continuation page does not
         * re-read the screen: if it did, it would splice in a tree the model never saw (0.5.4 T9 fix round).
         */
        private val laterNodes: List<RawSnapshotNode>? = null,
        /**
         * What every `snapshot` call from the third one on answers. Lets a test prove three successive fresh reads are
         * three different trees - e.g. a new turn must re-read even when the filter still matches (T9 fix round 2).
         */
        private val thirdNodes: List<RawSnapshotNode>? = null,
        // 0.5.4 T11 coordinate gating: the existing accessibility port answers these probes in production. The defaults
        // describe a normal unlocked app screen; a test that wants a refusal scripts the fact that causes it.
        private val foregroundPackage: String? = "com.example.app",
        private val keyguardLocked: Boolean = false,
        /**
         * The focused-window-type witness the coordinate gate also reads (0.5.4 T11 fix round). `null` means "cannot
         * tell", which must refuse exactly like `false`; the default is the normal "an app window is focused".
         */
        private val focusedWindow: Boolean? = true,
        private val screen: ScreenBounds? = ScreenBounds(0, 0, 1080, 2400),
        private val gestureAccepted: Boolean = true,
        /**
         * What the fake field reads back after `input_text` (0.5.4 T12). `null` means "echo the text the backend
         * dispatched", i.e. the normal field that accepts the write; a non-null value scripts an unchanged field.
         */
        private val textAfterType: String? = null,
        /**
         * Per-read-back answers, in order (0.5.4 T12). Lets a test script a field that updates late: the first re-read
         * is still empty and a later poll carries the text. Calls beyond the list answer like [textAfterType].
         */
        private val readBackAnswers: List<String?>? = null,
        /** Whether the port still recognises the snapshot id it was handed (0.5.4 T12). */
        private val snapshotValid: Boolean = true,
        /**
         * One structural fingerprint per read, in order (0.5.4 T13). The post-action settle reads this sequence, so a
         * test can script a UI that updates late or keeps moving; calls beyond the list repeat the last answer.
         */
        private val fingerprintAnswers: List<String>? = null,
    ) : AccessibilityUi {
        val clickByIndexCalls = mutableListOf<Pair<String, Int>>()
        val longPressByIndexCalls = mutableListOf<Pair<String, Int>>()
        val setTextByIndexCalls = mutableListOf<Triple<String, Int, String>>()
        val clickAtCalls = mutableListOf<Pair<Int, Int>>()
        val longPressAtCalls = mutableListOf<Pair<Int, Int>>()
        val textAtCalls = mutableListOf<Pair<Int, ScreenFilter>>()
        var fingerprints = 0
        var snapshotCalls = 0
        private var currentSnapshot: ScreenSnapshot? = null

        override fun clickText(text: String): Boolean = clickable
        override fun setText(text: String): Boolean = clickable
        override fun screenFingerprint(): String {
            fingerprints++
            fingerprintAnswers?.let { answers ->
                return answers.getOrElse(fingerprints - 1) { answers.last() }
            }
            return if (fingerprints == 1) fingerprintBefore else fingerprintAfter
        }

        override fun foreground(): Foreground? = foregroundPackage?.let { Foreground(it, null) }

        override fun isKeyguardLocked(): Boolean = keyguardLocked

        override fun focusedWindowIsApplication(): Boolean? = focusedWindow

        override fun screenBounds(): ScreenBounds? = screen

        override fun clickAt(x: Int, y: Int): Boolean {
            clickAtCalls += x to y
            return gestureAccepted
        }

        override fun longPressAt(x: Int, y: Int): Boolean {
            longPressAtCalls += x to y
            return gestureAccepted
        }

        override fun snapshot(filter: ScreenFilter): ScreenSnapshot {
            snapshotCalls++
            val tree = when {
                snapshotCalls == 1 -> nodes
                snapshotCalls == 2 -> laterNodes ?: nodes
                else -> thirdNodes ?: laterNodes ?: nodes
            }
            val source = object : SnapshotNodeSource {
                override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> = tree
                override fun maxNodeLimit(): Int = MAX_NODES
            }
            val snap = SnapshotBuilder.build(source, filter)
            currentSnapshot = snap
            return snap
        }

        override fun clickByIndex(snapshotId: String, index: Int): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            clickByIndexCalls += snapshotId to index
            currentSnapshot = null
            return clickable
        }

        override fun longPressByIndex(snapshotId: String, index: Int): Boolean {
            // Record the attempt before validating, so the list proves "the index path was never reached" even when the
            // coordinate test has no snapshot at all.
            longPressByIndexCalls += snapshotId to index
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            currentSnapshot = null
            return clickable
        }

        override fun scroll(direction: ScrollDirection): Boolean {
            currentSnapshot = null
            return clickable
        }

        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            setTextByIndexCalls += Triple(snapshotId, index, text)
            currentSnapshot = null
            return clickable
        }

        override fun textAt(index: Int, filter: ScreenFilter): String? {
            textAtCalls += index to filter
            readBackAnswers?.let { answers ->
                if (textAtCalls.size <= answers.size) return answers[textAtCalls.size - 1]
            }
            return textAfterType ?: setTextByIndexCalls.lastOrNull()?.third
        }

        override fun isSnapshotValid(snapshotId: String): Boolean = snapshotValid

        override fun structuralFingerprint(): ScreenFingerprint =
            ScreenFingerprint(hash = "", nodeCount = 0, clickableCount = 0, truncated = false)
    }

    private fun backend(
        ui: AccessibilityUi,
        connected: Boolean = true,
        elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    ) = AccessibilityExecutionBackend(
        ui = ui,
        connected = { connected },
        elapsedMillis = elapsedMillis,
        sleepMillis = sleepMillis,
    )

    /** A run of plain text nodes with distinct rectangles, for the pagination tests. */
    private fun textNodes(count: Int, prefix: String = "Item"): List<RawSnapshotNode> = (0 until count).map { i ->
        RawSnapshotNode(
            text = "$prefix $i", contentDescription = null, className = null,
            clickable = true, editable = false, scrollable = false, checked = false,
            left = 0, top = i * 10, right = 100, bottom = i * 10 + 10, hintText = null,
        )
    }

    /** One editable field: the target of every `input_text` test. */
    private fun fieldNode(text: String = ""): RawSnapshotNode = RawSnapshotNode(
        text = text, contentDescription = null, className = null,
        clickable = false, editable = true, scrollable = false, checked = false,
        left = 0, top = 0, right = 100, bottom = 50, hintText = null,
    )

    // ── read_screen fields and pagination (0.5.4 T9) ────────────────────────

    @Test fun `read screen renders content description and bounds`() {
        val nodes = listOf(
            RawSnapshotNode(
                text = null, contentDescription = "搜索设置项", className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 900, top = 120, right = 1056, bottom = 240, hintText = null,
            ),
            RawSnapshotNode(
                text = "WLAN", contentDescription = null, className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 0, top = 0, right = 100, bottom = 50, hintText = null,
            ),
            RawSnapshotNode(
                text = "Wi-Fi", contentDescription = "Wi-Fi 设置", className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 0, top = 50, right = 100, bottom = 100, hintText = null,
            ),
        )
        val result = backend(FakeUi(nodes = nodes)).execute("read_screen", """{"filter":"all"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val detail = result.detail
        // A node without text still reaches the model through its description, with the rectangle and its midpoint.
        assertTrue(
            detail,
            """[0] desc="搜索设置项" bounds=[900,120][1056,240] center=(978,180) clickable""" in detail,
        )
        // Text stays the primary label; a node without a description just carries geometry.
        assertTrue(detail, """[1] "WLAN" bounds=[0,0][100,50] center=(50,25) clickable""" in detail)
        // A description that adds information the text does not carry is kept next to it.
        assertTrue(
            detail,
            """[2] "Wi-Fi" desc="Wi-Fi 设置" bounds=[0,50][100,100] center=(50,75) clickable""" in detail,
        )
    }

    @Test fun `read screen page offset returns the next slice`() {
        val ui = FakeUi(nodes = textNodes(150))
        val b = backend(ui)

        val firstPage = b.execute("read_screen", """{"filter":"all"}""").detail

        assertTrue("the first page starts at index 0: $firstPage", """[0] "Item 0"""" in firstPage)
        assertTrue("the first page ends at index 99: $firstPage", """[99] "Item 99"""" in firstPage)
        assertFalse("the first page must not leak index 100: $firstPage", "[100]" in firstPage)

        val nextPage = b.execute("read_screen", """{"filter":"all","offset":100}""").detail

        assertTrue("offset 100 starts at the global index 100: $nextPage", """[100] "Item 100"""" in nextPage)
        assertTrue("the next page reaches the last element: $nextPage", """[149] "Item 149"""" in nextPage)
        assertFalse("the next page must not repeat index 99: $nextPage", "[99]" in nextPage)

        // The index is global, not page-relative: a snapshot read with an offset is still actionable through `tap`.
        val tap = b.execute("tap", """{"index":149}""")
        assertEquals(ActionOutcome.Done, tap.outcome)
        assertEquals(149, ui.clickByIndexCalls.last().second)
    }

    @Test fun `read screen truncated still marks truncation`() {
        // 400 nodes is the snapshot cap; paging must not change the snapshot's own truncation fact.
        val nodes = textNodes(MAX_NODES)
        val b = backend(FakeUi(nodes = nodes))

        val firstPage = b.execute("read_screen", """{"filter":"all"}""").detail

        assertTrue("the first page is bounded: $firstPage", """[99] "Item 99"""" in firstPage)
        assertFalse("the 400 cap does not lift the page bound: $firstPage", "[100]" in firstPage)
        assertTrue("the underlying snapshot is truncated: $firstPage", "truncated: true" in firstPage)

        val lastPage = b.execute("read_screen", """{"filter":"all","offset":300}""").detail

        assertTrue("offset 300 addresses the last page: $lastPage", """[300] "Item 300"""" in lastPage)
        assertTrue("the last page reaches the capped tail: $lastPage", """[399] "Item 399"""" in lastPage)
        assertTrue("truncation is a snapshot fact, not a page fact: $lastPage", "truncated: true" in lastPage)
    }

    @Test fun `read screen refuses a negative offset`() {
        val b = backend(FakeUi())

        val result = b.execute("read_screen", """{"offset":-1}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
    }

    @Test fun `read screen refuses a non-numeric offset`() {
        // Same refusal as a negative offset: a malformed page start is a caller error, not a page-0 request, and it is
        // rejected before the screen is read at all (0.5.4 T9 fix round; documented in the descriptor prose).
        val ui = FakeUi()

        val result = backend(ui).execute("read_screen", """{"offset":"x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
        assertEquals("a malformed offset must be refused before any UI read", 0, ui.snapshotCalls)
    }

    @Test fun `offset pages reuse the snapshot page zero was read from`() {
        // 0.5.4 T9 fix round (review Important 1): spec §6.3.1 says pagination is "within the same snapshot". A second
        // `ui.snapshot` here would answer a different tree, so a re-reading implementation would render a screen the
        // model never saw on page 0. The fake is deliberately changing: page 1 must answer from tree A and must not
        // call `ui.snapshot` again.
        val screenA = textNodes(150, prefix = "A")
        val screenB = textNodes(150, prefix = "B")
        val ui = FakeUi(nodes = screenA, laterNodes = screenB)
        val b = backend(ui)

        val firstPage = b.execute("read_screen", """{"filter":"all"}""").detail
        assertTrue("page 0 shows the first read: $firstPage", """[0] "A 0"""" in firstPage)
        assertEquals("page 0 reads the screen once", 1, ui.snapshotCalls)

        val nextPage = b.execute("read_screen", """{"filter":"all","offset":100}""").detail

        assertTrue("offset 100 keeps paging the snapshot page 0 came from: $nextPage", """[100] "A 100"""" in nextPage)
        assertFalse("offset 100 must not splice in a newer screen: $nextPage", "B 100" in nextPage)
        assertEquals("an offset page must not re-read the screen", 1, ui.snapshotCalls)
    }

    @Test fun `a successful tap invalidates the snapshot so the next page reads fresh`() {
        // 0.5.4 T9 fix round 2 (controller ruling): a mutating index action changes the screen, and the accessibility
        // service invalidates its own snapshot after every action. The backend's same-snapshot pagination must not
        // outlive the mutation: read page 0 -> tap succeeds -> offset 100 must re-read, not page the pre-mutation tree.
        val screenA = textNodes(150, prefix = "A")
        val screenB = textNodes(150, prefix = "B")
        val ui = FakeUi(nodes = screenA, laterNodes = screenB)
        val b = backend(ui)

        val firstPage = b.execute("read_screen", """{"filter":"all"}""").detail
        assertTrue("page 0 shows the first read: $firstPage", """[0] "A 0"""" in firstPage)
        assertEquals("page 0 reads the screen once", 1, ui.snapshotCalls)

        val tap = b.execute("tap", """{"index":0}""")
        assertEquals("the tap is dispatched and changes the screen", ActionOutcome.Done, tap.outcome)

        val nextPage = b.execute("read_screen", """{"filter":"all","offset":100}""").detail

        assertTrue("a page after a tap shows the mutated screen: $nextPage", """[100] "B 100"""" in nextPage)
        assertFalse("the pre-mutation tree must not be paged after a tap: $nextPage", "A 100" in nextPage)
        assertEquals("a page after a tap reads the screen again", 2, ui.snapshotCalls)
    }

    @Test fun `long press and input text also invalidate the snapshot on success`() {
        // Same rule as `tap`: every successful mutating index action ends the snapshot it was dispatched against.
        val cases = listOf(
            "long_press" to """{"index":0}""",
            "input_text" to """{"index":0,"text":"hello"}""",
        )
        cases.forEach { (action, args) ->
            val ui = FakeUi(nodes = textNodes(150, prefix = "A"), laterNodes = textNodes(150, prefix = "B"))
            val b = backend(ui)
            b.execute("read_screen", """{"filter":"all"}""")

            val result = b.execute(action, args)
            assertEquals("$action succeeds", ActionOutcome.Done, result.outcome)

            val page = b.execute("read_screen", """{"filter":"all","offset":100}""").detail
            assertTrue("$action must make the next page read fresh: $page", """[100] "B 100"""" in page)
            assertFalse("$action must not leave the pre-action tree pageable: $page", "A 100" in page)
            assertEquals("$action invalidates the snapshot", 2, ui.snapshotCalls)
        }
    }

    @Test fun `a refused index action still invalidates the snapshot`() {
        // The rule is "dispatched", not "succeeded": the snapshot is cleared after the `ui.*ByIndex` call regardless of
        // its boolean, so a later page can never be spliced from a tree the action already touched.
        val cases = listOf(
            "tap" to """{"index":0}""",
            "long_press" to """{"index":0}""",
            "input_text" to """{"index":0,"text":"hello"}""",
        )
        cases.forEach { (action, args) ->
            val ui = FakeUi(
                clickable = false,
                nodes = textNodes(150, prefix = "A"),
                laterNodes = textNodes(150, prefix = "B"),
            )
            val b = backend(ui)
            b.execute("read_screen", """{"filter":"all"}""")

            val result = b.execute(action, args)
            assertEquals("$action is refused by the UI", ActionOutcome.Refused, result.outcome)
            assertEquals("$action refusal", "NODE_NOT_CLICKABLE", result.detail)

            val page = b.execute("read_screen", """{"filter":"all","offset":100}""").detail
            assertTrue("$action still invalidates the snapshot: $page", """[100] "B 100"""" in page)
            assertFalse("a refused $action must not keep the old tree pageable: $page", "A 100" in page)
            assertEquals("a refused $action still makes the next page read fresh", 2, ui.snapshotCalls)
        }
    }

    @Test fun `a successful scroll invalidates the snapshot for later pages`() {
        // Regression guard for the clear that already existed: a scroll moves the screen, so a same-filter offset page
        // after it must re-read instead of paging the pre-scroll tree.
        val ui = FakeUi(nodes = textNodes(150, prefix = "A"), laterNodes = textNodes(150, prefix = "B"))
        val b = backend(ui)
        b.execute("read_screen", """{"filter":"all"}""")

        val scroll = b.execute("scroll", """{"direction":"forward"}""")
        assertEquals("the scroll succeeds", ActionOutcome.Done, scroll.outcome)

        val page = b.execute("read_screen", """{"filter":"all","offset":100}""").detail
        assertTrue("a page after a scroll shows the moved screen: $page", """[100] "B 100"""" in page)
        assertFalse("the pre-scroll tree must not be paged: $page", "A 100" in page)
        assertEquals("a page after a scroll reads the screen again", 2, ui.snapshotCalls)
    }

    @Test fun `an offset page with no matching snapshot reads fresh`() {
        // Documented fallback: `offset > 0` can only continue a snapshot that exists in this turn for the same filter.
        // A cold page, a filter change and a new turn have nothing to page, so each reads the screen once - and that
        // read becomes the snapshot the following page reuses.
        val screenA = textNodes(150, prefix = "A")
        val screenB = textNodes(150, prefix = "B")
        val screenC = textNodes(150, prefix = "C")
        val ui = FakeUi(nodes = screenA, laterNodes = screenB, thirdNodes = screenC)
        val b = backend(ui)

        val cold = b.execute("read_screen", """{"filter":"all","offset":100}""").detail
        assertTrue("a cold offset page reads the screen: $cold", """[100] "A 100"""" in cold)
        assertEquals("a cold offset page reads once", 1, ui.snapshotCalls)

        // A different filter is a different node set: paging the old snapshot would mix two queries.
        val changedFilter = b.execute("read_screen", """{"filter":"interactive","offset":100}""").detail
        assertTrue("a filter change reads fresh: $changedFilter", """[100] "B 100"""" in changedFilter)
        assertEquals("a filter change reads the screen again", 2, ui.snapshotCalls)

        // While the filter still matches, a continuation reuses that snapshot instead of reading again.
        val sameFilter = b.execute("read_screen", """{"filter":"interactive","offset":100}""").detail
        assertTrue("a matching filter keeps paging the stored tree: $sameFilter", """[100] "B 100"""" in sameFilter)
        assertEquals("a matching filter does not re-read the screen", 2, ui.snapshotCalls)

        // A new turn owns a new snapshot: the old one is gone even when the next offset page uses the *same* filter, so
        // this cannot pass merely because a filter mismatch also forces a read.
        b.beginTurn()
        val newTurn = b.execute("read_screen", """{"filter":"interactive","offset":100}""").detail
        assertTrue("a new turn re-reads the screen even with the same filter: $newTurn", """[100] "C 100"""" in newTurn)
        assertFalse("a new turn must not page the previous turn's tree: $newTurn", "B 100" in newTurn)
        assertEquals("a new turn re-reads the screen", 3, ui.snapshotCalls)
    }

    @Test fun `tap is a success only when the screen actually changed`() {
        val ui = FakeUi(fingerprintAfter = "screen-a")

        // Establish snapshot first
        backend(ui).execute("read_screen", "{}")
        val result = backend(ui).also { it.execute("read_screen", "{}") }.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SCREEN_CHANGE", result.detail)
    }

    @Test fun `tap succeeds when the screen changed`() {
        val ui = FakeUi()
        val b = backend(ui)

        b.execute("read_screen", "{}") // establish snapshot
        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(result.detail.contains("tapped"))
    }

    // ── post-action settle (0.5.4 T13, §6.3.4) ──────────────────────────────

    @Test fun `a tap whose UI updates late is a success, not a false no-screen-change`() {
        // Task 13: the one-shot after-fingerprint read reported NO_SCREEN_CHANGE for a navigation that committed a few
        // hundred milliseconds later (the pre-0.5.4 false failure). The settle keeps polling, so the late change is
        // observed inside the 1 s window. Sample 5 ("c") must never be read: two equal samples already proved stillness.
        val ui = FakeUi(fingerprintAnswers = listOf("a", "a", "b", "b", "c"))
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the late change is judged a success", 4, ui.fingerprints)
        assertTrue("the settle is bounded at 1 s: $now", now <= 1_000L)
    }

    @Test fun `the tap settle stops at the first two identical post-change fingerprints`() {
        // Two agreeing captures after the change mean the screen has settled: the loop must stop there, not poll the
        // whole window. The trailing "c" is therefore never read.
        val ui = FakeUi(fingerprintAnswers = listOf("a", "b", "b", "c", "c"))
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("two identical fingerprints stop the settle", 3, ui.fingerprints)
        assertEquals("no extra poll after the pair agreed", 100L, now)
    }

    @Test fun `the tap settle is bounded at one second when the screen keeps moving`() {
        // A UI that never holds still must not hang the tool: the same 1 s bound ends the settle, and a change seen at
        // any point is still a success (the tap did something), unlike the old single sample.
        val ui = FakeUi(fingerprintAnswers = (0..30).map { "screen-$it" })
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the settle never exceeds 1 s", 1_000L, now)
    }

    @Test fun `an unchanged tap still refuses after the whole settle window`() {
        // The no-change verdict may not be concluded from the first two equal samples: a UI that is still on the old
        // screen at 200 ms may update at 300 ms. The window is waited out, then NO_SCREEN_CHANGE is honest.
        val ui = FakeUi(fingerprintAnswers = listOf("a"))
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SCREEN_CHANGE", result.detail)
        assertEquals("the no-change verdict waits the full settle window", 1_000L, now)
        assertTrue("the settle polls instead of trusting one sample: ${ui.fingerprints}", ui.fingerprints > 2)
    }

    @Test fun `a coordinate tap settles a late screen change too`() {
        // The same verdict rule applies to a blind gesture: accepted is not success, and a late change is success.
        val ui = FakeUi(fingerprintAnswers = listOf("a", "a", "b", "b"))
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })

        val result = b.execute("tap", """{"x":540,"y":400}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf(540 to 400), ui.clickAtCalls)
        assertEquals("the late change was seen inside the window", 4, ui.fingerprints)
    }

    @Test fun `input_text reports TEXT_NOT_APPLIED when the text did not land and nothing changed`() {
        // 0.5.4 T12 fix round: the read-back is the only input_text verdict. A field that never takes the text *and* a
        // screen that never moved (fingerprintAfter == fingerprintBefore here) is still "the dispatched write did not
        // apply" - NO_SCREEN_CHANGE stays a tap/long_press refusal, not an input_text one.
        val editableNode = RawSnapshotNode(
            text = "", contentDescription = null, className = null,
            clickable = false, editable = true, scrollable = false, checked = false,
            left = 0, top = 0, right = 100, bottom = 50, hintText = null,
        )
        var now = 0L
        val staleUi = FakeUi(fingerprintAfter = "screen-a", nodes = listOf(editableNode), textAfterType = "")
        val staleB = backend(staleUi, elapsedMillis = { now }, sleepMillis = { now += it })
        staleB.execute("read_screen", "{}")
        val stale = staleB.execute("input_text", """{"index":0,"text":"hello"}""")
        assertEquals(ActionOutcome.Refused, stale.outcome)
        assertEquals("TEXT_NOT_APPLIED", stale.detail)
        assertEquals("the refusal waits out the settle window", 800L, now)

        val okUi = FakeUi(nodes = listOf(editableNode))
        val okB = backend(okUi)
        okB.execute("read_screen", "{}")
        val ok = okB.execute("input_text", """{"index":0,"text":"hello"}""")
        assertEquals(ActionOutcome.Done, ok.outcome)
    }

    // ── input_text read-back (0.5.4 T12) ────────────────────────────────────

    @Test fun `input_text is done only when the field reads the text back`() {
        // 0.5.4 T12: the platform accepting ACTION_SET_TEXT is not success. The backend re-reads the field it wrote to,
        // with the same filter the snapshot was read with (indices are assigned after filtering), and only a match is
        // reported as Done. The fingerprint deliberately does not move here: the field carrying the text is the verdict,
        // not the old "the screen must also have changed" rule. The fake echoes the dispatched text as its content.
        val ui = FakeUi(nodes = listOf(fieldNode()), fingerprintAfter = "screen-a")
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("input_text", """{"index":0,"text":"hello"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the write reached the port once", listOf(0), ui.setTextByIndexCalls.map { it.second })
        assertEquals(
            "the field was re-read at the written index with the snapshot's filter",
            listOf(0 to ScreenFilter.INTERACTIVE),
            ui.textAtCalls,
        )
        assertEquals("a matching read-back must not wait out the settle window", 0L, now)
    }

    @Test fun `input_text reports TEXT_NOT_APPLIED when the field text never changes`() {
        // The write was accepted but the field still reads back empty after the settle window: the platform said yes and
        // the app did not apply it. That is neither NODE_NOT_CLICKABLE (the node accepted the action) nor
        // NO_SCREEN_CHANGE (the screen may have changed) - it is the read-back specifically saying "not applied".
        val ui = FakeUi(nodes = listOf(fieldNode()), textAfterType = "")
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("input_text", """{"index":0,"text":"hello"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("TEXT_NOT_APPLIED", result.detail)
        assertEquals("the write really was dispatched", 1, ui.setTextByIndexCalls.size)
        assertTrue("the read-back is retried, not a single sample: ${ui.textAtCalls}", ui.textAtCalls.size > 1)
        assertEquals("the settle window is bounded at 800 ms", 800L, now)
    }

    @Test fun `input_text accepts text that reaches the field within the settle window`() {
        // Settle, not sleep-once: a field can apply the text after the first poll (IME/async widget). The fake answers
        // empty first and the dispatched text on the second read, so only a polling loop can report Done.
        val ui = FakeUi(nodes = listOf(fieldNode()), readBackAnswers = listOf("", "hello"))
        var now = 0L
        val b = backend(ui, elapsedMillis = { now }, sleepMillis = { now += it })
        b.execute("read_screen", "{}")

        val result = b.execute("input_text", """{"index":0,"text":"hello"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the second poll carried the text", 2, ui.textAtCalls.size)
        assertTrue("the loop waited one poll inside the window: $now", now in 1L..800L)
    }

    @Test fun `reusing an index after a dispatched action reports SNAPSHOT_STALE`() {
        // 0.5.4 T12: every dispatched index action invalidates the snapshot (T9). Reusing its index is not a node that
        // refused the action and not "you never read the screen": the snapshot existed and was invalidated, so the code
        // that tells the model to read the screen again is SNAPSHOT_STALE.
        listOf(
            "tap" to """{"index":0}""",
            "long_press" to """{"index":0}""",
            "input_text" to """{"index":0,"text":"hello"}""",
        ).forEach { (action, args) ->
            val node = RawSnapshotNode(
                text = "", contentDescription = null, className = null,
                clickable = true, editable = true, scrollable = false, checked = false,
                left = 0, top = 0, right = 100, bottom = 50, hintText = null,
            )
            val ui = FakeUi(nodes = listOf(node))
            val b = backend(ui)
            b.execute("read_screen", "{}")
            assertEquals("$action first dispatch succeeds", ActionOutcome.Done, b.execute(action, args).outcome)

            val reused = b.execute(action, args)

            assertEquals("$action reuse is refused", ActionOutcome.Refused, reused.outcome)
            assertEquals("$action must report the invalidated snapshot", "SNAPSHOT_STALE", reused.detail)
            assertNotEquals("$action must not blame the node", "NODE_NOT_CLICKABLE", reused.detail)
            assertNotEquals("$action must not claim no snapshot ever existed", "ARGUMENTS_INVALID", reused.detail)
            // Nothing may reach the UI with the expired snapshot.
            assertEquals("$action dispatches once only", 1, ui.clickByIndexCalls.size + ui.longPressByIndexCalls.size + ui.setTextByIndexCalls.size)
        }
    }

    @Test fun `an index action with no snapshot this turn stays ARGUMENTS_INVALID`() {
        // The other side of the distinction: a cold index (or one after the turn boundary cleared the snapshot) never
        // names a snapshot at all, so it keeps the pre-T12 ARGUMENTS_INVALID answer rather than claiming staleness.
        listOf(
            "tap" to """{"index":0}""",
            "long_press" to """{"index":0}""",
            "input_text" to """{"index":0,"text":"hello"}""",
        ).forEach { (action, args) ->
            val result = backend(FakeUi()).execute(action, args)
            assertEquals("$action cold start", ActionOutcome.Refused, result.outcome)
            assertEquals("$action cold start", "ARGUMENTS_INVALID", result.detail)
        }
    }

    @Test fun `a new turn clears the stale marker so its first index action is the cold case`() {
        // 0.5.4 T12 fix round (review Important 2): beginTurn() drops the snapshot AND the "it was invalidated by an
        // action" marker. Without the second half, an index action right after the turn seam would answer SNAPSHOT_STALE
        // and tell the model to re-read a screen it has not read yet; the cold ARGUMENTS_INVALID case is the correct
        // recovery. The marker is proven set first, so the second half is not passing merely because it was never set.
        val ui = FakeUi()
        val b = backend(ui)
        b.execute("read_screen", "{}")
        assertEquals("the dispatch succeeds", ActionOutcome.Done, b.execute("tap", """{"index":0}""").outcome)
        assertEquals(
            "the dispatched action really left the marker set",
            "SNAPSHOT_STALE",
            b.execute("tap", """{"index":0}""").detail,
        )

        b.beginTurn()

        val result = b.execute("tap", """{"index":0}""")
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("a new turn has read nothing", "ARGUMENTS_INVALID", result.detail)
        assertNotEquals("a new turn must not claim staleness", "SNAPSHOT_STALE", result.detail)
        assertEquals("only the first tap reached the device", 1, ui.clickByIndexCalls.size)
    }

    @Test fun `a stale index reuse emits no act line`() {
        // 0.5.4 T12 fix round: P3_ACT counts actions that reached the device. A stale reuse is refused before dispatch,
        // so logging it would overstate acts and attribute a device line to an action that never touched the device.
        val ui = FakeUi()
        val b = backend(ui)
        b.execute("read_screen", "{}")
        b.execute("tap", """{"index":0}""") // dispatched: it invalidates the snapshot

        val acts = withP3Sink { captured ->
            val stale = b.execute("tap", """{"index":0}""")
            assertEquals("the reuse is stale", "SNAPSHOT_STALE", stale.detail)
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertTrue("a stale reuse never reached the device and emitted no act line: $acts", acts.isEmpty())
    }

    @Test fun `an invalid snapshot at the port is SNAPSHOT_STALE and dispatches nothing`() {
        // The channel owns the authoritative snapshot and can invalidate it independently (a newer read on the same
        // service). The backend's copy must not turn that "false" into NODE_NOT_CLICKABLE - the node was never asked.
        val ui = FakeUi(nodes = listOf(fieldNode()), snapshotValid = false)
        val b = backend(ui)
        b.execute("read_screen", "{}")

        val result = b.execute("input_text", """{"index":0,"text":"hello"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("SNAPSHOT_STALE", result.detail)
        assertTrue("an invalid snapshot must not reach the UI: ${ui.setTextByIndexCalls}", ui.setTextByIndexCalls.isEmpty())
    }

    @Test fun `an unavailable service refuses instead of pretending`() {
        val ui = FakeUi()

        val result = backend(ui, connected = false).execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
        assertTrue("a down channel must advertise nothing through the router",
            ExecutionRouter(listOf(backend(ui, connected = false))).descriptors().isEmpty())
    }

    @Test fun `no action may record screen text in the audit trail`() {
        val byName = backend(FakeUi()).descriptors().associateBy { it.name }

        assertEquals(setOf("read_screen", "tap", "long_press", "scroll", "input_text"), byName.keys)
        byName.values.forEach { descriptor ->
            assertEquals("${descriptor.name} scope", "app.ui", descriptor.scope)
            // Screen content is private; recording arguments would be recording what the user saw.
            assertEquals("${descriptor.name} audit target", null, descriptor.auditTargetArgument)
        }
    }

    @Test fun `blank or multiline text in input_text is refused`() {
        val editableNode = RawSnapshotNode(
            text = "", contentDescription = null, className = null,
            clickable = false, editable = true, scrollable = false, checked = false,
            left = 0, top = 0, right = 100, bottom = 50, hintText = null,
        )
        val ui = FakeUi(nodes = listOf(editableNode))
        val b = backend(ui)

        listOf("""{"index":0,"text":""}""", """{"index":0,"text":"two\nlines"}""", """{"index":0,"text":"   "}""").forEach { args ->
            b.execute("read_screen", "{}") // re-establish snapshot
            val result = b.execute("input_text", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(ui.setTextByIndexCalls.isEmpty())
    }

    @Test fun `an unknown action name is refused rather than executed`() {
        val ui = FakeUi()

        assertEquals(ActionOutcome.Refused, backend(ui).execute("tap_anything", "{}").outcome)
    }

    @Test fun `index actions report only their via token`() {
        // 0.5.4 Task 8 (§6.2.2): `P3_ACT` starts with the targeting mode this task really has — index. Coordinate taps
        // arrive in Task 11 and extend the same token vocabulary; the line must never carry screen text or the index.
        val ui = FakeUi()
        val b = backend(ui)

        val acts = withP3Sink { captured ->
            fun readAnd(action: String, args: String) {
                b.execute("read_screen", "{}")
                b.execute(action, args)
            }
            readAnd("tap", """{"index":0}""")
            readAnd("long_press", """{"index":0}""")
            readAnd("input_text", """{"index":0,"text":"hello"}""")
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertEquals(listOf("P3_ACT via=index", "P3_ACT via=index", "P3_ACT via=index"), acts)
        assertTrue(
            "no screen or typed text may ride it: $acts",
            acts.none { it.contains("WLAN") || it.contains("hello") },
        )
    }

    @Test fun `a refused index action emits no act line`() {
        // 0.5.4 Task 8 fix round (review Important 1): `P3_ACT` reports a **dispatched** index action, not an attempt.
        // A refusal returns before `ui.*ByIndex` is ever called, so counting it would make a device log overstate acts
        // and attribute a device line to an action that never touched the device.
        val ui = FakeUi()
        val b = backend(ui)

        val acts = withP3Sink { captured ->
            // No snapshot established: all three index actions are refused before any UI call.
            b.execute("tap", """{"index":0}""")
            b.execute("long_press", """{"index":0}""")
            b.execute("input_text", """{"index":0,"text":"hello"}""")
            // A real snapshot but an out-of-range index.
            b.execute("read_screen", "{}")
            b.execute("tap", """{"index":9}""")
            b.execute("long_press", """{"index":9}""")
            b.execute("input_text", """{"index":9,"text":"hello"}""")
            // A real snapshot and index but invalid text.
            b.execute("input_text", """{"index":0,"text":""}""")
            b.execute("input_text", """{"index":0,"text":"two\nlines"}""")
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertTrue("a refused action was never dispatched and must emit no act line: $acts", acts.isEmpty())
        assertTrue("a refused action must not reach the UI: ${ui.clickByIndexCalls}", ui.clickByIndexCalls.isEmpty())
        assertTrue("a refused action must not reach the UI: ${ui.setTextByIndexCalls}", ui.setTextByIndexCalls.isEmpty())
    }

    @Test fun `the click result alone is not the verdict`() {
        // `clickByIndex` answered `false` (the node was not clickable): whatever the screen does afterwards, this is a
        // failure, and the code says why.
        val ui = FakeUi(clickable = false)
        val b = backend(ui)
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NODE_NOT_CLICKABLE", result.detail)
        assertFalse(result.detail == "NO_SCREEN_CHANGE")
    }

    // ── coordinate targets (0.5.4 T11) ──────────────────────────────────────

    @Test fun `tap refuses an ambiguous target shape`() {
        // Spec §6.3.3: index and x,y are mutually exclusive, and at least one target must be present. Both and neither
        // are caller errors, so the call is refused before any gate or UI read - a malformed target must never be
        // guessed into a nearby index or point.
        val ui = FakeUi()
        val b = backend(ui)

        listOf(
            "{}",
            """{"index":0,"x":540,"y":400}""",
            """{"index":0,"x":540}""",
            """{"index":0,"y":400}""",
            """{"x":"mid","y":400}""",
            """{"x":540,"y":null}""",
        ).forEach { args ->
            val result = b.execute("tap", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue("no ambiguous target may reach the UI: ${ui.clickAtCalls}", ui.clickAtCalls.isEmpty())
        assertTrue("no ambiguous target may reach the UI: ${ui.clickByIndexCalls}", ui.clickByIndexCalls.isEmpty())
    }

    @Test fun `a half given point is MISSING_POINT`() {
        // One coordinate is not a point: x alone or y alone can only mean the caller lost half of its target.
        val ui = FakeUi()
        val b = backend(ui)

        listOf("""{"x":540}""", """{"y":400}""").forEach { args ->
            listOf("tap", "long_press").forEach { action ->
                val result = b.execute(action, args)
                assertEquals("$action $args", ActionOutcome.Refused, result.outcome)
                assertEquals("$action $args", "MISSING_POINT", result.detail)
            }
        }
        assertTrue(ui.clickAtCalls.isEmpty())
        assertTrue(ui.longPressAtCalls.isEmpty())
    }

    @Test fun `a coordinate tap dispatches one gesture at the screen point`() {
        val ui = FakeUi()

        val result = backend(ui).execute("tap", """{"x":540,"y":400}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf(540 to 400), ui.clickAtCalls)
        assertTrue("a coordinate tap never resolves an index", ui.clickByIndexCalls.isEmpty())
    }

    @Test fun `a coordinate long press dispatches one long press at the screen point`() {
        val ui = FakeUi()

        val result = backend(ui).execute("long_press", """{"x":540,"y":400}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf(540 to 400), ui.longPressAtCalls)
        assertTrue("a coordinate long press never resolves an index", ui.clickByIndexCalls.isEmpty())
        // The fake now records the index long-press too: before this, a fallback to the index path was invisible here.
        assertTrue("a coordinate long press never falls back to an index", ui.longPressByIndexCalls.isEmpty())
    }

    @Test fun `coordinate actions are refused while the keyguard is up`() {
        // Spec §109/§6.3.3: a coordinate gesture is injected blindly, so the channel must prove the screen is unlocked
        // first. Both actions fail closed with the code that names the lock, and nothing is dispatched.
        val ui = FakeUi(keyguardLocked = true)
        val b = backend(ui)

        listOf("tap" to ui.clickAtCalls, "long_press" to ui.longPressAtCalls).forEach { (action, calls) ->
            val result = b.execute(action, """{"x":540,"y":400}""")
            assertEquals("$action is refused when locked", ActionOutcome.Refused, result.outcome)
            assertEquals("$action refusal", "COORD_REFUSED_KEYGUARD", result.detail)
            assertTrue("$action dispatches nothing when locked", calls.isEmpty())
        }
    }

    @Test fun `coordinate actions are refused on system ui and permission dialogs`() {
        // The foreground app is the only witness this port has. A system surface - the status/nav bar owner, a
        // permission controller, an installer, or an unknown foreground - is not a target app, so a blind gesture is
        // refused instead of clicking through a dialog the user did not ask EDITH to touch.
        listOf(
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            null,
        ).forEach { packageName ->
            val ui = FakeUi(foregroundPackage = packageName)
            val result = backend(ui).execute("tap", """{"x":540,"y":400}""")
            assertEquals("foreground=$packageName", ActionOutcome.Refused, result.outcome)
            assertEquals("foreground=$packageName", "COORD_REFUSED_SYSTEM_UI", result.detail)
            assertTrue("foreground=$packageName dispatches nothing", ui.clickAtCalls.isEmpty())
        }
    }

    @Test fun `a coordinate action requires the focused window to be a normal app`() {
        // 0.5.4 T11 fix round: the foreground package alone is a package-level witness, so a system overlay that never
        // raises a window-state event could slip through. The window type is a second witness: the focused window must
        // be TYPE_APPLICATION. `true` plus a normal foreground dispatches; `false` (system/IME/keyguard window) and
        // `null` (cannot tell) both fail closed and dispatch nothing.
        val allowed = FakeUi(focusedWindow = true)
        val dispatched = backend(allowed).execute("tap", """{"x":540,"y":400}""")
        assertEquals(ActionOutcome.Done, dispatched.outcome)
        assertEquals(listOf(540 to 400), allowed.clickAtCalls)

        listOf(false, null).forEach { focused ->
            val ui = FakeUi(focusedWindow = focused)
            val b = backend(ui)
            listOf("tap" to ui.clickAtCalls, "long_press" to ui.longPressAtCalls).forEach { (action, calls) ->
                val result = b.execute(action, """{"x":540,"y":400}""")
                assertEquals("focused=$focused $action", ActionOutcome.Refused, result.outcome)
                assertEquals("focused=$focused $action", "COORD_REFUSED_SYSTEM_UI", result.detail)
                assertTrue("focused=$focused $action dispatches nothing", calls.isEmpty())
            }
        }
    }

    @Test fun `a coordinate action refuses a point outside the screen`() {
        // Fail closed on out-of-screen points: they are not a usable on-screen point, so MISSING_POINT (the same code a
        // half-given point uses) answers and no gesture is dispatched.
        val ui = FakeUi()
        val b = backend(ui)

        listOf(
            """{"x":-1,"y":400}""",
            """{"x":1080,"y":400}""",
            """{"x":540,"y":2400}""",
            """{"x":540,"y":-1}""",
        ).forEach { args ->
            val result = b.execute("tap", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "MISSING_POINT", result.detail)
        }
        assertTrue(ui.clickAtCalls.isEmpty())
    }

    @Test fun `an unreadable screen size fails closed`() {
        // Without a screen rectangle the backend cannot prove the point is inside the display; guessing 0..Int.MAX
        // would dispatch a blind gesture.
        val ui = FakeUi(screen = null)

        val result = backend(ui).execute("long_press", """{"x":540,"y":400}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("MISSING_POINT", result.detail)
        assertTrue(ui.longPressAtCalls.isEmpty())
    }

    @Test fun `a coordinate action is judged by the screen change like an index action`() {
        // The gesture was dispatched, so the verdict is still "did the screen change": accepted is not success.
        val ui = FakeUi(fingerprintAfter = "screen-a")

        val result = backend(ui).execute("tap", """{"x":540,"y":400}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SCREEN_CHANGE", result.detail)
        assertEquals("the gesture really was dispatched once", 1, ui.clickAtCalls.size)
    }

    @Test fun `a gesture the platform refused is not a success`() {
        // `dispatchGesture` can answer false (cancelled/refused). There is no node to blame, so the model gets the same
        // observable verdict as an unchanged screen rather than a fabricated success.
        val ui = FakeUi(gestureAccepted = false)

        val result = backend(ui).execute("long_press", """{"x":540,"y":400}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SCREEN_CHANGE", result.detail)
    }

    @Test fun `coordinate actions report via=coord while index actions keep via=index`() {
        // R2: the same count-only P3_ACT line carries the targeting mode that actually reached the device. The index
        // and the point never ride it, and a refusal emits no line at all.
        val ui = FakeUi()
        val b = backend(ui)

        val acts = withP3Sink { captured ->
            b.execute("tap", """{"x":540,"y":400}""")
            b.execute("long_press", """{"x":540,"y":400}""")
            b.execute("read_screen", "{}")
            b.execute("tap", """{"index":0}""")
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertEquals(listOf("P3_ACT via=coord", "P3_ACT via=coord", "P3_ACT via=index"), acts)
        assertTrue("neither a point nor an index may ride the line: $acts", acts.none { it.contains("540") || it.contains("400") })
    }

    @Test fun `a refused coordinate action emits no act line`() {
        val ui = FakeUi(keyguardLocked = true)

        val acts = withP3Sink { captured ->
            backend(ui).execute("tap", """{"x":540,"y":400}""")
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertTrue("a refused coordinate action was never dispatched: $acts", acts.isEmpty())
    }

    @Test fun `a dispatched coordinate action invalidates the snapshot`() {
        // A blind gesture can move the screen just like an index click. A later `offset` page must not continue the
        // pre-gesture tree, exactly as Task 9's mutation rule already requires for index actions.
        val ui = FakeUi(nodes = textNodes(150, prefix = "A"), laterNodes = textNodes(150, prefix = "B"))
        val b = backend(ui)
        b.execute("read_screen", """{"filter":"all"}""")

        val tap = b.execute("tap", """{"x":540,"y":400}""")
        assertEquals(ActionOutcome.Done, tap.outcome)

        val page = b.execute("read_screen", """{"filter":"all","offset":100}""").detail
        assertTrue("a page after a coordinate tap shows the mutated screen: $page", """[100] "B 100"""" in page)
        assertFalse("a coordinate tap must not leave the old tree pageable: $page", "A 100" in page)
        assertEquals("a page after a coordinate tap reads the screen again", 2, ui.snapshotCalls)
    }
}
