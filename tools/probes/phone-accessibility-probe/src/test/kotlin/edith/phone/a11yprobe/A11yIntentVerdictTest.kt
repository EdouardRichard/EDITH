package edith.phone.a11yprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.0 A1's numbers, pinned before the probe runs.
 *
 * T3b could only ask "did the screen change". Every judgement here asks the different question "did the *intent* happen":
 * the destination screen carried a marker only that screen has, the runtime permission really flipped to GRANTED, and a
 * tap that landed somewhere else is counted against us instead of being silent.
 *
 * The arithmetic and the verdict text are unit-tested because the device cannot be asked to explain a number. A real
 * device that prints `scrollOk=0/1` says nothing about whether the probe aimed wrong or the phone refused; the reason
 * classes below are what make that zero readable.
 *
 * The mis-tap numerator is pinned to the **coordinate** path. A node click cannot land on another row (the framework
 * delivers it to the node it names), so counting label mismatches there measured nothing — and on the K40 it reported
 * two mis-taps for two clicks that both reached the intended screen.
 */
class A11yIntentVerdictTest {

    // ------------------------------------------------------------------ scroll: the stop condition

    /**
     * The reason the probe stops scrolling. These are the four facts the device can produce, and each implies a
     * different fix: "arrived" needs no fix, "granted" and "denied" are the popup path's own outcomes, "no progress"
     * means the list ended before the target, and "budget" means the probe simply gave up mid-list. Collapsing them
     * into one "failed" would hide that difference.
     */
    @Test
    fun scrollDecisionStopsAtEachDistinctOutcome() {
        assertEquals("arrived", scrollDecision(targetVisible = true, fingerprinted = true, scrollsDone = 0, maxScrolls = 6))
        assertEquals("no_progress", scrollDecision(targetVisible = false, fingerprinted = false, scrollsDone = 1, maxScrolls = 6))
        assertEquals("budget", scrollDecision(targetVisible = false, fingerprinted = true, scrollsDone = 6, maxScrolls = 6))
        assertEquals("scroll", scrollDecision(targetVisible = false, fingerprinted = true, scrollsDone = 0, maxScrolls = 6))
    }

    /**
     * "Visible" wins even when the budget is already exhausted: a target that came into view on the last allowed
     * scroll was still reached, and reporting `budget` there would turn a success into a failure.
     */
    @Test
    fun scrollDecisionPrefersArrivalOverTheBudget() {
        assertEquals("arrived", scrollDecision(targetVisible = true, fingerprinted = false, scrollsDone = 6, maxScrolls = 6))
    }

    // ------------------------------------------------------------------ scroll: which label matched

    /**
     * The first candidate in the list wins, not the label the tree happened to expose first, because candidate order
     * *is* the priority decision: Chinese labels are listed before English ones so a MIUI build is read with its own
     * language. Only one language is present on a real device, so the two fixtures below are a Chinese screen and an
     * English screen — a screen carrying both would never occur and would not pin the ordering.
     */
    @Test
    fun firstCandidateInPrefersTheEarlierCandidateOverTheLaterOne() {
        val chineseScreen = listOf("网络和互联网", "WLAN 助理")
        assertEquals("网络和互联网", firstCandidateIn(listOf("网络和互联网", "Network & internet"), chineseScreen))
        val englishScreen = listOf("Network & internet", "Mobile network")
        assertEquals("Network & internet", firstCandidateIn(listOf("网络和互联网", "Network & internet"), englishScreen))
        assertNull(firstCandidateIn(listOf("蓝牙", "Bluetooth"), chineseScreen))
    }

    /**
     * Matching is exact, never a substring: "WLAN" is a substring of "WLAN 助理" (a *different* Settings row), and a
     * substring match would let the probe claim it reached Wi-Fi by clicking something else. This is the same class of
     * mistake as T3b's "the screen changed, therefore success".
     */
    @Test
    fun firstCandidateInRequiresAnExactLabelMatch() {
        assertNull(firstCandidateIn(listOf("WLAN"), listOf("WLAN 助理")))
        assertNull(firstCandidateIn(listOf("WLAN"), listOf("已连接 WLAN")))
    }

    /** A blank or empty screen matches nothing — the probe must never read "no window" as arrival. */
    @Test
    fun firstCandidateInTreatsAnEmptyScreenAsNoMatch() {
        assertNull(firstCandidateIn(listOf("WLAN"), emptyList()))
        assertNull(firstCandidateIn(listOf("WLAN"), listOf("", "   ")))
    }

