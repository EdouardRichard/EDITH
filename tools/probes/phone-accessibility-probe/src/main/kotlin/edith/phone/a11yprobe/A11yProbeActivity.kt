package edith.phone.a11yprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.TextView

/**
 * P3.0 T3b runner: N rounds of "navigate away from a Settings entry", once by semantic node click and once by a
 * coordinate tap at the same node's bounds, then one summary line.
 *
 * The target is deliberately an entry *inside* the system Settings app driven through a public intent: it is
 * cross-app (the point of the probe), it exists on every Android phone, and it needs no credentials, no account and
 * no network. The candidate labels cover Chinese and English so one build measures both a MIUI/Redmi and an AOSP-style device.
 *
 * The run is bounded (default 20 rounds) and only ever acts on Settings; it never touches another app the operator has
 * not opened. Nothing is stored or uploaded — the numbers go to logcat and nothing else.
 */
class A11yProbeActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 16f
            setPadding(40, 120, 40, 40)
            text = "EDITH P3 T3b\n\n等待无障碍服务启用…\n\n启用后自动开始 N 轮测试。"
        }
        setContentView(status)

        val rounds = intent?.getIntExtra(EXTRA_ROUNDS, DEFAULT_ROUNDS) ?: DEFAULT_ROUNDS
        // A short delay so the operator can see the screen and the service can attach after enabling it.
        handler.postDelayed({ startWhenReady(rounds) }, START_DELAY_MILLIS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startWhenReady(rounds: Int) {
        val service = A11yProbeService.instance
        if (service == null) {
            status.text = "EDITH P3 T3b\n\n无障碍服务未启用。\n\n请在 设置 → 无障碍 中启用 " +
                "\"EDITH P3 T3b probe\"，然后重新打开本页。"
            Log.w(A11yProbeService.TAG, "A11Y_SERVICE_NOT_ENABLED")
            return
        }
        status.text = "EDITH P3 T3b\n\n进行中：$rounds 轮…"
        Thread { runRounds(service, rounds) }.start()
    }

    private fun runRounds(service: A11yProbeService, rounds: Int) {
        val runs = mutableListOf<A11yRun>()
        var attempt = 0

        repeat(rounds) { round ->
            // --- node mode -------------------------------------------------
            attempt += 1
            openSettings()
            sleep(SETTLE_MILLIS)
            val nodeBefore = service.fingerprint()
            val matched = service.findNode(TARGET_LABELS)
            val nodeRun = if (matched == null) {
                A11yRun(attempt, false, A11yMode.NODE_CLICK, service.describeMissing(TARGET_LABELS))
            } else {
                // Click the control, not the label: the text-bearing node is usually a child of the clickable row.
                val clickable = service.clickableAncestor(matched) ?: matched
                val performed = service.clickNode(clickable)
                sleep(SETTLE_MILLIS)
                val after = service.fingerprint()
                val run = A11yRun(attempt, isActionEffective(performed, nodeBefore, after), A11yMode.NODE_CLICK,
                    failureFor(performed, nodeBefore, after))
                // Recorded so the evidence says *which* node was aimed at when a click is refused.
                Log.i(A11yProbeService.TAG, "A11Y_TARGET matched='${matched.text ?: matched.contentDescription}' " +
                    "clickedLabel='${clickable.text ?: clickable.contentDescription}' clickable=${clickable.isClickable}")
                run
            }
            runs += nodeRun
            Log.i(A11yProbeService.TAG, "A11Y_RUN attempt=$attempt mode=NODE_CLICK ok=${nodeRun.ok} failure=${nodeRun.failure}")
            service.goBack()
            sleep(SETTLE_MILLIS)

            // --- coordinate mode ------------------------------------------
            attempt += 1
            openSettings()
            sleep(SETTLE_MILLIS)
            val coordBefore = service.fingerprint()
            val target = service.findNode(TARGET_LABELS)
            val coordinateRun = if (target == null) {
                // Deliberately NOT measured from a remembered coordinate: with no node there is no bounds, and a guessed
                // coordinate would make the coordinate mode look better than it is by testing a different target.
                A11yRun(attempt, false, A11yMode.COORDINATE_TAP, "no bounds to tap")
            } else {
                val bounds = service.boundsOf(target)
                val performed = service.tapCenter(bounds)
                sleep(SETTLE_MILLIS)
                val after = service.fingerprint()
                A11yRun(attempt, isActionEffective(performed, coordBefore, after), A11yMode.COORDINATE_TAP,
                    failureFor(performed, coordBefore, after, "dispatchGesture returned false"))
            }
            runs += coordinateRun
            Log.i(A11yProbeService.TAG, "A11Y_RUN attempt=$attempt mode=COORDINATE_TAP ok=${coordinateRun.ok} failure=${coordinateRun.failure}")
            service.goBack()
            sleep(SETTLE_MILLIS)
        }

        val summary = summarize(runs)
        Log.i(
            A11yProbeService.TAG,
            "P3_T3_A11Y_VERDICT nodeSuccess=${summary.nodeSuccess} nodeRuns=${summary.nodeRuns} " +
                "coordSuccess=${summary.coordSuccess} coordRuns=${summary.coordRuns} " +
                "failures=${summary.failures.entries.joinToString(",") { "${it.key}=${it.value}" }}",
        )
        handler.post {
            status.text = "EDITH P3 T3b\n\n节点点击 ${summary.nodeSuccess}/${summary.nodeRuns}\n" +
                "坐标点击 ${summary.coordSuccess}/${summary.coordRuns}\n\n结果已写入 logcat：P3_T3_A11Y_VERDICT"
        }
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(A11yProbeService.TAG, "A11Y_SETTINGS_LAUNCH_FAILED ${it.message}") }
    }

    private fun sleep(millis: Long) = runCatching { Thread.sleep(millis) }

    private companion object {
        const val EXTRA_ROUNDS = "rounds"
        const val DEFAULT_ROUNDS = 20
        const val START_DELAY_MILLIS = 3_000L

        /** Long enough for a window transition on a mid-range phone, short enough to keep 20 rounds bounded. */
        const val SETTLE_MILLIS = 900L

        /**
         * A Settings entry whose label is stable across the two target builds. Chinese first (MIUI/Redmi), English second
         * (AOSP-style); the probe records which one matched only through the failure text, which is enough to interpret a
         * zero.
         *
         * Public so A1 (`A11yIntentActivity`) can name the same entry when it is asked to re-measure the *shallow*
         * target: "scrolling changed the success rate" is only a meaningful statement if both modes aim at the same row.
         */
        val TARGET_LABELS = listOf("网络和互联网", "Network & internet", "WLAN", "Wi-Fi")
    }
}
