package edith.glasses

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edith.contracts.AssistantRoute
import edith.output.ConversationEntry
import edith.output.ConversationLog
import edith.output.ConversationRole
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
 * The glasses-side CustomApp entry point: the conversation view and the T18 render rules.
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
 * **Phase C (spec §6.6) changed what this screen is.** It used to show one hint at a time and erase it when the hint
 * expired. It now shows a **conversation**: AI replies on the left, the wearer's own messages on the right, one list
 * per route, with the live hint emphasised rather than being the only thing on screen. Retiring a hint therefore no
 * longer erases anything - `render(null)` means "nothing is live now", and the entries stay readable, which is the
 * whole point of a context view.
 *
 * [ConversationLog] is the single authority for what was said, in which order, under which route; the phone's
 * assistant tab renders the same object, so the two screens cannot disagree. The log is bounded, so a long session
 * cannot grow the view without limit.
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

    /** What was said, per route (Phase C). Bounded; see [ConversationLog]. */
    private val log = ConversationLog()

    /** Which route is in front of the wearer, and whether the slide is paused (`KEYCODE_ENTER`). */
    private val order = ConversationOrder()

    /** Rejects an out-of-order `SAID`; the custom-command channel promises nothing about ordering. */
    private val saidGate = SaidSequenceGate()

    private lateinit var headerView: TextView
    private lateinit var entriesView: LinearLayout
    private lateinit var hintScroll: ScrollView
    private lateinit var statusView: TextView

    /** The row the live hint occupies, so the layout report and the probe read the hint rather than the list. */
    private var liveRow: TextView? = null

    /** The entry id of the live hint, so exactly one row is emphasised. */
    private var liveEntryId: String? = null

    /** Drives [scheduleReveal]: one slide per conversation change, cancelled when the content changes again. */
    private var scrollAnimator: ValueAnimator? = null

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    /** Held only while something is on screen that needs to be seen; see [holdAwakeFor]. */
    private var hintWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turns the panel on when this Activity is shown. The ring's power key cannot be intercepted by an app
        // (official FAQ, 应用自启与按键拦截自定义: "指环中间键长按通常会触发 KEY_POWER ... 应用层无法拦截"), so an
        // arriving hint cannot light a panel the wearer deliberately switched off - but a session that starts while
        // the panel is off must not be silent, and this is the standard Android way to ask for it.
        setTurnScreenOn(true)
        // Phase C (spec §6.6: 文本提示页背景改 Color.TRANSPARENT, "消除绿色噪点"). The theme's window background paints
        // the whole panel with a non-black colour, which the waveguide shows as a green cast, so the window's own
        // background goes transparent as well as the layout's. Note the vendor FAQ's opposite advice ("建议将 Activity
        // 背景设置为黑色，可以降低启动或切换页面时的闪屏感") - that is about flicker on transitions, and this is the
        // owner's decision on colour; the two are traded off deliberately, and the device screenshot settles it.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(buildLayout())
        // The endpoint outlives this Activity, so it is given a listener rather than a reference to it.
        RokidBridgeEndpoint.instance.setListener { json -> acceptCommand(json) }
    }

    /** Keeps the display awake only while something is being shown; the wake lock itself lives in [holdAwakeFor]. */
    override fun onResume() {
        super.onResume()
        if (liveEntryId != null) holdAwakeFor(true)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the foreground ends the visible hint's claim on the panel; the controller clears the text too.
        holdAwakeFor(false)
        // ...and with it the slide, so a conversation that is no longer on screen cannot keep moving.
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    override fun onDestroy() {
        // Leaving the screen must never leave text on the glasses, and the endpoint must not keep a dead Activity.
        scrollAnimator?.cancel()
        scrollAnimator = null
        runCatching { controller.disconnect() }
        holdAwakeFor(false)
        // The conversation is session state: a destroyed Activity must not hand its words to the next one.
        log.clear()
        order.reset()
        saidGate.reset()
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
            when (command) {
                // A PING is the one command the controller deliberately ignores: it is the phone's clock exchange, and
                // answering it is this side's obligation. Everything else is the render/expiry state machine's business.
                is HintCommand.Ping -> {
                    val answered = clockResponder.onPing(command)
                    android.util.Log.i("EdithGlasses", "ping answered=$answered syncId=${command.syncId}")
                }

                // Phase C: what the wearer said. Filed under its own route, never switching the view (an utterance is
                // not something that needs to be looked at) and never acknowledged (it has no deadline to confirm).
                is HintCommand.Said -> onSaid(command)

                // BUSY belongs to the loading animation; it arrives with that task. Until then it is consciously
                // ignored here rather than half-handled, and the controller's own ignore list records the decision.
                else -> controller.accept(command)
            }
        }
        return true
    }

    /** The hint text as the wearer sees it, exposed so an instrumentation test can assert the committed view. */
    fun hintText(): String = liveRow?.text?.toString().orEmpty()

    /** Everything said in one conversation, one entry per line - the probe's view of [ConversationLog]. */
    fun conversationText(route: AssistantRoute): String =
        log.entries(route).joinToString("\n") { it.text }

    /** Which conversation is in front of the wearer, for the same reason. */
    fun selectedRoute(): AssistantRoute = order.selected()

    /**
     * The line count the live hint actually laid out into.
     *
     * Exposed for the same reason as [hintText]: the window shows a fixed number of lines, so nothing said about the
     * overflow would leave "the hint was taller than the screen" unobservable. Valid after a layout pass. Since
     * `P3.2-CLIP` an overflow is scrolled rather than clipped, and this count is what the scroll plan is built from.
     */
    fun renderedLineCount(): Int = liveRow?.lineCount ?: 0

    private companion object {
        /**
         * Upper bound on one hint's claim on the panel: longer than any hint the phone bounds (its `SHOW` carries the
         * remaining TTL, 10 s for the debug probe) and a safety net for a hint whose clear never arrives.
         */
        const val HINT_HOLD_MILLIS = 15_000L
    }

    /**
     * Files one accepted hint into its conversation and puts it on screen; `null` retires the live hint.
     *
     * `null` means **nothing is live**, not "forget what was shown": the entries stay, and only the emphasis is
     * removed. That distinction is the whole of spec §6.6 - a context view the phone can erase at will is not a
     * context view.
     */
    private fun render(displayed: DisplayedHint?) {
        if (displayed == null) {
            liveEntryId = null
            liveRow = null
        } else {
            val stored = log.append(
                ConversationEntry(displayed.hintId, displayed.route, ConversationRole.ASSISTANT, displayed.text))
            if (!stored) {
                // Refused only for a blank id or blank text, which the wire already rejects; recorded rather than
                // dropped silently because "nothing appeared" would otherwise have no cause.
                android.util.Log.i("EdithGlasses", "hint refused by the conversation log: blank id or text")
            }
            liveEntryId = displayed.hintId
            // The display follows what arrived. Without this the wearer reading one conversation would never see a
            // hint filed under the other, which is precisely the failure the route tags exist to prevent.
            order.show(displayed.route)
        }
        holdAwakeFor(liveEntryId != null)
        rebuild()
    }

    /** Files one utterance the wearer made. Must run on the UI thread. */
    private fun onSaid(command: HintCommand.Said) {
        // The same gate a SHOW passes: a late utterance from the session before must not appear in this one's
        // conversation. The identity lives in the controller, so this asks it rather than keeping a second copy that
        // could disagree with it.
        if (!controller.isCurrent(command.sessionId, command.generation)) {
            android.util.Log.i("EdithGlasses", "said refused: session or generation mismatch")
            return
        }
        if (!saidGate.accept(command.route, command.sequence)) {
            android.util.Log.i("EdithGlasses", "said refused: sequence ${command.sequence} is not newer")
            return
        }
        val stored = log.append(ConversationEntry(command.entryId, command.route, ConversationRole.USER, command.text))
        android.util.Log.i("EdithGlasses",
            "said stored=$stored route=${command.route.name} chars=${command.text.length}")
        rebuild()
    }

    /**
     * Redraws the conversation in front of the wearer, then reveals the newest entry.
     *
     * The list is rebuilt rather than patched: it is bounded at tens of entries, and a rebuild is what makes "the view
     * shows the log" true by construction instead of by careful incremental updates.
     */
    private fun rebuild() {
        val route = order.selected()
        val rows = ConversationView.rows(log.entries(route), liveEntryId)
        headerView.text = ConversationView.headerText(route, busy = false)
        statusView.text = ConversationView.statusText(log.size())
        entriesView.removeAllViews()
        liveRow = null
        rows.forEach { row -> entriesView.addView(entryRow(row)) }
        android.util.Log.i("EdithGlasses",
            HintPresentation.conversationReport(route, rows.size, rows.sumOf { it.entry.text.length }))
        scheduleReveal()
        // The layout's own limit is reported only **after** it has laid the text out - reading it here would read the
        // previous layout (or none). Counts and size only, never the text.
        liveRow?.post {
            val live = liveRow ?: return@post
            android.util.Log.i("EdithGlasses", HintPresentation.renderReport(live.lineCount, live.text.length))
        }
    }

    /** One row of the conversation: the wearer's words on the right, the assistant's on the left. */
    private fun entryRow(row: ConversationView.Row): TextView = TextView(this).apply {
        text = row.entry.text
        setTextColor(Color.parseColor(if (row.alignedToEnd) "#B2FF59" else "#00E676"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (row.live) 22f else 20f)
        typeface = if (row.live) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = if (row.alignedToEnd) Gravity.END else Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val vertical = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        setPadding(0, vertical, 0, vertical)
        if (row.live) liveRow = this
    }

    /**
     * Slides the conversation down to its newest entry (P3.2 `P3.2-CLIP` pacing, Phase C destination).
     *
     * The pacing is the owner's requirement, not a detail: the first version jumped a whole page at a time and the
     * verdict was "看到自动翻页了，但这不是友好的效果…滚动要延迟一两秒…让用户读完前面的字再滚动". So the plan is a
     * **duration**: hold still long enough to read the first window, then move at one line per reading pace. The
     * destination comes from [HintAutoScroll.endOffsetPx], the same quantity that pacing is computed from.
     *
     * A wearer who tapped the pad (`KEYCODE_ENTER`) has paused this: the plan then holds still, and only a tap
     * resumes it, so a slide can never fight the person reading.
     */
    private fun scheduleReveal() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        if (order.isPaused()) return
        entriesView.post {
            val lineHeight = (liveRow ?: entriesView.getChildAt(0) as? TextView)?.lineHeight ?: 0
            if (lineHeight <= 0) return@post
            val totalLines = (0 until entriesView.childCount)
                .mapNotNull { entriesView.getChildAt(it) as? TextView }
                .sumOf { it.lineCount }
            val duration = HintAutoScroll.scrollMillis(totalLines)
            if (duration <= 0L) {
                hintScroll.scrollTo(0, 0)
                return@post
            }
            val endPx = HintAutoScroll.endOffsetPx(totalLines, lineHeight)
            scrollAnimator = ValueAnimator.ofInt(hintScroll.scrollY, endPx).apply {
                this.duration = duration
                startDelay = HintAutoScroll.READ_DELAY_MILLIS
                interpolator = LinearInterpolator()
                addUpdateListener { animation -> hintScroll.scrollTo(0, animation.animatedValue as Int) }
                start()
            }
        }
    }

    /**
     * Wakes the panel for exactly as long as something is on screen.
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
     * The side-touch pad (spec §6.6 顶部支持切换 WAKE/PROACTIVE 上下文; owner's ruling 2026-10-15).
     *
     * The vendor's official sample (`50-system/01-按键与侧边触控.md`) documents this channel: the temple pad sends
     * `KeyEvent`s to the foreground page - `KEYCODE_DPAD_RIGHT` for a forward swipe, `KEYCODE_DPAD_LEFT` for a
     * backward one, `KEYCODE_ENTER` for a tap - and it is **not** a broadcast. The sample registers through
     * `ViewCompat`, which exists to reach API < 28; this app's `minSdk` is 28, so the platform's own
     * `addOnUnhandledKeyEventListener` is the same mechanism without adding a dependency.
     *
     * Only `ACTION_DOWN` is handled, and `true` is returned, because both are what stop one gesture from being acted
     * on twice (the sample's own warning). `BACK` is deliberately not consumed: the wearer must keep a way out.
     *
     * **The list is unhandled-key only**: if the root view or a child consumes these keys first, this listener never
     * sees them, and the device reading is what settles whether that happens on this build.
     */
    private fun installSideTouch(root: View) {
        root.addOnUnhandledKeyEventListener(View.OnUnhandledKeyEventListener { _, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnUnhandledKeyEventListener false
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val route = order.swipe()
                    android.util.Log.i("EdithGlasses", "side touch: route now ${route.name}")
                    rebuild()
                    true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    val paused = order.togglePaused()
                    android.util.Log.i("EdithGlasses", "side touch: paused=$paused")
                    if (!paused) scheduleReveal()
                    true
                }
                else -> false
            }
        })
    }

    /**
     * The fixed layout: a padded safe area, a route header, the conversation window and one small status line.
     *
     * Built in code on purpose - a `TextView` needs no resources, and a resource file would add a build surface that
     * nothing here needs.
     */
    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        // The official FAQ's other display note: "受光学结构影响，屏幕顶部约 40px 区域可能出现倒影。建议将关键文字、
        // 图标和按钮整体下移 40px." The conversation is the one thing that must be read, so it is pushed below that zone.
        val reflectionZone = 40
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding + reflectionZone, padding, padding)
        }
        headerView = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            text = ConversationView.headerText(AssistantRoute.WAKE, busy = false)
        }
        entriesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Phase C C1: nothing may paint the panel except the text. A child view would otherwise sit on the
            // theme's window background, and on the waveguide that non-black fill is the green cast to remove.
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        // The window the wearer sees: exactly [HintAutoScroll.VISIBLE_LINES] lines tall, so the auto-scroll plan and
        // the drawing agree on what "one page" is.
        val windowPx = (20f * resources.displayMetrics.scaledDensity).toInt()
        hintScroll = ScrollView(this).apply {
            // P3.2 Stage 0 had the bar off ("no input path"); Phase C turns it back on because the spec asks for a
            // scrollbar that shows while the content moves and hides when it stops. Android's own bar does exactly
            // that: `View.scrollTo` calls `awakenScrollBars()`, so a programmatic slide fades it in and lets it fade
            // out again. No timer of our own.
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            isFillViewport = false
            setBackgroundColor(Color.TRANSPARENT)
            // The other half of C1, measured on the device on 2026-10-15: `ScrollView` sets itself **focusable**, and
            // since API 26 a focused view is drawn with the framework's *default focus highlight* over its bounds.
            // With nothing else focusable in this window the ScrollView always held focus, so a uniform ~6% white
            // rectangle sat over exactly the conversation window (#101010 on black, #FF1010 on red) whatever colour
            // was set anywhere - the screenshot is what found it. A read-only display must not be focusable at all.
            isFocusable = false
            defaultFocusHighlightEnabled = false
            addView(entriesView)
        }
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            text = ConversationView.statusText(0)
        }
        column.addView(headerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(hintScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, windowPx * HintAutoScroll.VISIBLE_LINES))
        column.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        installSideTouch(root)
        return root
    }
}