    // ------------------------------------------------------------------ popup: the grant verdict

    /**
     * The popup's success criterion is device state, never pixels. `checkSelfPermission` returning GRANTED is the only
     * thing that counts; the dialog having disappeared is what T3b would have accepted and is exactly the signal this
     * mode was written to replace.
     */
    @Test
    fun popupVerdictReadsGrantedFromThePermissionState() {
        assertEquals("granted", popupVerdict(permissionState = "granted", dialogSeen = true, affirmativeClicked = true))
        assertEquals("granted", popupVerdict(permissionState = "granted", dialogSeen = false, affirmativeClicked = false))
    }

    /** The dialog was acted on and the permission stayed denied — a click that landed, but not on the right control. */
    @Test
    fun popupVerdictSeparatesADeniedGrantFromAMissingDialog() {
        assertEquals("denied", popupVerdict(permissionState = "denied", dialogSeen = true, affirmativeClicked = true))
        assertEquals("no_dialog", popupVerdict(permissionState = "denied", dialogSeen = false, affirmativeClicked = false))
    }

    /**
     * A dialog on screen whose affirmative control could not be found is its own reason: the fix is a label list, not a
     * click mechanism. Folding it into `denied` would send the next session looking for the wrong bug.
     */
    @Test
    fun popupVerdictSeparatesAMissingAffirmativeFromARefusedOne() {
        assertEquals("no_affirmative", popupVerdict(permissionState = "denied", dialogSeen = true, affirmativeClicked = false))
    }

    // ------------------------------------------------------------------ mis-taps: node clicks cannot mis-land

    /**
     * A **node** click can never be a mis-tap, by construction: `performAction(ACTION_CLICK)` is delivered by the
     * framework to that very node, so "the click went somewhere else" is not a state the node path can produce.
     *
     * The rule this replaces compared the clicked node's label with the intended label, and because a clickable row
     * container usually carries no text of its own its label degenerates to the class name and can never equal the
     * intended label. That is how the K40 run printed `misTaps=2` for two clicks that both reached the intended screen.
     */
    @Test
    fun aNodeClickIsNeverAMisTapByConstruction() {
        val record = TapRecord(
            intended = "省电与电池",
            describedAs = "android.widget.LinearLayout",
            arrived = true,
            markerSeen = true,
            failure = null,
            mode = TapMode.NODE,
            hitIntended = false,
        )
        assertFalse("a node click cannot land anywhere but on its own node", isMisTap(record))
    }

    /** A coordinate tap that landed on a row the probe did not aim at is the mis-tap this numerator exists for. */
    @Test
    fun aCoordinateTapOntoAnUnrelatedRowIsAMisTap() {
        val record = TapRecord(
            intended = "省电与电池",
            describedAs = "WLAN",
            arrived = false,
            markerSeen = false,
            failure = "marker_missing",
            mode = TapMode.COORDINATE,
            hitIntended = false,
        )
        assertTrue(isMisTap(record))
    }

    /**
     * The K40 regression, pinned: the node read back at the tap point is the row *container*, an unlabelled
     * `LinearLayout` whose label is its class name and therefore never equals the intended label. Relatedness, not label
     * equality, is the criterion — so this tap is not a mis-tap.
     */
    @Test
    fun aCoordinateTapOntoTheIntendedRowIsNotAMisTapEvenWhenItsLabelIsAClassName() {
        val record = TapRecord(
            intended = "省电与电池",
            describedAs = "android.widget.LinearLayout",
            arrived = true,
            markerSeen = true,
            failure = null,
            mode = TapMode.COORDINATE,
            hitIntended = true,
        )
        assertFalse(isMisTap(record))
    }

    /**
     * Label equality is not relatedness: two rows can carry the same text, and a coordinate tap that landed on the
     * *other* one is still a mis-tap. This is the case that keeps the numerator from collapsing back into the old
     * label-equality rule.
     */
    @Test
    fun aCoordinateTapOntoADifferentNodeWithTheSameLabelIsStillAMisTap() {
        val record = TapRecord(
            intended = "省电与电池",
            describedAs = "省电与电池",
            arrived = false,
            markerSeen = false,
            failure = "marker_missing",
            mode = TapMode.COORDINATE,
            hitIntended = false,
        )
        assertTrue(isMisTap(record))
    }

