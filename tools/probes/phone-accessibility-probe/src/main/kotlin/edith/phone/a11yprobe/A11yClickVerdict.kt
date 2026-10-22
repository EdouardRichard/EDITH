package edith.phone.a11yprobe

/**
 * How many of the active window's labels one poll tick may put in the log (**click-now**).
 *
 * Bounded so a long scrolling page cannot bury the verdict line, and applied **after** de-duplication in
 * [boundedLabels]: on a screen that repeats one word, spending the budget on copies of it would push out the very label
 * the line exists to reveal. On the MIUI install dialog the button text is the one thing that cannot be read any other
 * way, so this bound is deliberately "distinct labels", not "lines of tree output".
 */
const val MAX_LOGGED_LABELS = 40

/**
 * What may separate two labels in the command-line list (**click-now**).
 *
 * The full-width comma is here for a device fact, not for tidiness: the dialog this mode was written for is Chinese, so
 * the operator types the list on a Chinese IME and gets `，` without noticing. Splitting on the ASCII comma alone would
 * produce one label that matches nothing, and the run would report `no_match` while the command line *looks* correct —
 * the failure mode where the evidence would be blamed instead of the input.
 */
private val LABEL_SEPARATORS = Regex("[,，;；\n]")

/**
 * A label list from the command line, or null when the extra was **not supplied at all**.
 *
 * null and `emptyList()` are deliberately different answers and the difference is not cosmetic: null means "no override,
 * keep the default list", while an explicitly empty list means the operator asked for no candidates. Falling back to the
 * default on an empty override would click 「允许」/「OK」 on a dialog the operator never named — the mis-click this mode
 * exists to prevent — so the empty list is carried through and reported as `no_candidates`.
 */
fun parseLabelList(raw: String?): List<String>? {
    if (raw == null) return null
    return raw.split(LABEL_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/**
 * Whatever `am` actually delivered under an extra key, as a label list; null when the key was absent.
 *
 * `--esal labels a,b` delivers an `ArrayList<String>`, `--esa` a `String[]`, and `--es labels a,b` a single `String`. An
 * operator will use whichever spelling they remember, and `getStringArrayListExtra` returns null (or throws) for the
 * others — the same class of trap as the `--ei`/`--el` one documented on `longExtraOr`, and handled in one place for the
 * same reason. A value of an unexpected type is treated as absent rather than crashing an unattended run.
 */
fun normalizeLabels(raw: Any?): List<String>? = when (raw) {
    null -> null
    is String -> parseLabelList(raw)
    is Collection<*> -> parseLabelList(raw.filterIsInstance<String>().joinToString(","))
    is Array<*> -> parseLabelList(raw.filterIsInstance<String>().joinToString(","))
    else -> null
}

/**
 * The labels a poll tick may log: blank entries dropped, duplicates removed, then bounded to [max].
 *
 * De-duplication happens **before** the bound on purpose. A MIUI dialog often exposes the same short word on many nodes
 * (button text repeated in a title or a description), and a bound applied first would let those copies consume the whole
 * budget while the one unknown label — the reason for looking — fell off the end.
 */
fun boundedLabels(labels: List<String>, max: Int = MAX_LOGGED_LABELS): List<String> =
    labels.filter { it.isNotBlank() }.distinct().take(max)

/**
 * `|`-joined, because the labels themselves arrive as a comma-separated list: joining with a comma would make the
 * logged value unreadable back into the list it came from.
 */
fun joinLabels(labels: List<String>): String = labels.joinToString("|")

/**
 * One poll tick that matched nothing (**click-now**), as the log line an unattended run leaves behind.
 *
 * This line is the deliverable when the run fails: screenshots come back black and `uiautomator dump` returns a null root
 * on the MIUI install dialog, so the window's package name plus the labels already on screen are the only way to learn
 * the button's real wording. It carries the package name as well as the labels because "the dialog never came up" and
 * "we read some other window" produce the same `no_match` verdict and need different fixes.
 */
fun clickPollLine(tick: Int, window: String, labels: List<String>): String =
    "A1CLICK_POLL tick=$tick package=$window visible=${labels.size} labels=${joinLabels(labels)}"

/**
 * What the unattended click run produced. Four outcomes, each implying a different next move:
 *
 *  - `no_candidates` — the label list was empty, so nothing was ever looked for. The command line is the fix; kept apart
 *    from `no_match` because "we searched for nothing" and "we searched and the dialog never showed our wording" send
 *    the next session to different places.
 *  - `no_match` — the whole wait budget elapsed with none of the labels visible: either the dialog never appeared or its
 *    wording is not in the list, and the per-tick `A1CLICK_POLL` lines are what tells those two apart.
 *  - `click_refused` — a label was on screen, its node was found, and the platform refused the click: a capability
 *    question, not a wording one.
 *  - `clicked` — the click was dispatched. This is *dispatched*, never "the install proceeded": the run cannot see the
 *    installer's own state, and claiming more than the click would be the T3b "the screen changed, therefore success"
 *    mistake in a new place.
 *
 * `no_candidates` is checked first so an empty list stays an input problem even if a match was somehow reported.
 */
fun clickOutcome(candidates: List<String>, matched: String?, clicked: Boolean): String = when {
    candidates.isEmpty() -> "no_candidates"
    matched == null -> "no_match"
    clicked -> "clicked"
    else -> "click_refused"
}

/**
 * The one line the operator greps for the unattended click run.
 *
 * Every field is printed even when it is empty or zero (`matched=none`, `polls=0`, `visible=0`), for the same reason as
 * [verdictLine]: a field that disappears on a degenerate run makes that run and a truncated log indistinguishable. The
 * prefix is its own (`P3_A1CLICK_VERDICT`, not `P3_A1_VERDICT`) so the existing A1 evidence and its greps keep meaning
 * exactly what they meant.
 */
fun clickVerdictLine(
    candidates: List<String>,
    outcome: String,
    matched: String?,
    clicked: Boolean,
    polls: Int,
    waitMillis: Long,
    window: String,
    visibleCount: Int,
): String = "P3_A1CLICK_VERDICT outcome=$outcome labels=${joinLabels(candidates)} matched=${matched ?: "none"} " +
    "clicked=$clicked polls=$polls waitMillis=$waitMillis window=$window visible=$visibleCount"
