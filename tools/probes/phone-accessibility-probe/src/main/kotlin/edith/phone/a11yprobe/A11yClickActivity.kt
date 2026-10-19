package edith.phone.a11yprobe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * **click-now**: the unattended runner that clicks a named label or an explicit screen coordinate.
 *
 * The label arm (no `x`/`y` extras) clicks whichever of *these* labels is on screen; its behaviour is unchanged. The
 * coordinate arm (either `x` or `y` present) waits for a caller-named marker window, reads its bounds from the
 * accessibility tree, and dispatches one `dispatchGesture` touch at the point — the only way to test "a tap outside the
 * confirmation dialog" without `adb shell input tap`, which is a different input path and is forbidden by the project.
 * Both arms write one greppable verdict (`P3_A1CLICK_VERDICT` / `A1CLICK_AT`) and are protected by the same
 * `confirm=true` extra.
 *
 * Why it exists is a device fact, not a preference: on the MIUI installer's 「是否继续安装」 confirmation, screenshots
 * come back black and `uiautomator dump` returns a null root, so the accessibility tree is the only channel that can see
 * the button — and its wording cannot be known in advance, which is what the per-tick evidence in [runClick] is for. A1's
 * popup arm cannot cover this: its affirmative labels are a constant, it drives this app's *own* permission dialog, and a
 * run that finds nothing there leaves no record of what was on screen instead.
 *
 * **Start this before the dialog appears** (start it, then trigger the install), not after. This activity is what takes
 * the foreground when it starts; a dialog that is *already* showing belongs to another activity, and a foreground launch
 * of ours can stop that activity — taking its dialog with it. Started first, this window is merely what the early poll
 * ticks read, the dialog then arrives above it, and the loop keeps polling throughout. The per-tick lines name the
 * package that was actually read, so the mistake shows up as `package=edith.phone.a11yprobe` in the evidence instead of
 * as a bare `no_match`.
 *
 * Deliberately an activity with its own action and a confirmation extra, like A1: an exported component that taps must
 * not run because someone opened the app or a stray intent arrived. It is not a launcher and does not auto-run.
 *
 * Privacy: this activity writes to logcat only. The per-tick evidence is the labels **already on the active screen** plus
 * the name of the package that owns that window — nothing is read from anywhere else, nothing is stored or uploaded, and
 * the logged list is bounded and de-duplicated by [boundedLabels]. The coordinate verdict carries counts, coordinates and
 * window bounds, never the screen's text.
 */
class A11yClickActivity : Activity() {