    /**
     * A coordinate tap that hit nothing at all is the worst case and must stay in the numerator: the point landed on no
     * node, which is a miss and not an absence of a measurement. (A refused gesture is the same reading — it stays
     * visible as its own `click_refused` failure class.)
     */
    @Test
    fun aCoordinateTapThatHitsNothingIsAMisTap() {
        val record = TapRecord(
            intended = "省电与电池",
            describedAs = null,
            arrived = false,
            markerSeen = false,
            failure = "click_refused",
            mode = TapMode.COORDINATE,
            hitIntended = false,
        )
        assertTrue(isMisTap(record))
    }

    // ------------------------------------------------------------------ coordinate hit: relatedness by tree position

    /**
     * "Hit the intent" means the node under the tap point is the intended node itself, one of its ancestors, or one of
     * its descendants — and that is exactly a prefix relation between the two child-index paths from the window root.
     *
     * Comparing labels instead calls the unlabelled row container a miss (the K40 false positive); comparing only node
     * identity calls a tap on the row's own child label a miss. Relatedness by tree position is the criterion that
     * accepts both and still rejects the row below.
     */
    @Test
    fun hitIntendedAcceptsTheIntendedNodeItsAncestorsAndItsDescendants() {
        val intended = listOf(1, 3, 2)
        assertTrue("the intended node itself", isHitIntended(intended, listOf(1, 3, 2)))
        assertTrue("an ancestor of the intended node", isHitIntended(intended, listOf(1, 3)))
        assertTrue("a descendant of the intended node", isHitIntended(intended, listOf(1, 3, 2, 0)))
        assertTrue("a descendant several levels down", isHitIntended(intended, listOf(1, 3, 2, 0, 4)))
        assertTrue("both sides are the window root", isHitIntended(emptyList(), emptyList()))
    }

    /** A sibling row, or a point with no node under it at all, is a miss. */
    @Test
    fun hitIntendedRejectsASiblingRowAndAPointThatHitNothing() {
        val intended = listOf(1, 3, 2)
        assertFalse("the next row in the same list", isHitIntended(intended, listOf(1, 3, 4)))
        assertFalse("a different branch of the tree", isHitIntended(intended, listOf(2, 0)))
        assertFalse("nothing under the point", isHitIntended(intended, null))
        assertFalse("no intended node to relate to", isHitIntended(null, listOf(1, 3, 2)))
        assertFalse("neither side resolved", isHitIntended(null, null))
    }

    // ------------------------------------------------------------------ counters

    /**
     * Two attempts, one of which landed on another row by coordinate: the counters must let a reader compute both the
     * rate and the denominator from the line alone. A numerator without its denominator is the defect §12#5 named.
     */
    @Test
    fun countersCarryBothTheDenominatorAndTheMisTapNumerator() {
        val counters = countersOf(
            listOf(
                TapRecord("WLAN", describedAs = "WLAN", arrived = true, markerSeen = true, failure = null),
                TapRecord(
                    "WLAN", describedAs = "WLAN 助理", arrived = false, markerSeen = false, failure = "marker_missing",
                    mode = TapMode.COORDINATE, hitIntended = false,
                ),
            ),
            listOf(PopupRecord("granted", affirmativeClicked = true), PopupRecord("no_dialog", affirmativeClicked = false)),
        )
        assertEquals("the denominator is every tap plus every popup attempt that actually measured something", 4, counters.attempts)
        assertEquals(1, counters.misTaps)
        assertEquals(1, counters.intentMet)
        assertEquals(2, counters.scrollRuns)
        assertEquals(1, counters.scrollOk)
        assertEquals(1, counters.popupOk)
        assertEquals(2, counters.popupRuns)
        assertEquals(0, counters.popupSkipped)
        assertEquals(1, counters.failures.getValue("no_dialog"))
    }

    /**
     * `scrollOk` and `intentMet` are deliberately different numbers. `scrollOk` says the list move worked; `intentMet`
     * says the marker proved the destination was reached. A run that scrolls into a *different* screen scores the first
     * and not the second, and merging them would hide the "scrolled fine, wrong place" case that this whole mode exists
     * to expose.
     */
    @Test
    fun countersSeparateTheScrollSucceedingFromTheMarkerBeingSeen() {
        val counters = countersOf(
            listOf(
                TapRecord("入口", describedAs = "入口", arrived = true, markerSeen = true, failure = null),
                TapRecord("入口", describedAs = "其他", arrived = true, markerSeen = false, failure = "marker_missing"),
            ),
            emptyList(),
        )
        assertEquals(2, counters.scrollOk)
        assertEquals(1, counters.intentMet)
        assertTrue(counters.failures.containsKey("marker_missing"))
    }

