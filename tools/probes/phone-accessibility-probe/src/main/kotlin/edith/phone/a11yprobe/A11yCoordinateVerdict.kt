package edith.phone.a11yprobe

/**
 * One screen size in pixels, as the probe reads it at dispatch time (**coordinate** arm).
 *
 * A separate value from `android.graphics.Rect` on purpose: the bounds validation and the verdict are pure Kotlin, so
 * the decision "is this point a place a touch can land" is pinned by a JVM test instead of only by the device run that
 * needs it.
 */
data class ProbeScreenSize(val width: Int, val height: Int)

/**
 * The active window's bounds in screen pixels, or of whatever rectangle the caller read from the accessibility tree.
 *
 * Inclusive on left/top and exclusive on right/bottom, i.e. the same half-open rule a hit test uses: a point exactly on
 * the far edge is **outside** the rectangle. The coordinate arm uses this to answer one question before it taps - is
 * the point one that can miss the dialog? - and prints the answer, so the outside-tap evidence is reviewable.
 */
data class ProbeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    fun text(): String = "[$left,$top][$right,$bottom]"
}

/**
 * A coordinate from `am`, whatever spelling delivered it, or null when the extra was absent/unusable.
 *
 * `--ei x 540` delivers an `Int`, `--el` a `Long` and `--es` a `String`; an operator uses whichever they remember. An
 * unusable value must not become 0 - (0, 0) is the top-left pixel, a real point that a silent default would tap.
 */
fun intExtraOrNull(raw: Any?): Int? = when (raw) {
    is Int -> raw
    is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else null
    is String -> raw.trim().toIntOrNull()
    else -> null
}

/** Whether (x, y) is a pixel a touch can land on. A screen size that could not be read contains no point. */
fun pointOnScreen(x: Int, y: Int, screen: ProbeScreenSize): Boolean =
    screen.width > 0 && screen.height > 0 && x >= 0 && y >= 0 && x < screen.width && y < screen.height

/**
 * What one coordinate click produced, decided in the order the run can fail:
 *
 *  - `service_not_enabled` — no accessibility service to dispatch through; the run cannot even attempt the tap.
 *  - `missing_coordinates` — one or both `x`/`y` extras absent or unusable: a command-line error, not a failed tap.
 *  - `out_of_screen` — the point is not a pixel of the screen read at dispatch time (negative or past the edge).
 *  - `no_dialog` — the caller named a marker label and it never appeared within the wait budget, so the bounds that
 *    should bound the tap are unknown and the outside-tap premise cannot be established.
 *  - `outside_requires_marker` — the caller demanded a point outside the marker window but named no marker, so there
 *    are no bounds to compare against. Refused rather than dispatched: a completed gesture would otherwise be written
 *    up as an outside tap whose "outside" was never established, and could have landed inside the dialog.
 *  - `inside_dialog_bounds` — the caller asked for a point **outside** the active window and this one is inside it: the
 *    gesture would not test the outside case, so it is refused rather than dispatched and mis-read as evidence.
 *  - `click_refused` — the platform refused to inject the gesture (`dispatchGesture` returned false).
 *  - `gesture_not_completed` — the platform accepted the gesture but its completion callback never arrived. Kept apart
 *    from `clicked` because the probe cannot see whether it landed; reporting it as a click would overstate the run.
 *  - `clicked` — the gesture was accepted and completed. This is *dispatched and completed*, never "the screen changed"
 *    and never "the dialog ignored it" — the caller reads the dialog state back separately.
 */
fun coordinateOutcome(
    x: Int?,
    y: Int?,
    screen: ProbeScreenSize,
    markerRequired: Boolean,
    dialogBounds: ProbeBounds?,
    requireOutside: Boolean,
    dispatched: Boolean,
    completed: Boolean,
    serviceEnabled: Boolean = true,
): String = when {
    !serviceEnabled -> "service_not_enabled"
    x == null || y == null -> "missing_coordinates"
    !pointOnScreen(x, y, screen) -> "out_of_screen"
    markerRequired && dialogBounds == null -> "no_dialog"
    requireOutside && dialogBounds == null -> "outside_requires_marker"
    requireOutside && dialogBounds != null && dialogBounds.contains(x, y) -> "inside_dialog_bounds"
    !dispatched -> "click_refused"
    !completed -> "gesture_not_completed"
    else -> "clicked"
}

/**
 * The one line the operator greps for the coordinate arm.
 *
 * Every field is printed even when it is unknown (`x=none`, `dialogBounds=none`, `outsideDialog=none`), for the same
 * reason as the click-now verdict: a field that disappears on a degenerate run makes that run and a truncated log
 * indistinguishable. `screenW`/`screenH` are the bounds the point was validated against, and `dialogBounds` is what
 * the accessibility tree said the active window was **at dispatch time** - the two facts that make "this point was
 * outside the dialog" reviewable instead of asserted.
 */
fun coordinateVerdictLine(
    x: Int?,
    y: Int?,
    screen: ProbeScreenSize,
    markerRequired: Boolean,
    dialogBounds: ProbeBounds?,
    requireOutside: Boolean,
    dispatched: Boolean,
    completed: Boolean,
    serviceEnabled: Boolean = true,
): String {
    val inScreen = x != null && y != null && pointOnScreen(x, y, screen)
    val outside = if (dialogBounds != null && x != null && y != null) !dialogBounds.contains(x, y) else null
    val outcome = coordinateOutcome(
        x = x,
        y = y,
        screen = screen,
        markerRequired = markerRequired,
        dialogBounds = dialogBounds,
        requireOutside = requireOutside,
        dispatched = dispatched,
        completed = completed,
        serviceEnabled = serviceEnabled,
    )
    return "A1CLICK_AT x=${x ?: "none"} y=${y ?: "none"} screenW=${screen.width} screenH=${screen.height} " +
        "inScreen=$inScreen dialogBounds=${dialogBounds?.text() ?: "none"} " +
        "outsideDialog=${outside ?: "none"} dispatched=$dispatched completed=$completed outcome=$outcome"
}
