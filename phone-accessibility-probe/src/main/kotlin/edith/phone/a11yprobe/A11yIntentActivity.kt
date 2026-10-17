package edith.phone.a11yprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.TextView

/**
 * The raw extra value under [name], whatever type `am` decided to deliver; null when the extra is absent.
 *
 * One read for every `am` spelling, because the type delivered is not the type the caller asked for: `--esal` delivers an
 * `ArrayList`, `--esa` a `String[]` and `--es` a single `String`. Callers decide what they can accept ([longExtraOr] for
 * numbers, [normalizeLabels] for label lists) instead of each re-deriving the trap.
 *
 * `Bundle.get(String)` is deprecated as of API 33 in favour of `Bundle.get(String, Class)`, which does **not exist** on
 * this probe's `minSdk = 28`; the untyped read is the only call that works across the supported range, so the warning is
 * suppressed with that reason rather than left in the build output.
 */
@Suppress("DEPRECATION")
internal fun Intent?.rawExtra(name: String): Any? = this?.extras?.get(name)

/**
 * A long extra that accepts what `adb` can actually send.
 *
 * `--el dialogWaitMillis 15000` delivers a `Long`, but the shorter `--ei dialogWaitMillis 15000` delivers an
 * `Integer`, and `Intent.getLongExtra` throws a `ClassCastException` on that — a trap that would only show up on the
 * device. Reading the raw bundle value keeps both spellings usable, and anything unusable falls back to the default
 * rather than crashing the run.
 *
 * Shared with the click-now activity (`A11yClickActivity`) rather than copied: a second copy is how the `--ei` bug comes
 * back, and it is the kind of bug that only appears on the device, mid-run, unattended.
 */
internal fun Intent?.longExtraOr(name: String, fallback: Long): Long =
    when (val value = rawExtra(name)) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull() ?: fallback
        else -> fallback
    }

/**
 * P3.0 **A1**: the acceptance runner that judges *intent reached*, not *screen changed*.
 *
 * T3b answered "did the screen change"; §12#5 records that this is not the question the acceptance needs, and that no
 * mis-tap rate was ever measured. A1 runs three measurements and prints one line:
 *
 *  1. **scroll** — a Settings entry starts below the fold, the probe scrolls it into view with
 *     `AccessibilityService`'s own `ACTION_SCROLL_FORWARD`, taps it, and then asserts the destination screen carries a
 *     **marker label only that screen has**. Success is the marker, not the change. The tap is performed **twice per
 *     round shape**: once as a *node* click (`performAction(ACTION_CLICK)`) and once as a *coordinate* tap
 *     (`dispatchGesture` at the centre of the row's bounds), because the two mechanisms cannot fail in the same way —
 *     see the mis-tap note below.
 *  2. **popup** — the probe app itself requests a runtime permission and the accessibility service finds and clicks the
 *     affirmative control on the *system* dialog. Success is `checkSelfPermission` returning GRANTED — device state,
 *     never pixels. An already-GRANTED permission is skipped and reported as `popupSkipped`, because a run that measured
 *     nothing must not be quotable as a success. How long the probe waits for that dialog is overridable
 *     (`--ei dialogWaitMillis 15000`), because "the dialog never appeared" and "the probe stopped waiting too early"
 *     look identical in the verdict and only a longer wait separates them. Which labels count as affirmative is
 *     overridable too (`--esal affirmativeLabels "继续安装,安装,确定"`), because that wording belongs to the build being
 *     driven and not to this code; absent, the constant default applies unchanged.
 *  3. **mis-tap rate** — `attempts` is every tap/click dispatched; `misTaps` counts, out of the **coordinate** taps, the
 *     ones that did not land on the intended row (a tap into empty space included). A node click is delivered by the
 *     framework to the node it names, so it cannot mis-land and is never counted: the numerator's evidence comes from
 *     the coordinate path, which is the only path where a point can drift with the layout. Each record logs the label of
 *     the node it hit, so the numerator can be reviewed line by line rather than trusted.
 *
 * Deliberately a **separate activity with its own action and a confirmation extra**: nothing here may run because
 * someone opened the app, and the original T3b launcher activity is left behaviourally untouched so its 20/20 evidence
 * stays valid.
 *
 * Privacy: this activity writes to logcat only. It stores nothing, uploads nothing, reads no notification and no
 * transcript content, and logs no user content — only the labels it was already searching for, counts, class names and
 * the verdict.
 */