    /**
     * Taps and popup attempts are summed into one `attempts` denominator, because the acceptance question is "how often
     * did the probe touch the wrong thing", not "per sub-scenario". `intentMet` counts scroll arrivals only: the popup
     * path has its own numerator (`popupOk`) and adding it here would double-count one action.
     */
    @Test
    fun countersSumTapsAndPopupAttemptsIntoOneDenominator() {
        val counters = countersOf(
            listOf(
                TapRecord("入口", describedAs = "入口", arrived = true, markerSeen = true, failure = null),
                TapRecord(
                    "入口", describedAs = "其他", arrived = true, markerSeen = false, failure = "marker_missing",
                    mode = TapMode.COORDINATE, hitIntended = false,
                ),
                TapRecord("入口", describedAs = "入口", arrived = true, markerSeen = true, failure = null),
            ),
            listOf(PopupRecord("denied", affirmativeClicked = true)),
        )
        assertEquals(4, counters.attempts)
        assertEquals(1, counters.misTaps)
        assertEquals("every tap that arrived counts as a successful scroll attempt", 3, counters.scrollOk)
        assertEquals(2, counters.intentMet)
        assertEquals(3, counters.scrollRuns)
        assertEquals(0, counters.popupOk)
        assertEquals(1, counters.popupRuns)
        assertTrue(counters.failures.containsKey("denied"))
    }

    /**
     * A node click that was never dispatched is recorded as a *failure* (`click_refused`) and still counts as an
     * attempt, but under the new criterion it is not a mis-tap: nothing was delivered anywhere, so there is no landing
     * to judge. The failure class keeps it visible, and mixing "the probe could not act" into the mis-tap numerator
     * would merge two different defects.
     */
    @Test
    fun countersRecordANeverDispatchedNodeClickAsAFailureRatherThanAMisTap() {
        val counters = countersOf(
            listOf(TapRecord("入口", describedAs = null, arrived = false, markerSeen = false, failure = "click_refused")),
            emptyList(),
        )
        assertEquals(1, counters.attempts)
        assertEquals(0, counters.misTaps)
        assertEquals(0, counters.scrollOk)
        assertTrue(counters.failures.containsKey("click_refused"))
    }

    /**
     * The numerator is measured on the coordinate path only, and the denominator stays every dispatched tap. The K40
     * shape — two node clicks on an unlabelled row container, one coordinate hit and one coordinate miss — must produce
     * `misTaps=1`, not `misTaps=3`.
     */
    @Test
    fun countersCountOnlyCoordinateMissesAsMisTaps() {
        val counters = countersOf(
            listOf(
                TapRecord(
                    "省电与电池", describedAs = "android.widget.LinearLayout", arrived = true, markerSeen = true,
                    failure = null, mode = TapMode.NODE, hitIntended = false,
                ),
                TapRecord(
                    "省电与电池", describedAs = "android.widget.LinearLayout", arrived = true, markerSeen = true,
                    failure = null, mode = TapMode.NODE, hitIntended = false,
                ),
                TapRecord(
                    "省电与电池", describedAs = "android.widget.LinearLayout", arrived = true, markerSeen = true,
                    failure = null, mode = TapMode.COORDINATE, hitIntended = true,
                ),
                TapRecord(
                    "省电与电池", describedAs = "WLAN", arrived = false, markerSeen = false, failure = "marker_missing",
                    mode = TapMode.COORDINATE, hitIntended = false,
                ),
            ),
            emptyList(),
        )
        assertEquals("one coordinate miss out of four dispatched taps", 1, counters.misTaps)
        assertEquals(4, counters.attempts)
        assertEquals("two coordinate rounds were run", 2, counters.coordRuns)
        assertEquals("one of them hit the intended row", 1, counters.coordOk)
        assertEquals(3, counters.intentMet)
    }

    /**
     * The exact K40 `A1_SCROLL` shape, pinned: both rounds reached the intended screen (`markerSeen=true`) while the
     * clicked row container's label was its class name. A tap record that names no mode is a node click (the old
     * construction shape), so this run must score `misTaps=0`; scoring it 2 is the false positive this change removes.
     */
    @Test
    fun countersDoNotCountANodeClickOnAnUnlabelledRowContainerAsAMisTap() {
        val counters = countersOf(
            listOf(
                TapRecord("省电与电池", describedAs = "android.widget.LinearLayout", arrived = true, markerSeen = true, failure = null),
                TapRecord("省电与电池", describedAs = "android.widget.LinearLayout", arrived = true, markerSeen = true, failure = null),
            ),
            emptyList(),
        )
        assertEquals(0, counters.misTaps)
        assertEquals(2, counters.intentMet)
        assertEquals("no coordinate round ran", 0, counters.coordRuns)
        assertEquals(0, counters.coordOk)
    }

