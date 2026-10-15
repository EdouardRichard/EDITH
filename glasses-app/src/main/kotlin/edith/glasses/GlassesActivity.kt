package edith.glasses

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edith.output.DisplayedHint
import edith.output.GlassHintController
import edith.output.HintCommand
import edith.output.HintWireCodec
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The glasses-side CustomApp entry point: one fixed, lightweight view and the T18 render rules.
 *
 * The phone never learns whether a hint became visible, so the last line of defence lives here: [GlassHintController]
 * refuses a `SHOW` for another session, an older revision, a sequence behind a `CLEAR` fence, an unknown or stale
 * clock mapping, or a deadline that has already passed - and it expires the text locally, because the phone may stop
 * sending `CLEAR` and must not be relied upon to deliver it.
 *
 * Everything arrives on the bridge's callback thread, so commands are applied on the UI thread. That is what makes the
 * acknowledgement honest: `GlassHintController` reports `Displayed` only after the render callback has committed, and
 * a view update that had merely been *posted* would have acknowledged something the wearer could not see yet.
 *
 * A light `TextView` is deliberate (plan Task 18 Step 3): the layout is fixed, the safe area is padding rather than a
 * measurement, and hints are short - but a hint taller than the six-line window is now **walked through** by
 * [HintAutoScroll] rather than clipped (P3.2 `P3.2-CLIP`), still with no measurement and no input path.
 */
class GlassesActivity : Activity() {

