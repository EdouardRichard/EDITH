package com.sheen.rokid.glasses.app
import com.sheen.rokid.glasses.display.HintPresentation
import com.sheen.rokid.glasses.display.HintTicker
import com.sheen.rokid.glasses.display.ConversationView
import com.sheen.rokid.glasses.bridge.RokidBridgeEndpoint
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.GlassesPanelView
import com.sheen.rokid.glasses.display.GradientMaskView
import com.sheen.rokid.glasses.display.LoadingIndicatorView
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.PinIconView
import com.sheen.rokid.glasses.display.TailTextView
import com.sheen.rokid.glasses.display.VoiceOrbView

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The glasses-side CustomApp entry point: the conversation view and the T18 render rules.
 *
 * The phone never learns whether a hint became visible, so the last line of defence is [GlassesPanelController] (and
 * the `GlassHintController` it owns): a `SHOW` for another session, an older revision, a sequence behind a `CLEAR`
 * fence, an unknown or stale clock mapping, or a deadline that has already passed is refused, and the text expires
 * locally because the phone may stop sending `CLEAR` and must not be relied upon to deliver it.
 *
 * Everything arrives on the bridge's callback thread, so commands are applied on the UI thread. That is what makes the
 * acknowledgement honest: the hint controller reports `Displayed` only after the render callback has committed, and
 * a view update that had merely been *posted* would have acknowledged something the wearer could not see yet.
 *
 * **0.23 P3/P4 changed what this screen is.** It shows one **conversation** at a time, every line left-aligned
 * (including the wearer's own), with the official bottom panel and a 1 px/frame ticker; the route is chosen by `SHOW`,
 * and `ENTER` pins the reading position. The live hint is not emphasised - it is simply the newest row; retiring it
 * does not erase anything (a null live id means "nothing is live now").
 *
 * **0.24 S2 Task 8 moved the other half out.** The conversation log, the selected route, the `SAID` gate, the busy
 * level and the BACK/pin decisions live in [GlassesPanelController]; this class only owns the view tree, the scroll
 * mechanics, the wake lock and the key/threading glue. It implements [PanelPort] so the controller can ask for exactly
 * that, and binds the published [PanelModel] to the views in [render].
 */
class GlassesActivity : Activity(), PanelPort {

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

    /**
     * 0.24 S2 Task 8: the panel's state, cache and decisions, extracted from this Activity.
     *
     * Set in `onCreate` before the bridge listener (and therefore before any command can arrive). It owns the
     * conversation log, the selected route, the `SAID` gate, the busy level and the BACK/pin decisions; this Activity
     * only forwards bytes/keys and binds the model it publishes to views through [render].
     */
    private lateinit var controller: GlassesPanelController

    /**
     * The last model the controller published.
     *
     * The probes and the resume path read it; the controller stays the only writer through [render]. Null only before
     * the first publish, which `onResume` performs before any probe can run.
     */
    private var model: PanelModel? = null

    /** The extracted view tree (0.24 S2 Task 7): every panel child this Activity touches lives there. */
    private lateinit var panel: GlassesPanelView

    private val entriesView: LinearLayout get() = panel.entriesView
    private val hintScroll: ScrollView get() = panel.hintScroll

    /** The official bottom cluster: the listening line, the voice orb and the two operating tips. */
    private val listeningView: TextView get() = panel.listeningView

    /** Typed as the fallback orb until Task 18 swaps in the PAG view; the panel itself exposes it as a `View`. */
    private val orbView: VoiceOrbView get() = panel.orbView as VoiceOrbView
    private val tipsView: TextView get() = panel.tipsView
    private val pinTextView: TextView get() = panel.pinTextView
    private val pinIconView: PinIconView get() = panel.pinIconView
    private val complianceView: TextView get() = panel.complianceView
    private val bottomMask: GradientMaskView get() = panel.bottomMask

    /** The thinking row currently in the list, so its ring can be stopped with the panel; null when not thinking. */
    private var thinkingIcon: LoadingIndicatorView? = null
    private var thinkingTextView: TextView? = null

    /**
     * 0.23 P4: one entry arrived since the last rebuild, so the rebuild must bring it into view before the ticker
     * starts. This is the "new content first" rule - it applies while pinned as well, because a pin is a reading
     * position, not a reason for the next answer to stay hidden.
     *
     * Set from the controller-owned [PanelModel.contentRevision], because "bring into view" is a scroll decision this
     * view owns; the controller only says that the content is new. Comparing entries directly would miss a replacement
     * that leaves the stored list equal (same hint id/text with a higher revision, or a repeated utterance).
     */
    private var newEntryPending = false

    /** The 1 px/frame engine; null while the list is not moving. */
    private var tickerAnimator: ValueAnimator? = null
    private var tickerFrames = 0
    private var tickerStartOffset = 0

    /** What the last BACK meant, so the device test can assert the branch rather than infer it from a log. */
    private var lastBackAction: String? = null
    private var lastBackStopSent: Boolean? = null

    /** Test-only override for the CXR send; production leaves it null and uses [RokidBridgeEndpoint]. */
    private var stopSenderForProbe: ((String) -> Boolean)? = null

    /** The wearer paged by hand: the ticker stays stopped until the next entry arrives (official `isAutoScroll=false`). */
    private var userReadPaused = false

    /** False between `onPause` and `onResume`; prevents a pending post from restarting animators on a hidden screen. */
    private var resumed = false

    /** Monotonic rebuild token: only the newest layout pass may restore/reveal a scroll position. */
    private var rebuildSerial = 0

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
        panel = GlassesPanelView(this)
        // The bottom fade follows the scroll; the decision (`updateScrollChrome`) stays in this class, so the listener
        // is installed here, before the tree is attached - the same point in construction as before the extraction.
        panel.hintScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollChrome() }
        // Registered on the **content view**, as the official sample does (`android.R.id.content`), rather than on the
        // panel: a key one of the panel's children consumed would never reach a listener attached below it, and the
        // sample's placement is the one that is known to work on this ROM.
        installSideTouch(findViewById(android.R.id.content) ?: panel)
        setContentView(panel)
        // 0.24 S2 Task 8: the controller is created before the bridge listener so no command can arrive before the
        // state it updates exists. `this` is the PanelPort: the only Android things the controller is allowed to ask
        // for are drawing a model, waking the panel, hiding it and telling the phone.
        controller = GlassesPanelController(
            clock = clock,
            scheduler = scheduler,
            port = this,
            send = ::sendCommand,
            diagnostics = { message -> android.util.Log.i("EdithGlasses", message) },
        )
        // The endpoint outlives this Activity, so it is given a listener rather than a reference to it.
        RokidBridgeEndpoint.instance.setListener { json -> acceptCommand(json) }
    }

    /** Keeps the display awake only while something is being shown; the wake lock itself lives in [holdAwakeFor]. */
    override fun onResume() {
        super.onResume()
        resumed = true
        // `onStart` re-applies the current level and re-arms the local busy bound. The view's rebuild follows the
        // published model; the scroll needs its own start because `applyVisualState` deliberately does not touch it.
        controller.onStart()
        startTickerIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        controller.onStop()
        // Leaving the foreground ends the visible hint's claim on the panel; the controller keeps its model, but a
        // hidden screen must not hold the wake lock or keep animators running.
        holdAwakeFor(false)
        // The ring stops with the screen: it is not a progress bar whose state matters while nobody can see it, and a
        // running animator keeps the panel's frame loop busy.
        orbView.stop()
        thinkingIcon?.stop()
        stopTicker()
    }

    /**
     * 0.23 P5 (U11, spec §5.3.1): the double-click's `KEYCODE_BACK`.
     *
     * The decision itself now lives in [GlassesPanelController.onKey]: **busy, with an established session identity**
     * sends `STOP` and keeps the window open; **idle, or busy with no identity to name** hides the panel. This override
     * is only the Activity's key-path and cancelled-gesture glue: the panel consumes BACK through its default key path
     * before an unhandled-key listener can see it (the official app handles it in a `Dialog.onKeyUp` instead), and a
     * gesture cancelled mid-press (the window lost focus) is not a wearer decision.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // A cancelled gesture (the window lost focus mid-press) is not a wearer decision. Consume it without
            // finishing so a lost focus cannot close the panel.
            if (event.isCanceled) return true
            return controller.onKey(keyCode, event.action)
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        // Leaving the screen must never leave text on the glasses, and the endpoint must not keep a dead Activity.
        // `disconnect` is the old hint-controller teardown: it drops the identity and retires a visible hint (which
        // renders "nothing live" through this Activity's port). The conversation cache it owns dies with this object;
        // the externally visible cleanup below is the part that must still happen explicitly.
        runCatching { controller.disconnect() }
        holdAwakeFor(false)
        orbView.stop()
        thinkingIcon?.stop()
        stopTicker()
        RokidBridgeEndpoint.instance.setListener(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Applies one inbound command. Returns whether it was understood, so the caller can record the ones that were not.
     *
     * Public because it is the production entry point the bridge listener drives; an instrumentation test drives the
     * same method rather than a test-only path.
     *
     * 0.24 S2 Task 8: the command decision lives in [GlassesPanelController.accept]. This method is only the Activity's
     * threading glue. The bridge calls from its callback thread, so a decoded command must be posted to the UI thread;
     * the return value is the decode answer, which the controller owns and which is therefore read here on the caller's
     * thread before the post. Undecodable input is rejected immediately and never posted, exactly as before.
     */
    fun acceptCommand(json: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return controller.accept(json)
        val understood = HintWireCodec.decode(json) != null
        if (understood) {
            runOnUiThread { controller.accept(json) }
        } else {
            // Same line the controller would record, on the thread that has to drop the message.
            android.util.Log.i("EdithGlasses", "inbound undecodable chars=${json.length}")
        }
        return understood
    }

    /** The hint text as the wearer sees it, exposed so an instrumentation test can assert the committed view. */
    fun hintText(): String = panel.liveRow?.text?.toString().orEmpty()

    /** Everything said in one conversation, one entry per line - the probe's view of the controller's log. */
    fun conversationText(route: AssistantRoute): String = controller.conversationText(route)

    /** Which conversation is in front of the wearer, for the same reason. */
    fun selectedRoute(): AssistantRoute = model?.route ?: AssistantRoute.WAKE

    private companion object {
        /**
         * Upper bound on one hint's claim on the panel: longer than any hint the phone bounds (its `SHOW` carries the
         * remaining TTL, 10 s for the debug probe) and a safety net for a hint whose clear never arrives.
         */
        const val HINT_HOLD_MILLIS = 15_000L
    }

    // ---------------------------------------------------------------- PanelPort (0.24 S2 Task 8)

    /** The panel is this Activity's content view; the controller never asks to show it in this task. */
    override fun show(route: AssistantRoute) = Unit

    /**
     * The controller's exit branch. In this full-screen Activity, hiding the panel is finishing it; Task 9's overlay
     * service replaces this with a real hide while keeping the resident service alive.
     *
     * The two BACK probes are set at the send seam when a `STOP` is attempted. An idle exit has no STOP to observe, so
     * it records here; an exit that followed a failed STOP keeps the `false` its line already recorded.
     */
    override fun hide() {
        if (lastBackAction != "exit") {
            lastBackAction = "exit"
            lastBackStopSent = null
        }
        finish()
    }

    /**
     * Binds one published model to the view tree.
     *
     * The controller owns what the panel means; this method only decides how it looks: which row is live, where the
     * list is, whether the wake claim is held. A render also arrives while the screen is paused (the bridge outlives
     * the Activity), so the same early-release rule the old `applyPanelAndAnimation` had is kept here.
     */
    override fun render(model: PanelModel) {
        val previous = this.model
        this.model = model

        // A new session publishes an empty model: the old code reset the scroll state explicitly on that HELLO. Every
        // retired hint stays in its conversation, so "empty and nothing live" can only mean a new window.
        val newSession = model.entries.isEmpty() && model.liveEntryId == null
        if (newSession) {
            stopTicker()
            userReadPaused = false
            newEntryPending = false
        } else if (previous?.contentRevision != model.contentRevision) {
            // New content first, even while pinned: the pin is a reading position, not a reason for the next answer
            // to stay hidden (P4). The controller increments `contentRevision` for every accepted SHOW and every
            // stored selected-route SAID - including a replacement that leaves the entry list equal (identical
            // text with a higher revision, or a repeated utterance). A retirement does not increment, so it is
            // deliberately not a reveal.
            newEntryPending = true
        }

        // The pin's ticker half is view glue: the controller decides the pin, the scroll view decides how to stop.
        if (previous != null && previous.pinned != model.pinned) {
            if (model.pinned) stopTicker() else userReadPaused = false
        }

        // Two claimants share the panel (a live hint and a working turn), so the wake claim comes from the one model:
        // `idle` is false while either route is busy, exactly the old `BusyState.any` expression. On a paused screen it
        // is released, as the old early return did before anything could acquire it.
        if (!resumed) holdAwakeFor(false) else holdAwakeFor(!model.idle || model.liveEntryId != null)
        rebuild(model)
    }

    /** The controller asks for the screen when something must be seen; the reason is in [holdAwakeFor]. */
    override fun wakeScreen() {
        holdAwakeFor(true)
    }

    /** The panel is the Activity; there is no separate panel window yet. Task 11 wires `PANEL_SHOWN`/`UI_CLOSED`. */
    override fun sendPanelShown() = Unit

    override fun sendUiClosed() = Unit

    /**
     * The official panel's state row, applied from the one model so the listening line, the thinking row, the orb and
     * the tips cannot disagree with each other.
     *
     * A command can arrive while the screen is paused (the bridge outlives the Activity); it must not restart the orb
     * or the ticker behind a blank display. `onResume` applies the state once the screen is back.
     */
    private fun applyVisualState(model: PanelModel) {
        if (!::panel.isInitialized) return
        if (!resumed) return
        val route = model.route
        val live = model.liveEntryId != null
        val listening = model.listeningVisible
        val thinking = model.thinkingVisible
        val pinned = model.pinned
        val orb = orbStateFor(model)

        // Task 8 keeps Hi behaviour unchanged: the only listening row that exists today is `Hi, 我在听`, so it is the
        // model's `hiVisible` field that drives it. Task 13/17 adds the separate "…" row and the official first-open
        // rule, at which point `listeningVisible` gets its own view.
        listeningView.visibility = if (model.hiVisible) View.VISIBLE else View.GONE
        panel.thinkingRow?.let { row -> row.visibility = if (thinking) View.VISIBLE else View.GONE }
        if (thinking) thinkingIcon?.start() else thinkingIcon?.stop()
        orbView.setState(orb)
        tipsView.text = model.tips
        pinTextView.visibility = if (pinned) View.GONE else View.VISIBLE
        pinIconView.visibility = if (pinned) View.VISIBLE else View.GONE
        // The P3_STATE line keeps its historical fields. `any` is the model's transitional idle inverse; `busy` is the
        // per-route value reconstructed from the rows it drives (a live answer plus any working route). Counts and
        // state names only, never text - the same rule as every other P3/P4 line.
        val busyAny = !model.idle
        val busyForRoute = listening || thinking || (live && busyAny)
        android.util.Log.i("EdithGlasses",
            "P3_STATE route=${route.name} busy=$busyForRoute any=$busyAny live=$live " +
                "orb=${orb.name} listening=$listening thinking=$thinking pinned=$pinned " +
                "offset=${scrollOffsetY()} max=${maxScrollY()} ticker=${tickerAnimator != null}")
    }

    /**
     * The orb state from the one model, highest-priority first: a live answer, then listening, then thinking, then
     * idle. This is the same order `GlassesPanelSpec.orbState` uses; deriving it from the model keeps the orb and the
     * rows from disagreeing once the model is the only description of the state.
     */
    private fun orbStateFor(model: PanelModel): OrbState = when {
        model.liveEntryId != null -> OrbState.ANSWERING
        model.listeningVisible -> OrbState.LISTENING
        model.thinkingVisible -> OrbState.THINKING
        else -> OrbState.IDLE
    }

    /**
     * Redraws the conversation in front of the wearer.
     *
     * The list is rebuilt rather than patched: it is bounded at tens of entries, and a rebuild is what makes "the view
     * shows the model" true by construction instead of by careful incremental updates. The model supplies the route,
     * the entries, the live id and whether the thinking row is visible; this method only builds `View`s from them.
     */
    private fun rebuild(model: PanelModel) {
        if (!::panel.isInitialized) return
        val route = model.route
        val rows = ConversationView.rows(model.entries, model.liveEntryId)
        // `removeAllViews` can clamp the scroll to zero, so the position to restore is read before the rebuild.
        val previousScroll = scrollOffsetY()
        entriesView.removeAllViews()
        panel.setLiveRow(null)
        thinkingIcon = null
        panel.setThinkingRow(null)
        thinkingTextView = null
        rows.forEach { row -> entriesView.addView(entryRow(row)) }
        if (model.thinkingVisible) {
            entriesView.addView(thinkingRow())
        }
        android.util.Log.i("EdithGlasses",
            HintPresentation.conversationReport(route, rows.size, rows.sumOf { it.entry.text.length }))
        applyVisualState(model)
        val serial = ++rebuildSerial
        entriesView.post {
            // Only the newest rebuild may move the scroll. Several commands can arrive before the layout pass; a
            // stale post restoring its own `previousScroll` would otherwise undo the newest entry's reveal.
            if (serial != rebuildSerial || isFinishing || isDestroyed) return@post
            val max = maxScrollY()
            if (newEntryPending) {
                // New content first: the newest entry's top, even while pinned. The ticker, if it runs, carries the
                // rest of that entry through the window.
                stopTicker()
                val top = (lastMessageRowForProbe()?.top ?: 0).coerceIn(0, max)
                hintScroll.scrollTo(0, top)
                android.util.Log.i("EdithGlasses", "P4_REVEAL top=$top offset=${scrollOffsetY()} max=$max")
                userReadPaused = false
                newEntryPending = false
            } else {
                hintScroll.scrollTo(0, previousScroll.coerceIn(0, max))
            }
            updateScrollChrome()
            startTickerIfNeeded()
            panel.liveRow?.let { live ->
                android.util.Log.i("EdithGlasses",
                    HintPresentation.renderReport(live.lineCount, live.text.length, max))
            }
        }
    }

    /**
     * Starts the official ticker when there is somewhere to go and the wearer has not stopped it.
     *
     * The engine is a `ValueAnimator` whose duration is effectively endless; the per-frame work is [HintTicker]'s
     * arithmetic. Starting is idempotent - every incoming command re-applies the state - and the ticker stops itself
     * at the bottom, on the pin, on a manual page and with the screen.
     */
    private fun startTickerIfNeeded() {
        if (!resumed) return
        if (tickerAnimator != null) return
        if (model?.pinned == true || userReadPaused) return
        if (!HintTicker.shouldScroll(scrollOffsetY(), maxScrollY())) return
        tickerStartOffset = scrollOffsetY()
        tickerFrames = 0
        tickerAnimator = ValueAnimator.ofInt(0, Int.MAX_VALUE).apply {
            duration = Int.MAX_VALUE.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                tickerFrames++
                val next = HintTicker.offsetAfterFrames(tickerStartOffset, tickerFrames, maxScrollY())
                val delta = next - scrollOffsetY()
                if (delta > 0) hintScroll.scrollBy(0, delta)
                if (tickerFrames % 30 == 0) {
                    // Movement evidence: offset and bound, never the text. Every 30 frames is about half a second.
                    android.util.Log.i("EdithGlasses",
                        "P4_TICKER frames=$tickerFrames offset=${scrollOffsetY()} max=${maxScrollY()}")
                }
                if (!HintTicker.shouldScroll(scrollOffsetY(), maxScrollY())) {
                    android.util.Log.i("EdithGlasses",
                        "P4_TICKER_STOP offset=${scrollOffsetY()} max=${maxScrollY()}")
                    stopTicker()
                }
            }
            start()
        }
    }

    /** Stops the ticker; idempotent. */
    private fun stopTicker() {
        tickerAnimator?.cancel()
        tickerAnimator = null
        tickerFrames = 0
    }

    /**
     * One page key: `min(remaining, viewport * 0.85)` in the requested direction, and the ticker stops.
     *
     * A manual page is the wearer reading, so it also stops the continuous ticker until the next entry arrives (the
     * official `isAutoScroll = false` rule). It is not a pin: unpinning still resumes the ticker.
     */
    private fun pageBy(direction: Int) {
        stopTicker()
        userReadPaused = true
        val viewport = (if (::panel.isInitialized) hintScroll.height else 0) -
            (if (::panel.isInitialized) hintScroll.paddingTop + hintScroll.paddingBottom else 0)
        val remaining = if (direction > 0) maxScrollY() - scrollOffsetY() else scrollOffsetY()
        val step = HintTicker.pageStepPx(remaining, viewport.coerceAtLeast(0))
        if (step <= 0) return
        hintScroll.smoothScrollBy(0, direction * step)
        android.util.Log.i("EdithGlasses",
            "P4_PAGE direction=$direction step=$step offset=${scrollOffsetY()} max=${maxScrollY()}")
    }

    /** The bottom fade is visible only while the list has more content below. */
    private fun updateScrollChrome() {
        if (!::panel.isInitialized) return
        bottomMask.visibility =
            if (HintTicker.shouldScroll(scrollOffsetY(), maxScrollY())) View.VISIBLE else View.GONE
    }

    /**
     * One row of the conversation, in the official style.
     *
     * 0.23 U9: every row is left-aligned; the wearer's line is 14 px at 70% green, capped at four lines and truncated
     * **from the start** (the official `StaticLayoutView` keeps the newest words), while the assistant's line is 16 px
     * at full green and never truncated at the view layer - the list scrolls instead.
     */
    private fun entryRow(row: ConversationView.Row): TextView {
        val role = row.entry.role
        val view: TextView = if (role == com.sheen.rokid.output.ConversationRole.USER) {
            // U6: keep the **newest** four lines. AOSP start-ellipsis is single-line only, so a plain
            // `maxLines=4 + ellipsize=START` TextView would keep the oldest four instead.
            TailTextView(this, maxVisibleLines = GlassesPanelSpec.maxLines(com.sheen.rokid.output.ConversationRole.USER))
        } else {
            TextView(this)
        }
        view.apply {
            text = row.entry.text
            setTextColor(GlassesPanelSpec.textColor(role))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.textSizePx(role))
            if (role != com.sheen.rokid.output.ConversationRole.USER) {
                maxLines = GlassesPanelSpec.maxLines(role)
            }
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val vertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
            setPadding(0, vertical, 0, vertical)
            if (row.live) panel.setLiveRow(this)
        }
        return view
    }

    /**
     * The `正在思考` row: the official 22x22 icon plus 16 px full-green copy, as the last line of the conversation.
     *
     * It is a row **inside** the list (not a separate overlay), because the list scrolls and the thinking line must
     * move with it. The icon is the existing ring with a smaller dot radius, not a second implementation.
     */
    private fun thinkingRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val icon = LoadingIndicatorView(this@GlassesActivity, dotRadiusPx = 2f,
            colorInt = GlassesPanelSpec.GREEN_NORMAL)
        addView(icon, LinearLayout.LayoutParams(
            GlassesPanelSpec.THINKING_ICON_PX, GlassesPanelSpec.THINKING_ICON_PX))
        val label = TextView(this@GlassesActivity).apply {
            text = GlassesPanelSpec.THINKING_TEXT
            setTextColor(GlassesPanelSpec.GREEN_NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.THINKING_TEXT_SIZE_PX)
            gravity = Gravity.START
        }
        addView(label, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 5 })
        thinkingIcon = icon
        panel.setThinkingRow(this)
        thinkingTextView = label
        // The animator is started by `applyVisualState` only while the screen is resumed; starting it here would let a
        // BUSY/SAID that arrives while paused keep a ring running behind a blank display.
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

    /**
     * The one outbound seam. The controller's `send` lambda and the `PONG` reply both use it, so an outbound command
     * can only reach the bridge one way.
     *
     * The two BACK probes are recorded here because the send outcome is observable here: `"stop"`/`true` when a STOP
     * went out, `"exit"`/`false` when it did not. A probe sender replaces the CXR send for STOP only, exactly as the
     * pre-extraction Activity's BACK branch did.
     */
    private fun sendCommand(command: HintCommand): Boolean {
        val sent = runCatching {
            val json = HintWireCodec.encode(command)
            if (command is HintCommand.Stop) {
                stopSenderForProbe?.invoke(json) ?: RokidBridgeEndpoint.instance.send(json)
            } else {
                RokidBridgeEndpoint.instance.send(json)
            }
        }.getOrDefault(false)
        if (command is HintCommand.Stop) {
            lastBackAction = if (sent) "stop" else "exit"
            lastBackStopSent = sent
        }
        return sent
    }

    /**
     * The side-touch pad (0.23 P4, spec §5.2.3/§5.2.4).
     *
     * The vendor's official sample (`50-system/01-按键与侧边触控.md`) documents this channel: the temple pad sends
     * `KeyEvent`s to the foreground page - `KEYCODE_DPAD_RIGHT` for a forward swipe, `KEYCODE_DPAD_LEFT` for a
     * backward one, `KEYCODE_ENTER` for a tap - and it is **not** a broadcast. The sample registers through
     * `ViewCompat`, which exists to reach API < 28; this app's `minSdk` is 28, so the platform's own
     * `addOnUnhandledKeyEventListener` is the same mechanism without adding a dependency.
     *
     * 0.23 changes the meaning: the four directions page the list (official `min(remaining, viewport*0.85)`), and
     * `ENTER` toggles the pin. There is **no route switch** left (U8). `BACK` is handled in [onKeyUp] instead of here:
     * the Activity consumes BACK through its default key path before an unhandled-key listener can see it.
     *
     * Only `ACTION_DOWN` is handled, and `true` is returned, because both are what stop one gesture from being acted
     * on twice (the sample's own warning).
     */
    private fun installSideTouch(root: View) {
        root.addOnUnhandledKeyEventListener(View.OnUnhandledKeyEventListener { _, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnUnhandledKeyEventListener false
            // 0.24 S2 Task 8: the controller owns the panel's key decisions (ENTER = pin). The four directions are the
            // scroll view's, so they only page after the controller declines them.
            if (controller.onKey(event.keyCode, event.action)) return@OnUnhandledKeyEventListener true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    pageBy(1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    pageBy(-1)
                    true
                }
                else -> false
            }
        })
    }

    // ---------------------------------------------------------------- instrumentation seams (production entry points)

    /** Which orb state is committed; the same value [applyVisualState] computed. */
    fun orbStateForProbe(): OrbState = orbView.state()

    fun listeningVisibleForProbe(): Boolean = listeningView.visibility == View.VISIBLE

    fun thinkingVisibleForProbe(): Boolean = panel.thinkingRow?.visibility == View.VISIBLE

    fun listeningTextForProbe(): String = listeningView.text.toString()

    fun thinkingTextForProbe(): String = thinkingTextView?.text?.toString().orEmpty()

    fun tipsTextForProbe(): String = tipsView.text.toString()

    fun complianceTextForProbe(): String = complianceView.text.toString()

    /** The last message row (the thinking row is a `LinearLayout`, so it is skipped). */
    fun lastMessageRowForProbe(): TextView? =
        (0 until entriesView.childCount).mapNotNull { entriesView.getChildAt(it) as? TextView }.lastOrNull()

    /** The committed scroll offset; the scroll-by-one ticker is asserted against this on the device. */
    fun scrollOffsetY(): Int = if (::panel.isInitialized) hintScroll.scrollY else 0

    /** How far the conversation can still travel; zero when everything fits. */
    fun maxScrollY(): Int =
        if (::panel.isInitialized) {
            // The ScrollView's padding is part of what the wearer sees but not of the scrollable viewport: subtracting
            // only the view's height under-reported the maximum by top+bottom padding, so a new entry could be
            // positioned exactly at the "maximum" and still sit under the bottom edge.
            val viewport = (hintScroll.height - hintScroll.paddingTop - hintScroll.paddingBottom)
                .coerceAtLeast(0)
            (entriesView.height - viewport).coerceAtLeast(0)
        } else 0

    fun isPinnedForProbe(): Boolean = model?.pinned == true

    fun tickerRunningForProbe(): Boolean = tickerAnimator != null

    /** The last BACK branch: `"stop"` while busy, `"exit"` when idle. Device-test seam only. */
    fun lastBackActionForProbe(): String? = lastBackAction

    fun lastBackStopSentForProbe(): Boolean? = lastBackStopSent

    /** Test-only: replaces the CXR send used by BACK, so both the sent and unsent branches can be driven on-device. */
    fun setStopSenderForProbe(sender: ((String) -> Boolean)?) {
        stopSenderForProbe = sender
    }

    /** The top of the last message row, in the list's own coordinates; the new-entry reveal lands here. */
    fun lastMessageTopForProbe(): Int = lastMessageRowForProbe()?.top ?: 0
}