    /**
     * A popup that was skipped because the permission was already GRANTED is excluded from `popupRuns`: it measured
     * nothing, and letting it into the denominator would report a success the probe never produced. It gets its own
     * `popupSkipped` field so the omission is visible instead of silent.
     */
    @Test
    fun countersKeepASkippedPopupOutOfThePopupDenominator() {
        val counters = countersOf(emptyList(), listOf(PopupRecord("skipped", affirmativeClicked = false)))
        assertEquals(0, counters.popupRuns)
        assertEquals(0, counters.popupOk)
        assertEquals(1, counters.popupSkipped)
    }

    /**
     * An empty run is all zeros, never a vacuous 100%: a probe that ran nothing must not be quotable as a success.
     */
    @Test
    fun countersOfAnEmptyRunAreAllZero() {
        val counters = countersOf(emptyList(), emptyList())
        assertEquals(0, counters.attempts)
        assertEquals(0, counters.misTaps)
        assertEquals(0, counters.intentMet)
        assertEquals(0, counters.scrollOk)
        assertEquals(0, counters.scrollRuns)
        assertEquals(0, counters.coordOk)
        assertEquals(0, counters.coordRuns)
        assertEquals(0, counters.popupOk)
        assertEquals(0, counters.popupRuns)
        assertEquals(0, counters.popupSkipped)
        assertEquals(0, counters.failures.size)
    }

    // ------------------------------------------------------------------ the verdict line

    /**
     * The whole point of the line is that the ratios are computable from it by eye. Every number therefore has to be
     * present, even when it is zero, and the failure classes have to be on the same line as the counts.
     *
     * `coordOk` is appended **after** the existing fields so a reader — and the earlier evidence — can still quote the
     * prefix `P3_A1_VERDICT attempts=… popupSkipped=…` unchanged.
     */
    @Test
    fun verdictLineCarriesEveryNumberAndTheFailureClasses() {
        val counters = A11yIntentCounters(
            attempts = 5,
            misTaps = 1,
            intentMet = 2,
            scrollRuns = 3,
            scrollOk = 2,
            coordRuns = 2,
            coordOk = 1,
            popupRuns = 2,
            popupOk = 1,
            popupSkipped = 0,
            failures = mapOf("denied" to 1, "no_progress" to 1),
        )
        assertEquals(
            "P3_A1_VERDICT attempts=5 misTaps=1 intentMet=2 scrollOk=2/3 popupOk=1/2 popupSkipped=0 " +
                "failures=denied=1,no_progress=1 coordOk=1/2",
            verdictLine(counters),
        )
    }

    /**
     * `failures=` is empty rather than absent on a clean run: a key that vanishes when there is nothing to report makes
     * a clean run and a truncated log look identical. `coordOk=0/0` is printed for the same reason — a run with no
     * coordinate round must say so rather than drop the field.
     */
    @Test
    fun verdictLineKeepsAnEmptyFailureFieldRatherThanDroppingIt() {
        val counters = A11yIntentCounters(
            attempts = 1,
            misTaps = 0,
            intentMet = 1,
            scrollRuns = 1,
            scrollOk = 1,
            coordRuns = 0,
            coordOk = 0,
            popupRuns = 0,
            popupOk = 0,
            popupSkipped = 1,
            failures = emptyMap(),
        )
        assertEquals(
            "P3_A1_VERDICT attempts=1 misTaps=0 intentMet=1 scrollOk=1/1 popupOk=0/0 popupSkipped=1 " +
                "failures= coordOk=0/0",
            verdictLine(counters),
        )
    }

    /** Failure classes are ordered so two runs of the same device produce byte-identical lines that can be diffed. */
    @Test
    fun verdictLineOrdersFailureClassesDeterministically() {
        val counters = A11yIntentCounters(
            attempts = 0,
            misTaps = 0,
            intentMet = 0,
            scrollRuns = 0,
            scrollOk = 0,
            coordRuns = 0,
            coordOk = 0,
            popupRuns = 0,
            popupOk = 0,
            popupSkipped = 0,
            failures = mapOf("no_progress" to 2, "denied" to 1),
        )
        assertEquals(
            "failures=denied=1,no_progress=2 coordOk=0/0",
            verdictLine(counters).substringAfter("popupSkipped=0 "),
        )
    }
}
