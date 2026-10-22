package edith.phone.a11yprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinate arm's decisions, pinned before the probe fires a touch at a raw screen point.
 *
 * A coordinate click has no label to check itself against — the point *is* the input. The questions an unattended run
 * cannot answer afterwards are therefore decided here: did the command line name both coordinates, is the point inside
 * the screen, is it outside the dialog the run means to miss, and does "dispatched" mean the gesture really completed?
 * Every failure ends as a verdict line, so the line's shape and the decision behind it have to be fixed off-device.
 */
class A11yCoordinateVerdictTest {

    // ------------------------------------------------------------------ what the command line delivered

    /**
     * `am` delivers the same number as `--ei` (Int), `--el` (Long) or `--es` (String), and an operator uses whichever
     * they remember. All three must mean the same point; anything else is "absent", never a silent 0 — a coordinate
     * tap at (0,0) is the top-left corner, not "no input".
     */
    @Test
    fun coordinateExtraAcceptsEveryAmSpellingAndRejectsAnythingElse() {
        assertEquals(472, intExtraOrNull(472))
        assertEquals(472, intExtraOrNull(472L))
        assertEquals(472, intExtraOrNull("472"))
        assertEquals(472, intExtraOrNull(" 472 "))
        assertNull("a blank string is not a coordinate", intExtraOrNull(" "))
        assertNull("text is not a coordinate", intExtraOrNull("abc"))
        assertNull("a Long that does not fit an Int must not wrap to a wild point", intExtraOrNull(Int.MAX_VALUE + 1L))
        assertNull(intExtraOrNull(null))
        assertNull("a boolean is not a coordinate", intExtraOrNull(true))
    }

    /** The screen read at dispatch time: the point must be somewhere a touch can land, edges exclusive. */
    @Test
    fun pointOnScreenRejectsNegativeAndOutOfRangePoints() {
        val screen = ProbeScreenSize(width = 1080, height = 2400)
        assertTrue(pointOnScreen(0, 0, screen))
        assertTrue(pointOnScreen(1079, 2399, screen))
        assertTrue(pointOnScreen(540, 400, screen))
        assertTrue("the top-left corner is a real point", pointOnScreen(0, 500, screen))
        assertEquals(false, pointOnScreen(-1, 500, screen))
        assertEquals(false, pointOnScreen(500, -1, screen))
        assertEquals("the right edge is one past the last pixel", false, pointOnScreen(1080, 500, screen))
        assertEquals("the bottom edge is one past the last pixel", false, pointOnScreen(500, 2400, screen))
        assertEquals("an unread screen size cannot contain any point", false, pointOnScreen(0, 0, ProbeScreenSize(0, 0)))
    }

    /** Window bounds are inclusive on the left/top and exclusive on the right/bottom, like a hit test. */
    @Test
    fun boundsContainTheirTopLeftCornerAndExcludeTheFarEdges() {
        val dialog = ProbeBounds(left = 100, top = 948, right = 980, bottom = 1487)
        assertTrue(dialog.contains(100, 948))
        assertTrue(dialog.contains(979, 1486))
        assertEquals(false, dialog.contains(99, 1000))
        assertEquals(false, dialog.contains(980, 1000))
        assertEquals(false, dialog.contains(500, 1487))
        assertEquals("[100,948][980,1487]", dialog.text())
    }

    // ------------------------------------------------------------------ the outcome

    /**
     * Every outcome is kept apart because each implies a different next move: a missing coordinate is a command-line
     * fix, an off-screen point is a measurement mistake, a point inside the dialog means the run would not test the
     * outside-tap case at all, a refused dispatch is a capability question, and a dispatched gesture that never
     * completed is *not* a success — the probe cannot see whether it landed.
     */
    @Test
    fun coordinateOutcomeSeparatesEveryWayTheTapCanEnd() {
        val screen = ProbeScreenSize(width = 1080, height = 2400)
        val dialog = ProbeBounds(left = 100, top = 948, right = 980, bottom = 1487)
        fun outcome(
            x: Int? = 540,
            y: Int? = 400,
            markerRequired: Boolean = true,
            bounds: ProbeBounds? = dialog,
            requireOutside: Boolean = true,
            dispatched: Boolean = true,
            completed: Boolean = true,
            serviceEnabled: Boolean = true,
        ) = coordinateOutcome(x, y, screen, markerRequired, bounds, requireOutside, dispatched, completed, serviceEnabled)

        assertEquals("clicked", outcome())
        assertEquals("service_not_enabled", outcome(serviceEnabled = false))
        assertEquals("missing_coordinates", outcome(x = null))
        assertEquals("missing_coordinates", outcome(y = null))
        assertEquals("out_of_screen", outcome(x = -1))
        assertEquals("out_of_screen", outcome(y = 4000))
        assertEquals("no_dialog", outcome(bounds = null))
        assertEquals("inside_dialog_bounds", outcome(y = 1000))
        assertEquals(
            "an inside point may be allowed when the caller is not testing the outside case",
            "clicked",
            outcome(y = 1000, requireOutside = false),
        )
        assertEquals("click_refused", outcome(dispatched = false, completed = false))
        assertEquals("gesture_not_completed", outcome(completed = false))
        assertEquals(
            "without a dialog marker the bounds are unknown but the tap can still be dispatched",
            "clicked",
            outcome(markerRequired = false, bounds = null, requireOutside = false),
        )
    }