    private val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime() }
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "edith-glasses-ttl").apply { isDaemon = true }
    }

    /** Locally scheduled expiry, on its own thread, lifted to the UI thread so only the UI touches the view. */
    private val scheduler = object : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val delayNanos = (deadlineNanos - clock.nowNanos()).coerceAtLeast(0L)
            val future = executor.schedule({ runOnUiThread(task) }, delayNanos, TimeUnit.NANOSECONDS)
            return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
        }
    }

    private val controller by lazy {
        GlassHintController(clock, scheduler, render = ::render, ack = ::acknowledge,
            diagnostics = { message -> android.util.Log.i("EdithGlasses", message) })
    }

    /** The handshake half: a `PING` is answered immediately, because the phone cannot compute a mapping without it. */
    private val clockResponder by lazy {
        GlassClockResponder(clock) { json -> RokidBridgeEndpoint.instance.send(json) }
    }

    private lateinit var hintView: TextView
    private lateinit var hintScroll: ScrollView
    private lateinit var statusView: TextView

    /** Drives [scheduleAutoScroll]: one slide per hint, cancelled the moment the hint changes or leaves the screen. */
    private var scrollAnimator: ValueAnimator? = null

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    /** Held only while a hint is on screen; see [holdAwakeFor]. */
    private var hintWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turns the panel on when this Activity is shown. The ring's power key cannot be intercepted by an app
        // (official FAQ, 应用自启与按键拦截自定义: "指环中间键长按通常会触发 KEY_POWER ... 应用层无法拦截"), so an
        // arriving hint cannot light a panel the wearer deliberately switched off - but a session that starts while
        // the panel is off must not be silent, and this is the standard Android way to ask for it.
        setTurnScreenOn(true)
        setContentView(buildLayout())
        // The endpoint outlives this Activity, so it is given a listener rather than a reference to it.
        RokidBridgeEndpoint.instance.setListener { json -> acceptCommand(json) }
    }

    /**
     * Keeps the display awake only while a hint is being shown; the wake lock itself lives in [holdAwakeFor].
     */
    override fun onResume() {
        super.onResume()
        if (hintText().isNotEmpty()) holdAwakeFor(true)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the foreground ends the visible hint's claim on the panel; the controller clears the text too.
        holdAwakeFor(false)
        // ...and with it the slide, so a hint that is no longer on screen cannot keep moving.
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    override fun onDestroy() {
        // Leaving the screen must never leave text on the glasses, and the endpoint must not keep a dead Activity.
        scrollAnimator?.cancel()
        scrollAnimator = null
        runCatching { controller.disconnect() }
        holdAwakeFor(false)
        RokidBridgeEndpoint.instance.setListener(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Applies one inbound command. Returns whether it was understood, so the caller can record the ones that were not.
     *
     * Public because it is the production entry point the bridge listener drives; an instrumentation test drives the
     * same method rather than a test-only path.
     */
    fun acceptCommand(json: String): Boolean {
        val command = HintWireCodec.decode(json) ?: return false
        runOnUiThread {
            // A PING is the one command the controller deliberately ignores: it is the phone's clock exchange, and
            // answering it is this side's obligation. Everything else is the render/expiry state machine's business.
            if (command is HintCommand.Ping) {
                val answered = clockResponder.onPing(command)
                android.util.Log.i("EdithGlasses", "ping answered=$answered syncId=${command.syncId}")
            } else {
                controller.accept(command)
            }
        }
        return true
    }

    /** The hint text as the wearer sees it, exposed so an instrumentation test can assert the committed view. */
    fun hintText(): String = hintView.text?.toString().orEmpty()

    /**
     * The line count the current hint actually laid out into.
     *
     * Exposed for the same reason as [hintText]: the window shows a fixed number of lines, so nothing said about the
     * overflow would leave "the hint was taller than the screen" unobservable. Valid after a layout pass. Since
     * `P3.2-CLIP` an overflow is scrolled rather than clipped, and this count is what the scroll plan is built from.
     */
    fun renderedLineCount(): Int = hintView.lineCount

    private companion object {
        /**
         * Upper bound on one hint's claim on the panel: longer than any hint the phone bounds (its `SHOW` carries the
         * remaining TTL, 10 s for the debug probe) and a safety net for a hint whose clear never arrives.
         */
        const val HINT_HOLD_MILLIS = 15_000L
    }

    /**
     * Shows one accepted hint; `null` retires whatever is live.
     *
     * Phase C (Task 2) changed what the controller hands over — the accepted hint's text **and** its route, because
     * only the controller knows whether the eight gates let the hint through. This build still renders a single hint
     * block; the conversation view that consumes `displayed.route` arrives with Task 5, and until then the route is
     * deliberately unused rather than guessed at.
     */
    private fun render(displayed: DisplayedHint?) {
        val text = displayed?.text
        val display = HintPresentation.displayText(text)
        if (HintPresentation.truncated(text)) {
            // Recorded rather than silently dropped: the phone bounded the hint, so this means it sent more than the
            // wire allows.
            android.util.Log.i("EdithGlasses", "hint exceeded the wire bound; extras hidden")
        }
        // A pushed screen message is the only thing that needs the panel, so it is woken with the hint and released
        // when the hint leaves the screen - including the expiry path, which calls this with null.
        holdAwakeFor(display.isNotEmpty())
        hintView.text = display
        statusView.text = if (display.isEmpty()) "等待提示" else "EDITH"
        scheduleAutoScroll()
        // P3.2 Stage 0: the layout's own limit is reported only **after** it has laid the text out - reading it here
        // would read the previous layout (or none). Counts and size only, never the text.
        hintView.post {
            android.util.Log.i("EdithGlasses", HintPresentation.renderReport(hintView.lineCount, display.length))
        }
    }

    /**
     * Slides a hint taller than the window down to its end (P3.2 `P3.2-CLIP`).
     *
     * The ring's middle key cannot be intercepted (vendor FAQ), so there is no input path for the wearer to scroll and
     * a `maxLines` window silently dropped everything past six lines - an 81-character wake answer lost its last line.
     * The view is therefore placed in a [ScrollView] and moved here.
     *
     * **One continuous animation, not page jumps.** The first build stepped a whole page at a time and the owner
     * rejected the effect on the device: it must slide slowly, and only after a moment long enough to read what is
     * already on screen. [HintAutoScroll] owns both numbers (a 1.5 s hold, then a line per 1.4 s), and a linear
     * interpolator keeps the speed constant so there is no lurch at either end. The animation is cancelled on the next
     * render, on a clear, and on pause, so a stale slide can never outlive the hint it belongs to.
     */
    private fun scheduleAutoScroll() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        hintScroll.scrollTo(0, 0)
        hintView.post {
            val stepPx = hintView.lineHeight.takeIf { it > 0 } ?: return@post
            val duration = HintAutoScroll.scrollMillis(hintView.lineCount)
            if (duration <= 0L) return@post
            val endPx = (hintView.lineCount - HintAutoScroll.VISIBLE_LINES).coerceAtLeast(0) * stepPx
            scrollAnimator = ValueAnimator.ofInt(0, endPx).apply {
                this.duration = duration
                startDelay = HintAutoScroll.READ_DELAY_MILLIS
                interpolator = LinearInterpolator()
                addUpdateListener { animation -> hintScroll.scrollTo(0, animation.animatedValue as Int) }
                start()
            }
        }
    }

    /**
     * Wakes the panel for exactly as long as a hint is on screen.
     *
     * Not a permanent keep-awake: a hint is the only thing that needs to be seen, so the display is woken when one is
     * pushed and released the moment it leaves the screen (expiry, clear, disconnect). The vendor SDK has no wake API
     * - the official FAQ documents `FLAG_KEEP_SCREEN_ON` and states the ring's `KEY_POWER` cannot be intercepted - so
     * this is the standard Android screen wake lock, bounded by [HINT_HOLD_MILLIS] as a safety net so a hint that is
     * never cleared cannot hold the panel on.
     */
    @Suppress("DEPRECATION")
    private fun holdAwakeFor(hold: Boolean) {
        if (hold) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (hintWakeLock == null) {
                hintWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "edith:hint")
                    .apply {
                        setReferenceCounted(false)
                        acquire(HINT_HOLD_MILLIS)
                    }
            }
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hintWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        hintWakeLock = null
    }

    private fun acknowledge(ack: HintCommand.Ack) {
        RokidBridgeEndpoint.instance.send(HintWireCodec.encode(ack))
    }

    /**
     * The fixed layout: a padded safe area, one hint block and one small status line.
     *
     * Built in code on purpose - a `TextView` needs no resources, and a resource file would add a build surface that
     * nothing here needs.
     */
    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        // The official FAQ's other display note: "受光学结构影响，屏幕顶部约 40px 区域可能出现倒影。建议将关键文字、
        // 图标和按钮整体下移 40px." The hint block is the one thing that must be read, so it is pushed below that zone.
        val reflectionZone = 40
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding + reflectionZone, padding, padding)
        }
        hintView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            // No `maxLines` any more: the window is the ScrollView below, and a taller hint is walked through instead
            // of being cut at six lines with nothing said (P3.2-CLIP).
            text = ""
        }
        // The window the wearer sees: exactly [HintAutoScroll.VISIBLE_LINES] lines tall, so the auto-scroll plan and
        // the drawing agree on what "one page" is. The hint is centred while it fits, and slides once it does not -
        // the view itself never measures anything; the height comes from the text size set just above.
        val windowPx = hintView.lineHeight.takeIf { it > 0 }
            ?: (22f * resources.displayMetrics.scaledDensity).toInt()
        hintScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(hintView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            text = "等待提示"
        }
        column.addView(hintScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, windowPx * HintAutoScroll.VISIBLE_LINES))
        column.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }
}
