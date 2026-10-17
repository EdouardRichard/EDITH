package edith.phone.a11yprobe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * **click-now**: the unattended runner that clicks whichever of *these* labels is on screen.
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
 * the logged list is bounded and de-duplicated by [boundedLabels].
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

        private const val DEFAULT_WAIT_MILLIS = 15_000L

        /** Short enough that a dialog appearing mid-budget is clicked promptly, long enough not to spin. */
        private const val POLL_MILLIS = 150L
    }
}
