package edith.phone.a11yprobe

/**
 * The stop condition of the scroll loop, as a value rather than an inline `break` (A1, scroll scenario).
 *
 * Reason to exist: on the device these outcomes are a single `scrollOk=0/1`, and a zero that does not say *why* costs a
 * whole session to re-diagnose. Each branch implies a different fix, so each is a distinct string:
 *
 *  - `arrived` — the target is visible and the loop may click it. Not a stop-the-run failure.
 *  - `no_progress` — a scroll was dispatched and the visible text did not change, so the list is at its end (or is not
 *    scrollable at all). At most one retry is allowed, because a single unchanged frame can be a settled animation.
 *  - `budget` — [maxScrolls] reached while the list was still moving. The probe gave up; the target may still exist.
 *  - `scroll` — keep going.
 */
enum class ScrollDecision { ARRIVED, SCROLL, NO_PROGRESS, BUDGET }

/**
 * [fingerprinted] is whether the last dispatched scroll changed the visible text, i.e. whether the list still moved.
 *
 * A true `targetVisible` wins over every stop reason: a target that came into view on the last allowed scroll *was*
 * reached, and reporting `budget` there would turn a success into a failure.
 */
fun scrollDecision(targetVisible: Boolean, fingerprinted: Boolean, scrollsDone: Int, maxScrolls: Int): String = when {
    targetVisible -> "arrived"
    !fingerprinted -> "no_progress"
    scrollsDone >= maxScrolls -> "budget"
    else -> "scroll"
}

/**
 * The first candidate label that is present on the screen **exactly**, or null (A1).
 *
 * Exact, not substring: "WLAN" is a substring of "WLAN 助理", a *different* Settings row, so a substring match would let
 * the probe claim it reached Wi-Fi after clicking something else — the same class of error as T3b's "the screen changed,
 * therefore success". Candidate order is the priority decision (Chinese before English on a MIUI build), so the first
 * candidate that matches wins rather than whichever node the walk happened to see first.
 */
fun firstCandidateIn(candidates: List<String>, onScreen: List<String>): String? {
    val present = onScreen.filter { it.isNotBlank() }.toSet()
    // Confirmation cards (notably the owner's allow button) append a live countdown: “允许 (91)”, “允许（9）”.
    // Match that exact suffix only; this must never become a general substring rule (安装 vs 继续安装).
    val withoutCountdown = present.map { COUNTDOWN_SUFFIX.replace(it, "") }.toSet()
    return candidates.firstOrNull { it in present || it in withoutCountdown }
}

private val COUNTDOWN_SUFFIX = Regex("""\s*[（(]\d+[)）]\s*$""")

/**
 * Which mechanism dispatched one tap (A1, mis-tap rate).
 *
 * This is the axis the mis-tap numerator is measured on, because the two mechanisms *cannot* mis-aim in the same way:
 *
 *  - [NODE] — `performAction(ACTION_CLICK)` on an `AccessibilityNodeInfo`. **Physically unable to "land wrong"**: the
 *    framework delivers the click to that very node, and there is no coordinate in the request that could drift. There
 *    is therefore no such event as "the node click hit another row"; a node click is not a sample of the mis-tap rate
 *    at all.
 *  - [COORDINATE] — `dispatchGesture` at the centre of the node's bounds. The point comes from a *measurement of the
 *    layout*, and the layout can move between the measurement and the injection (a settling scroll, an animation, a row
 *    expanding): a point has no identity, so it can land on a different row. **The mis-tap numerator must be measured
 *    here**, or it is measuring a failure mode that the mechanism it measures cannot produce — which is how the first
 *    K40 run reported `misTaps=2` for two node clicks that both reached the intended screen.
 */
enum class TapMode { NODE, COORDINATE }