class A11yIntentActivity : Activity() {

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

        // Nothing runs without an explicit confirmation: a stray `am start` on this component must not tap through a
        // permission dialog on a device someone is holding. Same reasoning as T3b's "the launcher does not auto-run".
        if (intent?.getBooleanExtra(EXTRA_CONFIRM, false) != true) {
            status.text = "EDITH P3 A1\n\n未确认，不执行。\n\n用 adb 显式传 --ez $EXTRA_CONFIRM true 触发。"
            Log.w(A11yProbeService.TAG, "A1_NOT_CONFIRMED")
            return
        }

        val scenario = intent?.getStringExtra(EXTRA_SCENARIO) ?: SCENARIO_ALL
        val configuration = Config.from(intent)
        status.text = "EDITH P3 A1\n\nscenario=$scenario\n\n进行中…"
        Thread { runVerification(scenario, configuration) }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Everything the run needs, resolved once from the intent so the device code below reads as one straight line. */
    private data class Config(
        val targetLabels: List<String>,
        val markerLabels: List<String>,
        val affirmativeLabels: List<String>,
        val permission: String,
        val maxScrolls: Int,
        val dialogWaitMillis: Long,
    ) {
        companion object {
            /**
             * The default target is a **deep** Settings entry, not the shallow one T3b used: the whole point of this
             * scenario is that the entry starts below the fold, which the shallow entry does not guarantee. Overridable
             * with `--esal targetLabels`, because "which entry is below the fold" is a property of the device, not of
             * this code.
             */
            fun from(intent: Intent?): Config = Config(
                targetLabels = intent?.getStringArrayListExtra(EXTRA_TARGET_LABELS) ?: TARGET_LABELS,
                markerLabels = intent?.getStringArrayListExtra(EXTRA_MARKER_LABELS) ?: MARKER_LABELS,
                // Absent extra keeps the constant default, so every earlier popup run means exactly what it meant.
                affirmativeLabels = normalizeLabels(intent.rawExtra(EXTRA_AFFIRMATIVE_LABELS)) ?: AFFIRMATIVE_LABELS,
                permission = intent?.getStringExtra(EXTRA_PERMISSION) ?: Manifest.permission.READ_CALENDAR,
                maxScrolls = intent?.getIntExtra(EXTRA_MAX_SCROLLS, MAX_SCROLLS) ?: MAX_SCROLLS,
                dialogWaitMillis = intent.longExtraOr(EXTRA_DIALOG_WAIT_MILLIS, DIALOG_WAIT_MILLIS),
            )
        }
    }

    private fun runVerification(scenario: String, configuration: Config) {
        val service = service
        if (service == null) {
            // Enabling an accessibility service is the user's decision and cannot be scripted: report and stop.
            status.text = "EDITH P3 A1\n\n无障碍服务未启用。\n\n请在 设置 → 无障碍 中启用 \"EDITH P3 T3b probe\" 后重试。"
            Log.w(A11yProbeService.TAG, "A1_SERVICE_NOT_ENABLED")
            return
        }

        val taps = mutableListOf<TapRecord>()
        val popups = mutableListOf<PopupRecord>()
        if (scenario == SCENARIO_ALL || scenario == SCENARIO_SCROLL) taps += runScrollRounds(service, configuration)
        if (scenario == SCENARIO_ALL || scenario == SCENARIO_POPUP) popups += runPopupRounds(service, configuration)

        val counters = countersOf(taps, popups)
        val line = verdictLine(counters)
        // The verdict is the deliverable; it is the one line the acceptance needs to grep.
        Log.i(A11yProbeService.TAG, line)
        handler.post {
            status.text = "EDITH P3 A1\n\n$line\n\n结果已写入 logcat。"
        }
    }

    // ------------------------------------------------------------------ scenario 1: scroll