    /**
     * Asking for an outside point **without naming the window it must miss** cannot be checked: the bounds are what
     * makes "outside" true. Such a run must fail closed instead of dispatching, or a completed gesture inside the
     * dialog would be quoted as an outside tap the probe never made. The verdict stays distinct from `no_dialog`,
     * which means a marker was named but never appeared, and still prints every field so the refusal is reviewable.
     */
    @Test
    fun requireOutsideWithoutAMarkerFailsClosed() {
        val screen = ProbeScreenSize(width = 1080, height = 2400)
        assertEquals(
            "no bounds to compare against ⇒ refuse, never dispatch",
            "outside_requires_marker",
            coordinateOutcome(
                x = 540,
                y = 400,
                screen = screen,
                markerRequired = false,
                dialogBounds = null,
                requireOutside = true,
                dispatched = true,
                completed = true,
            ),
        )
        assertEquals(
            "A1CLICK_AT x=540 y=400 screenW=1080 screenH=2400 inScreen=true dialogBounds=none " +
                "outsideDialog=none dispatched=false completed=false outcome=outside_requires_marker",
            coordinateVerdictLine(
                x = 540,
                y = 400,
                screen = screen,
                markerRequired = false,
                dialogBounds = null,
                requireOutside = true,
                dispatched = false,
                completed = false,
            ),
        )
    }

    // ------------------------------------------------------------------ the verdict line

    /**
     * The one line the operator greps. Every field is present on the success path — the coordinate, the screen it was
     * validated against, the dialog bounds read from the accessibility tree, whether the point is outside them, and
     * both halves of "the gesture really completed" — because the outside-tap evidence is exactly this line.
     */
    @Test
    fun coordinateVerdictCarriesEveryFieldOnASuccessfulOutsideTap() {
        val screen = ProbeScreenSize(width = 1080, height = 2400)
        val dialog = ProbeBounds(left = 100, top = 948, right = 980, bottom = 1487)
        val line = coordinateVerdictLine(
            x = 540,
            y = 400,
            screen = screen,
            markerRequired = true,
            dialogBounds = dialog,
            requireOutside = true,
            dispatched = true,
            completed = true,
        )
        assertEquals(
            "A1CLICK_AT x=540 y=400 screenW=1080 screenH=2400 inScreen=true dialogBounds=[100,948][980,1487] " +
                "outsideDialog=true dispatched=true completed=true outcome=clicked",
            line,
        )
        assertTrue("the line must be greppable by its own prefix", line.startsWith("A1CLICK_AT "))
    }

    /**
     * A degenerate run still prints every field: `x=none`, an unknown dialog and `outsideDialog=none`. A field that
     * disappeared here would make "no coordinate was supplied" and a truncated log look identical.
     */
    @Test
    fun coordinateVerdictKeepsItsFieldsWhenTheInputIsMissing() {
        val line = coordinateVerdictLine(
            x = null,
            y = 400,
            screen = ProbeScreenSize(width = 1080, height = 2400),
            markerRequired = true,
            dialogBounds = null,
            requireOutside = true,
            dispatched = false,
            completed = false,
        )
        assertEquals(
            "A1CLICK_AT x=none y=400 screenW=1080 screenH=2400 inScreen=false dialogBounds=none " +
                "outsideDialog=none dispatched=false completed=false outcome=missing_coordinates",
            line,
        )
    }

    /**
     * The refusal path for the point that would not test anything: inside the dialog's own bounds the verdict still
     * carries the coordinate and the bounds that rejected it.
     */
    @Test
    fun coordinateVerdictRecordsThePointAndBoundsThatRefusedTheTap() {
        val line = coordinateVerdictLine(
            x = 540,
            y = 1000,
            screen = ProbeScreenSize(width = 1080, height = 2400),
            markerRequired = true,
            dialogBounds = ProbeBounds(left = 100, top = 948, right = 980, bottom = 1487),
            requireOutside = true,
            dispatched = false,
            completed = false,
        )
        assertEquals(
            "A1CLICK_AT x=540 y=1000 screenW=1080 screenH=2400 inScreen=true dialogBounds=[100,948][980,1487] " +
                "outsideDialog=false dispatched=false completed=false outcome=inside_dialog_bounds",
            line,
        )
    }
}