/**
 * A tap is a mis-tap when the **coordinate** it was injected at did not land on the intended row (A1, mis-tap rate).
 *
 * The two mechanisms are judged differently and the asymmetry is the point of [TapMode]:
 *
 *  - [TapMode.NODE] is `false` **by construction**. A node click is delivered by the framework to the node it names, so
 *    it cannot arrive somewhere else; there is no landing to judge. The old rule compared the *label* of the clicked
 *    node with the intended label, and because a clickable row container usually carries no text of its own, its label
 *    degenerates to the class name and can never equal the intended label. That is a defect in the *measurement*, not a
 *    mis-tap. **The evidence for the mis-tap rate therefore comes from the coordinate path**, which is also where the
 *    failure is physically possible.
 *  - [TapMode.COORDINATE] is `!hitIntended`, and `false` covers both "landed on another node" and "landed on nothing at
 *    all": a tap into empty space has to stay in the numerator, or the worst case silently disappears from it. A gesture
 *    the platform refused (never injected) reads the same way and stays visible as its own `click_refused` failure class
 *    rather than being argued out of the numerator here.
 */
fun isMisTap(record: TapRecord): Boolean = when (record.mode) {
    TapMode.NODE -> false
    TapMode.COORDINATE -> !record.hitIntended
}

/**
 * Whether a coordinate tap landed on the intended node, one of its ancestors, or one of its descendants (A1).
 *
 * Relatedness is a **tree-position** fact, and the position is the child-index path from the window root, so the test is
 * a prefix relation between two paths and needs no node-identity API. Labels are deliberately *not* part of the
 * comparison: the node read back at the tap point on the K40 is the row *container* — an unlabelled `LinearLayout`
 * whose label is its class name — so comparing labels would call a correct tap a miss. The opposite case is pinned too:
 * two rows can carry the same text, and a tap on the *other* one is still a mis-tap, which label equality would miss.
 *
 * `null` on either side is a miss: no intended node means there was nothing to aim at, and nothing under the point means
 * the coordinate landed on no node at all.
 */
fun isHitIntended(intendedPath: List<Int>?, hitPath: List<Int>?): Boolean {
    if (intendedPath == null || hitPath == null) return false
    return intendedPath == hitPath || isPrefixOf(intendedPath, hitPath) || isPrefixOf(hitPath, intendedPath)
}

/** Whether [prefix] names a node that strictly contains the node named by [path] (an ancestor of it). */
private fun isPrefixOf(prefix: List<Int>, path: List<Int>): Boolean =
    path.size > prefix.size && path.subList(0, prefix.size) == prefix

/**
 * One tap attempt and what the *service* measured about it, so the mis-tap rate is a measurement rather than a guess.
 *
 * [describedAs] is the label of the node the tap was **dispatched against** — for [TapMode.NODE] the clickable ancestor
 * that owns the action, for [TapMode.COORDINATE] the intended row whose centre the point came from. On the coordinate
 * path the label of the node *read back at the tap point* is the evidence for [hitIntended]; the device logs it as
 * `hitLabel=` rather than storing it here, because nothing in this arithmetic needs it.
 *
 * [hitIntended] is only meaningful on the coordinate path (see [isMisTap]); node clicks leave it at its default because
 * the question cannot arise for them. A record that names no [mode] is a node click, which is what the pre-coordinate
 * construction shape means.
 */
data class TapRecord(
    val intended: String,
    val describedAs: String?,
    val arrived: Boolean,
    val markerSeen: Boolean,
    val failure: String?,
    val mode: TapMode = TapMode.NODE,
    val hitIntended: Boolean = false,
)

/** One popup attempt. [outcome] is a [popupVerdict] string; `skipped` stays out of the denominator. */
data class PopupRecord(val outcome: String, val affirmativeClicked: Boolean)

/**
 * The verdict's numbers.
 *
 * Deno-minators are explicit and always printed (`attempts`, `scrollRuns`, `coordRuns`, `popupRuns`) because the §12#5
 * defect was a *rate without a denominator*: `misTaps=1` is unreadable until `attempts=` says one out of how many.
 *
 * `scrollOk` and `intentMet` are separate on purpose. `scrollOk` = the list scrolled and the target became visible;
 * `intentMet` = the destination screen carried the intent marker. A run can scroll perfectly into the wrong screen, and
 * that case must be visible as `scrollOk=1 intentMet=0` rather than folded into one number.
 *
 * `coordRuns`/`coordOk` are a *subset* of the scroll rounds — the same tap performed by coordinate instead of by node —
 * and they are the denominator of the mis-tap numerator. Both default to `0` so a run that dispatched no coordinate tap
 * prints an explicit `coordOk=0/0` instead of omitting the field.
 */