    /**
     * The two arms of the scroll scenario, as rounds of one recorded tap each.
     *
     * Node rounds first (the semantic click, unchanged from the original A1), then the *same* tap by coordinate: the arms
     * are the same task performed two ways, and the verdict's mis-tap numerator only exists on the second one. Every
     * round re-opens Settings from scratch, because a second round that started on the previous destination would be
     * measuring a different task than the first.
     */
    private fun runScrollRounds(service: A11yProbeService, configuration: Config): List<TapRecord> {
        val records = mutableListOf<TapRecord>()
        repeat(SCROLL_ROUNDS) { round ->
            records += runScrollRound(service, configuration, TapMode.NODE, round + 1)
        }
        repeat(COORDINATE_ROUNDS) { round ->
            records += runScrollRound(service, configuration, TapMode.COORDINATE, SCROLL_ROUNDS + round + 1)
        }
        return records
    }

    /**
     * One round: open Settings, scroll the target into view, tap it by [mode], then judge the *destination* by its
     * marker.
     *
     * The log line carries both labels on purpose, because the mis-tap numerator must be reviewable from the evidence:
     *  - `landedOn` — the label of the node the tap was **aimed at**: the clickable ancestor that owns the action (node
     *    mode) or the intended row whose centre the point came from (coordinate mode).
     *  - `hitLabel` — the label of the node the tap is judged to have **hit**: for a node click the framework delivers
     *    the click to the node it names, so it is the same node; for a coordinate tap it is the node read back at the
     *    tap point. That read-back is usually the row *container*, i.e. a class name — that is the evidence, not a
     *    defect, and it is exactly why the criterion is relatedness rather than label equality.
     */
    private fun runScrollRound(
        service: A11yProbeService,
        configuration: Config,
        mode: TapMode,
        round: Int,
    ): TapRecord {
        openSettings()
        sleep(SETTLE_MILLIS)

        val found = scrollUntilVisible(service, configuration)
        val intended = found?.let { service.labelOf(it.node) ?: configuration.targetLabels.first() }
            ?: configuration.targetLabels.first()

        val outcome = if (found == null) {
            // "Never became visible" is a scroll failure, and the verdict records it as `arrived=false` rather than as a
            // mis-tap on a node nobody tapped.
            RoundOutcome(
                TapRecord(
                    intended = intended,
                    describedAs = null,
                    arrived = false,
                    markerSeen = false,
                    failure = "scroll_no_progress",
                    mode = mode,
                    hitIntended = false,
                ),
                hitLabel = null,
            )
        } else when (mode) {
            TapMode.NODE -> nodeTap(service, configuration, found, intended)
            TapMode.COORDINATE -> coordinateTap(service, configuration, found, intended)
        }

        Log.i(
            A11yProbeService.TAG,
            "A1_SCROLL round=$round intended='${outcome.record.intended}' landedOn='${outcome.record.describedAs}' " +
                "markerSeen=${outcome.record.markerSeen} failure=${outcome.record.failure} " +
                "package=${service.activePackage()} mode=${outcome.record.mode} hitLabel='${outcome.hitLabel}'",
        )
        // Back to the Settings root for the next round, and to the operator's previous screen at the end.
        service.goBack()
        sleep(SETTLE_MILLIS)
        return outcome.record
    }

    /**
     * The node arm: climb to the clickable ancestor that owns the action and click it.
     *
     * `hitIntended` stays false and is never read: `isMisTap` is false for [TapMode.NODE] by construction, because the
     * framework delivers the click to this very node — there is no landing that could differ from the aim.
     */
    private fun nodeTap(
        service: A11yProbeService,
        configuration: Config,
        found: NodeInTree,
        intended: String,
    ): RoundOutcome {
        val clickable = service.clickableAncestor(found.node) ?: found.node
        val performed = service.clickNode(clickable)
        sleep(SETTLE_MILLIS)
        val markerSeen = service.isShown(configuration.markerLabels)
        val aimedAt = service.labelOf(clickable)
        return RoundOutcome(
            TapRecord(
                intended = intended,
                describedAs = aimedAt,
                arrived = true,
                markerSeen = markerSeen,
                failure = if (markerSeen) null else if (performed) "marker_missing" else "click_refused",
                mode = TapMode.NODE,
                hitIntended = false,
            ),
            hitLabel = aimedAt,
        )
    }