    private val service: A11yProbeService? get() = A11yProbeService.instance
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 16f
            setPadding(40, 120, 40, 40)
        }
        setContentView(status)

        // Same rule as A1: nothing here may tap because a stray intent reached an exported component.
        if (intent?.getBooleanExtra(EXTRA_CONFIRM, false) != true) {
            status.text = "EDITH P3 CLICK\n\n未确认，不执行。\n\n用 adb 显式传 --ez $EXTRA_CONFIRM true 触发。"
            Log.w(A11yProbeService.TAG, "A1CLICK_NOT_CONFIRMED")
            return
        }

        // An absent `labels` extra is not the same as an empty one — see `parseLabelList`; both are reported honestly.
        val candidates = normalizeLabels(intent.rawExtra(EXTRA_LABELS)) ?: emptyList()
        val waitMillis = intent.longExtraOr(EXTRA_WAIT_MILLIS, DEFAULT_WAIT_MILLIS)

        // A coordinate run is selected by naming a point; a label run is selected by naming labels. Keeping the two
        // modes on one activity means the existing click-now callers (no `x`/`y` extras) are unchanged.
        val rawX = intent.rawExtra(EXTRA_X)
        val rawY = intent.rawExtra(EXTRA_Y)
        if (rawX != null || rawY != null) {
            val x = intExtraOrNull(rawX)
            val y = intExtraOrNull(rawY)
            val marker = intent.getStringExtra(EXTRA_LABEL)?.trim()?.takeIf { it.isNotEmpty() }
            val requireOutside = intent.getBooleanExtra(EXTRA_REQUIRE_OUTSIDE, false)
            status.text = "EDITH P3 CLICK\n\n坐标点击 x=${x ?: "none"} y=${y ?: "none"}\n\n进行中…"
            Thread { runCoordinateClick(x, y, marker, requireOutside, waitMillis) }.start()
            return
        }

        status.text = "EDITH P3 CLICK\n\nlabels=${joinLabels(candidates)}\n\n进行中…"
        Thread { runClick(candidates, waitMillis) }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Poll the **active** window until one of [candidates] is on screen, click it, and log one verdict line.
     *
     * The poll ticks that match nothing are not dead time: each writes the window's package and its visible labels
     * ([clickPollLine]), which is how the real button text gets learned on a device where the dialog cannot be seen any
     * other way. That is why the evidence is emitted per tick rather than only at the end — the dialog's wording is the
     * whole question, and one snapshot at the wrong moment would answer it wrongly.
     *
     * Success here is the **click being dispatched**, never "the install proceeded": this run cannot observe the
     * installer's state, and reporting more than it measured is the T3b "the screen changed, therefore success" mistake.
     */
    private fun runClick(candidates: List<String>, waitMillis: Long) {
        val service = service
        if (service == null) {
            // Enabling an accessibility service is the user's decision and cannot be scripted: report and stop.
            status.text = "EDITH P3 CLICK\n\n无障碍服务未启用。\n\n请在 设置 → 无障碍 中启用 \"EDITH P3 T3b probe\" 后重试。"
            Log.w(A11yProbeService.TAG, "A1CLICK_SERVICE_NOT_ENABLED")
            return
        }

        // Logged before the first poll so an unattended run that dies mid-way still shows which run this was.
        Log.i(A11yProbeService.TAG, "A1CLICK_START labels=${joinLabels(candidates)} waitMillis=$waitMillis")

        var polls = 0
        var matched: String? = null
        var clicked = false
        // Read once even when `candidates` is empty, so a verdict with no polls still names the window that was there.
        var window = service.activePackage()
        var visible = service.visibleLabels()
        val deadline = System.currentTimeMillis() + waitMillis

        while (matched == null && candidates.isNotEmpty() && System.currentTimeMillis() < deadline) {
            polls += 1
            visible = service.visibleLabels()
            window = service.activePackage()
            val candidate = firstCandidateIn(candidates, visible)
            // The *locate* step may fall back to T3b's substring rule only after the exact rule failed: the match decision
            // above is already exact, so widening the search cannot make the probe click a label it did not match — it
            // only covers the case where the node that carries the text was not the one the walk resolved.
            val node = candidate?.let { service.findExactNode(listOf(it)) ?: service.findNode(listOf(it)) }
            if (candidate == null || node == null) {
                Log.i(A11yProbeService.TAG, clickPollLine(polls, window, boundedLabels(visible)))
            } else {
                matched = candidate
                // Climb to the clickable ancestor, exactly as A1 and T3b learned to: the text-bearing node is usually a
                // label whose own `performAction(ACTION_CLICK)` is refused.
                clicked = service.clickNode(service.clickableAncestor(node) ?: node)
                Log.i(
                    A11yProbeService.TAG,
                    "A1CLICK_MATCH tick=$polls label='$candidate' clicked=$clicked package=$window",
                )
            }
            if (matched == null) sleep(POLL_MILLIS)
        }

        val line = clickVerdictLine(
            candidates = candidates,
            outcome = clickOutcome(candidates, matched, clicked),
            matched = matched,
            clicked = clicked,
            polls = polls,
            waitMillis = waitMillis,
            window = window,
            visibleCount = boundedLabels(visible).size,
        )
        // The verdict is the deliverable; it is the one line the unattended run needs to grep.
        Log.i(A11yProbeService.TAG, line)
        handler.post {
            status.text = "EDITH P3 CLICK\n\n$line\n\n结果已写入 logcat。"
        }
    }

    /**
     * **Coordinate arm**: wait until the screen the caller names is in front, read its bounds from the accessibility
     * tree, dispatch one touch at an explicit screen point, and log one verdict.
     *
     * It exists for the one question a label click cannot answer: *does a tap outside the confirmation dialog dismiss
     * it?* That tap has no label to aim at - the point is the input - and it must be a real injected touch on the
     * production window, not `adb shell input tap` (a different input path). The caller starts this **before** the
     * dialog is raised, brings the production app back to the front, and the marker label is what tells this thread the
     * dialog is really up; the bounds read then is the tree's own answer, at dispatch time.
     *
     * Success here is only "the platform accepted and completed the gesture". Whether the dialog stayed or went is read
     * back separately by the caller: a probe that called a completed touch "the dialog ignored it" would be reporting a
     * result it never measured.
     */
    private fun runCoordinateClick(x: Int?, y: Int?, marker: String?, requireOutside: Boolean, waitMillis: Long) {
        val screen = screenSize()
        val service = service
        if (service == null) {
            // Same rule as the label arm: enabling an accessibility service is the user's decision and cannot be scripted.
            emitCoordinateVerdict(
                x = x, y = y, screen = screen, markerRequired = marker != null, dialogBounds = null,
                requireOutside = requireOutside, dispatched = false, completed = false, serviceEnabled = false,
            )
            return
        }

        val deadline = System.currentTimeMillis() + waitMillis
        var bounds: ProbeBounds? = null
        if (marker != null) {
            // The marker is the caller's assertion that the target window is up. Until it is, the bounds read would
            // describe whatever was in front instead of the window the point is meant to miss.
            while (System.currentTimeMillis() < deadline) {
                if (service.isShown(listOf(marker))) {
                    bounds = service.activeRootBounds()?.let { ProbeBounds(it.left, it.top, it.right, it.bottom) }
                    if (bounds != null) break
                }
                sleep(POLL_MILLIS)
            }
        } else {
            // No marker: still wait for the caller to put the target in front. Dispatching while our own translucent
            // activity is the active window would send the touch at the wrong window and measure nothing.
            while (System.currentTimeMillis() < deadline && service.activePackage() == packageName) {
                sleep(POLL_MILLIS)
            }
        }

        // Input validation runs before the gesture: only "the point is valid and may be dispatched" reaches the tap.
        // Asking the same pure decision twice (once with a hypothetical successful gesture, once with the real one)
        // keeps the verdict and the dispatch from ever disagreeing about what was refused.
        val inputsValid = coordinateOutcome(
            x = x, y = y, screen = screen, markerRequired = marker != null, dialogBounds = bounds,
            requireOutside = requireOutside, dispatched = true, completed = true,
        ) == "clicked"
        var dispatched = false
        var completed = false
        if (inputsValid) {
            val tap = service.tapAt(x!!, y!!)
            dispatched = tap.accepted
            completed = tap.completed
        }
        emitCoordinateVerdict(
            x = x, y = y, screen = screen, markerRequired = marker != null, dialogBounds = bounds,
            requireOutside = requireOutside, dispatched = dispatched, completed = completed, serviceEnabled = true,
        )
    }

    /** The screen the point is validated against, read from this window's metrics (full display on the target K40). */
    private fun screenSize(): ProbeScreenSize {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()?.let {
                return ProbeScreenSize(it.width(), it.height())
            }
        }
        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        return ProbeScreenSize(metrics.widthPixels, metrics.heightPixels)
    }

    /** The coordinate arm's deliverable: one greppable line, then this activity gets out of the caller's way. */
    private fun emitCoordinateVerdict(
        x: Int?,
        y: Int?,
        screen: ProbeScreenSize,
        markerRequired: Boolean,
        dialogBounds: ProbeBounds?,
        requireOutside: Boolean,
        dispatched: Boolean,
        completed: Boolean,
        serviceEnabled: Boolean,
    ) {
        val line = coordinateVerdictLine(
            x = x, y = y, screen = screen, markerRequired = markerRequired, dialogBounds = dialogBounds,
            requireOutside = requireOutside, dispatched = dispatched, completed = completed,
            serviceEnabled = serviceEnabled,
        )
        Log.i(A11yProbeService.TAG, line)
        handler.post {
            status.text = "EDITH P3 CLICK\n\n$line\n\n结果已写入 logcat。"
            handler.postDelayed({ finish() }, COORDINATE_FINISH_DELAY_MILLIS)
        }
    }

    private fun sleep(millis: Long) = runCatching { Thread.sleep(millis) }

    companion object {
        /** The action the operator's `am start` targets. Its own action keeps this mode opt-in and greppable. */
        const val ACTION = "edith.phone.a11yprobe.intent.CLICK_NOW"

        const val EXTRA_CONFIRM = "confirm"

        /** The labels to click, comma-separated: `--esal labels "继续安装,安装"`. */
        const val EXTRA_LABELS = "labels"

        /**
         * How long to keep polling for one of [EXTRA_LABELS] (milliseconds): `--el waitMillis 15000`.
         *
         * The default is deliberately longer than A1's dialog wait because this mode is meant to be started **before**
         * the dialog exists: the operator launches it, then triggers the install, and the budget has to cover that gap.
         * Read through `longExtraOr` so `--ei` (which delivers an `Int`) works as well as `--el`.
         */
        const val EXTRA_WAIT_MILLIS = "waitMillis"

        /**
         * The coordinate arm's point (pixels): `--ei x 540 --ei y 400`.
         *
         * Supplying **either** switches the run to the coordinate arm; supplying neither keeps the label arm exactly
         * as it was. `intExtraOrNull` accepts the Int/Long/String spellings `am` can deliver.
         */
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"

        /**
         * The label the caller expects to see before the tap (the confirmation dialog's title, for the outside-tap
         * check). It is a **marker**, not a target: the point is the target, and the marker's window bounds are what
         * the verdict reports and what `requireOutside` is checked against.
         */
        const val EXTRA_LABEL = "label"

        /**
         * Refuse the tap unless the point lies outside the marker window's bounds (`--ez requireOutside true`).
         *
         * Without this, a point that landed inside the dialog would still be dispatched and the run could quote a
         * completed gesture as an outside tap it never made. Refusing leaves an `inside_dialog_bounds` verdict instead.
         *
         * It **requires a marker**: the bounds that make "outside" checkable come from the marker window. Asking for
         * `requireOutside` without a `label` fails closed with `outside_requires_marker` and dispatches nothing - a
         * completed gesture with unknown bounds would be evidence of no outside tap at all.
         */
        const val EXTRA_REQUIRE_OUTSIDE = "requireOutside"

        private const val DEFAULT_WAIT_MILLIS = 15_000L

        /** Short enough that a dialog appearing mid-budget is clicked promptly, long enough not to spin. */
        private const val POLL_MILLIS = 150L

        /** Let the verdict reach logcat/UI before the activity removes itself; the caller's app stays in front. */
        private const val COORDINATE_FINISH_DELAY_MILLIS = 300L
    }
}