data class A11yIntentCounters(
    val attempts: Int,
    val misTaps: Int,
    val intentMet: Int,
    val scrollRuns: Int,
    val scrollOk: Int,
    val coordRuns: Int = 0,
    val coordOk: Int = 0,
    val popupRuns: Int,
    val popupOk: Int,
    val popupSkipped: Int,
    val failures: Map<String, Int>,
)

/**
 * Fold the device's records into the verdict's numbers.
 *
 * `attempts` sums the scroll taps and the not-skipped popup attempts, because the acceptance question is "how often did
 * the probe touch the wrong thing" and not "how often per sub-scenario". A `skipped` popup (permission already
 * GRANTED) is excluded from `popupRuns`: it measured nothing, and admitting it would report a success the probe never
 * produced.
 */
fun countersOf(taps: List<TapRecord>, popups: List<PopupRecord>): A11yIntentCounters {
    val popupAttempts = popups.filter { it.outcome != "skipped" }
    return A11yIntentCounters(
        attempts = taps.size + popupAttempts.size,
        misTaps = taps.count { isMisTap(it) },
        intentMet = taps.count { it.markerSeen },
        scrollRuns = taps.size,
        scrollOk = taps.count { it.arrived },
        coordRuns = taps.count { it.mode == TapMode.COORDINATE },
        coordOk = taps.count { it.mode == TapMode.COORDINATE && it.hitIntended },
        popupRuns = popupAttempts.size,
        popupOk = popupAttempts.count { it.outcome == "granted" },
        popupSkipped = popups.size - popupAttempts.size,
        failures = (taps.mapNotNull { it.failure } + popups.filter { it.outcome != "skipped" }.map { it.outcome })
            .groupingBy { it }
            .eachCount(),
    )
}

/**
 * The verdict of the runtime-permission popup, judged on **device state** and never on pixels.
 *
 * `granted` outranks everything: `checkSelfPermission` returning GRANTED is the acceptance criterion, and a dialog that
 * merely vanished is what T3b would have accepted — the exact signal this mode was written to replace.
 *
 * The four failures are kept apart because each needs a different fix:
 *  - `no_dialog` — the request never produced a system dialog (request already in flight, or the permission is
 *    policy-fixed on this build).
 *  - `no_affirmative` — a dialog was on screen but no known affirmative label was found: a label-list fix, not a click
 *    fix.
 *  - `denied` — the affirmative control was clicked and the permission stayed denied: the click landed elsewhere.
 *  - `skipped` is reported by the caller (not here) when the permission was already GRANTED before the request, so this
 *    run measured nothing.
 */
fun popupVerdict(permissionState: String, dialogSeen: Boolean, affirmativeClicked: Boolean): String = when {
    permissionState == "granted" -> "granted"
    !dialogSeen -> "no_dialog"
    !affirmativeClicked -> "no_affirmative"
    else -> "denied"
}

/**
 * The one line the Lead greps out of logcat.
 *
 * Every number is printed even when zero, and `failures=` is printed even when empty: a field that disappears on a
 * clean run makes a clean run and a truncated log indistinguishable. Failure classes are sorted so two runs of the same
 * device produce byte-identical lines that can be diffed.
 *
 * `coordOk` — how many coordinate taps hit the intended row — is appended **last**, after every pre-existing field, so
 * the older evidence can still be quoted by its unchanged prefix `P3_A1_VERDICT attempts=… popupSkipped=…`.
 */
fun verdictLine(counters: A11yIntentCounters): String {
    val failures = counters.failures.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
    return "P3_A1_VERDICT attempts=${counters.attempts} misTaps=${counters.misTaps} " +
        "intentMet=${counters.intentMet} scrollOk=${counters.scrollOk}/${counters.scrollRuns} " +
        "popupOk=${counters.popupOk}/${counters.popupRuns} popupSkipped=${counters.popupSkipped} " +
        "failures=$failures coordOk=${counters.coordOk}/${counters.coordRuns}"
}