    /**
     * The coordinate arm: dispatch the tap at the centre of the intended row's bounds and read back the node at that
     * point.
     *
     * This is the only arm where a mis-tap is physically possible. The point is a *measurement* of the layout taken
     * before the tap, so a settling scroll or an animation between the two can put another row under it; a node click
     * has no such gap. `hitIntended` is the read-back node's relatedness to the intended row (itself, an ancestor, or a
     * descendant); nothing under the point is a miss and stays in the numerator.
     */
    private fun coordinateTap(
        service: A11yProbeService,
        configuration: Config,
        found: NodeInTree,
        intended: String,
    ): RoundOutcome {
        val bounds = service.boundsOf(found.node)
        val tap = service.tapCenterAndReadPoint(bounds)
        sleep(SETTLE_MILLIS)
        val markerSeen = service.isShown(configuration.markerLabels)
        val hitIntended = isHitIntended(found.path, tap.hit?.path)
        val hitLabel = service.labelOf(tap.hit?.node)
        // The paths are logged next to the verdict because they are what makes the hit readable on a device whose list
        // may expose fewer nodes than the eye sees: a read that stopped at a shared container is an *ancestor* of the
        // intended row and therefore counts as a hit, which is only visible here.
        Log.i(
            A11yProbeService.TAG,
            "A1_SCROLL_POINT x=${bounds.centerX()} y=${bounds.centerY()} dispatched=${tap.dispatched} " +
                "hitIntended=$hitIntended intendedPath=${found.path} hitPath=${tap.hit?.path} " +
                "hitLabel='$hitLabel' package=${service.activePackage()}",
        )
        return RoundOutcome(
            TapRecord(
                intended = intended,
                describedAs = service.labelOf(found.node),
                arrived = true,
                markerSeen = markerSeen,
                failure = if (markerSeen) null else if (tap.dispatched) "marker_missing" else "click_refused",
                mode = TapMode.COORDINATE,
                hitIntended = hitIntended,
            ),
            hitLabel = hitLabel,
        )
    }

    /** One round's record plus the label the tap is judged to have hit, which only the log line needs. */
    private data class RoundOutcome(val record: TapRecord, val hitLabel: String?)

    /**
     * Scroll the list until a target label is on screen; return the node that carries it, or null if the list ended or
     * the budget ran out.
     *
     * The stop conditions themselves are unit-tested in `scrollDecision`; this method only feeds them device facts:
     *  - `targetVisible` — the platform's own visibility, via `isShown`,
     *  - `fingerprinted` — whether the *last* dispatched scroll changed the visible text. A list that stopped moving has
     *    ended, and continuing would burn the budget and then report "the target does not exist" when the truth is "the
     *    list ended".
     *
     * The node handed back is re-resolved from the candidate list rather than reused across scrolls: an
     * `AccessibilityNodeInfo` obtained before a scroll can be stale, and clicking a stale node is how a probe reports a
     * clickable-looking row as "click refused". It comes back with its tree position, because the coordinate arm needs to
     * relate the node read back at the tap point to *this* node.
     */
    private fun scrollUntilVisible(service: A11yProbeService, configuration: Config): NodeInTree? {
        var scrollsDone = 0
        var moved = true
        while (true) {
            val visible = service.visibleLabels()
            val matched = firstCandidateIn(configuration.targetLabels, visible)
            when (scrollDecision(matched != null, moved, scrollsDone, configuration.maxScrolls)) {
                "arrived" -> {
                    // `findExactNodeWithPath`, not T3b's substring `findNode`: the label that satisfied `firstCandidateIn`
                    // must be the label that gets clicked, or the verdict would judge a row the probe never aimed at.
                    val node = service.findExactNodeWithPath(configuration.targetLabels)
                    if (node != null && matched != null) return node
                    // The label is visible but no node could be resolved: count a scroll and try again rather than
                    // spinning on the same frame.
                    moved = scrollOnce(service, configuration)
                    scrollsDone += 1
                }
                "no_progress" -> return null
                "budget" -> return null
                else -> {
                    val dispatched = scrollOnce(service, configuration)
                    scrollsDone += 1
                    Log.i(
                        A11yProbeService.TAG,
                        "A1_SCROLL_STEP scrolls=$scrollsDone dispatched=$dispatched moved=$moved " +
                            "package=${service.activePackage()}",
                    )
                }
            }
        }
    }

    /** Dispatch one forward scroll on the list that contains the target, and report whether the screen text moved. */
    private fun scrollOnce(service: A11yProbeService, configuration: Config): Boolean {
        val before = service.fingerprint()
        // Anchor the scroll at the target when it exists (its ancestors own the scrollable list); otherwise fall back to
        // the scrollable node nearest the window root, because before the target is visible there is nothing to climb.
        val anchor = service.findExactNode(configuration.targetLabels) ?: service.scrollAnchor()
        val dispatched = service.scrollForwardFrom(anchor)
        sleep(SCROLL_SETTLE_MILLIS)
        val after = service.fingerprint()
        if (after != before) return true
        // One unchanged frame after a dispatched scroll can be a settled animation; give the list one more chance
        // before declaring it ended. Only the second unchanged frame is the stop (see `scrollDecision`).
        if (!dispatched) return false
        val secondBefore = service.fingerprint()
        service.scrollForwardFrom(anchor)
        sleep(SCROLL_SETTLE_MILLIS)
        return service.fingerprint() != secondBefore
    }

    // ------------------------------------------------------------------ scenario 2: popup

    /**
     * Request a runtime permission from *this* app and let the accessibility service click the system dialog's
     * affirmative control. The verdict is `checkSelfPermission`, read back after the dialog is gone — the permission
     * flipping to GRANTED is the only thing that counts.
     *
     * The permission cannot be revoked from inside the app (`pm revoke` is a shell action and is the Lead's step), so an
     * already-GRANTED state is reported as `skipped` rather than silently "succeeding".
     */
    private fun runPopupRounds(service: A11yProbeService, configuration: Config): List<PopupRecord> {
        val records = mutableListOf<PopupRecord>()
        repeat(POPUP_ROUNDS) { round ->
            if (isGranted(configuration.permission)) {
                records += PopupRecord(outcome = "skipped", affirmativeClicked = false)
                Log.i(
                    A11yProbeService.TAG,
                    "A1_POPUP round=${round + 1} skipped=already_granted permission=${configuration.permission}",
                )
                return@repeat
            }

            // The dialog is created asynchronously after the request, so wait for it instead of racing it. A permission
            // dialog does not stop this activity (it only takes focus), so an un-waited read would find this activity's
            // own window and wrongly report "no dialog". The wait is overridable because `no_dialog` cannot otherwise be
            // told apart from "the probe stopped waiting too early" — see `EXTRA_DIALOG_WAIT_MILLIS`.
            requestPermissions(arrayOf(configuration.permission), REQUEST_CODE)
            val dialogSeen = waitForAny(service, configuration.affirmativeLabels, configuration.dialogWaitMillis)
            val affirmative = service.findNode(configuration.affirmativeLabels)
            val clicked = affirmative != null && service.clickNode(service.clickableAncestor(affirmative) ?: affirmative)
            if (clicked) sleep(SETTLE_MILLIS)

            val state = if (isGranted(configuration.permission)) "granted" else "denied"
            val outcome = popupVerdict(permissionState = state, dialogSeen = dialogSeen, affirmativeClicked = clicked)
            records += PopupRecord(outcome = outcome, affirmativeClicked = clicked)
            Log.i(
                A11yProbeService.TAG,
                "A1_POPUP round=${round + 1} permission=${configuration.permission} dialogSeen=$dialogSeen " +
                    "affirmativeClicked=$clicked state=$state outcome=$outcome",
            )
            if (state != "granted") {
                // Leave no half-open dialog behind for the next round or for the operator.
                service.goBack()
                sleep(SETTLE_MILLIS)
            }
        }
        return records
    }

    /** Poll for any of [candidates] until [timeoutMillis] elapses; report whether one ever appeared. */
    private fun waitForAny(service: A11yProbeService, candidates: List<String>, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (service.isShown(candidates)) return true
            sleep(POLL_MILLIS)
        }
        return false
    }

    private fun isGranted(permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(A11yProbeService.TAG, "A1_SETTINGS_LAUNCH_FAILED ${it.message}") }
    }

    private fun sleep(millis: Long) = runCatching { Thread.sleep(millis) }

    companion object {
        /** The action the Lead's adb command targets. A separate action keeps this mode opt-in and greppable. */
        const val ACTION = "edith.phone.a11yprobe.intent.A1_RUN"

        const val EXTRA_CONFIRM = "confirm"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_PERMISSION = "permission"
        const val EXTRA_TARGET_LABELS = "targetLabels"
        const val EXTRA_MARKER_LABELS = "markerLabels"
        const val EXTRA_MAX_SCROLLS = "maxScrolls"

        /**
         * How long the popup arm waits for the system dialog (milliseconds), overridable so `no_dialog` can be told
         * apart from a too-short wait without a rebuild: `--ei dialogWaitMillis 15000`, or `--el` for a long.
         *
         * The default stays [DIALOG_WAIT_MILLIS]. It is **not** a success threshold: a longer wait can only turn
         * `no_dialog` into a real dialog observation, never into a grant, and `no_dialog` remains an honest failure
         * class rather than being re-judged as success.
         */
        const val EXTRA_DIALOG_WAIT_MILLIS = "dialogWaitMillis"

        const val SCENARIO_ALL = "all"
        const val SCENARIO_SCROLL = "scroll"
        const val SCENARIO_POPUP = "popup"

        /**
         * The default target: a Settings entry that sits **below the fold** on a phone, Chinese first (MIUI/Redmi) then
         * English (AOSP-style). This is an assumption about the device and about where the fold falls; the Lead can
         * override it with `--esal targetLabels` without a rebuild, and the assumptions are listed in the handover.
         */
        val TARGET_LABELS = listOf("关于手机", "我的设备", "About phone", "My device")

        /**
         * Labels that only the destination screen carries — the A1 replacement for T3b's "the screen changed". Because a
         * target entry is often *repeated as the destination page's own header*, these are deliberately sibling rows,
         * not the header: matching the header would make the probe report arrival without leaving the list.
         */
        val MARKER_LABELS = listOf(
            "全部参数", "设备名称", "内核版本", "MIUI 版本",
            "All specs", "Device name", "Kernel version", "Android version",
        )

        /**
         * The affirmative control on a permission dialog, Chinese first (MIUI/Redmi) then English (AOSP-style).
         *
         * `确定`/`OK` are on the list because several builds label the positive button that way; if a build uses wording
         * none of these match, the verdict says `no_affirmative` and the list is the fix.
         *
         * This is only the **default**: [EXTRA_AFFIRMATIVE_LABELS] replaces it for one run.
         */
        val AFFIRMATIVE_LABELS = listOf("允许", "始终允许", "确定", "Allow", "ALLOW", "While using the app", "OK")

        /**
         * Replaces [AFFIRMATIVE_LABELS] for the popup arm of this run: `--esal affirmativeLabels "继续安装,安装,确定"`.
         *
         * Added because the popup arm is also the only channel that can see *some* confirmations at all (on MIUI the
         * install dialog's screenshots come back black and `uiautomator dump` returns a null root), and their wording is
         * a property of the build, not of this code — discovering it must not require a rebuild. Absent, the constant
         * default applies, so every earlier run's evidence still means exactly what it did.
         */
        const val EXTRA_AFFIRMATIVE_LABELS = "affirmativeLabels"

        private const val REQUEST_CODE = 0xA1

        /** Two rounds: one real measurement, and a second that either repeats it or proves `skipped`. */
        private const val SCROLL_ROUNDS = 2
        private const val POPUP_ROUNDS = 2

        /**
         * The coordinate arm runs the same scroll-and-tap task as [SCROLL_ROUNDS], never instead of it: the node rounds
         * keep the original evidence shape, and these rounds are what the mis-tap numerator is computed from.
         */
        private const val COORDINATE_ROUNDS = 2

        private const val MAX_SCROLLS = 6

        /** Long enough for a window transition on the mid-range target device, short enough to stay bounded. */
        private const val SETTLE_MILLIS = 900L
        private const val SCROLL_SETTLE_MILLIS = 500L
        private const val DIALOG_WAIT_MILLIS = 4_000L
        private const val POLL_MILLIS = 150L
    }
}
