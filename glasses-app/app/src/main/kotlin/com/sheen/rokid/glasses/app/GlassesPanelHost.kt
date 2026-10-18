package com.sheen.rokid.glasses.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.glasses.bridge.RokidBridgeEndpoint
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.GlassesPanelView
import com.sheen.rokid.glasses.display.ConversationView
import com.sheen.rokid.glasses.display.GradientMaskView
import com.sheen.rokid.glasses.display.HintPresentation
import com.sheen.rokid.glasses.display.HintTicker
import com.sheen.rokid.glasses.display.LoadingIndicatorView
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.PinIconView
import com.sheen.rokid.glasses.display.RevealCoalescer
import com.sheen.rokid.glasses.display.TailTextView
import com.sheen.rokid.glasses.display.ToolRowState
import com.sheen.rokid.glasses.display.VoiceOrbView
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 0.24 S2 Task 9: the Android half shared by the production overlay service and the debug Activity test host.
 *
 * Task 7 extracted the panel's view tree, Task 8 extracted its state/cache/decisions, and this class is what remained
 * in `GlassesActivity`: the scroll reveal, the 1 px/frame ticker, the row builders, the wake claim and the key/threading
 * glue. It is shared rather than copied because the instrumentation suite must exercise the same rendering path the
 * production overlay uses - the Activity is only a test host now, and the service is the default.
 *
 * The two hosts differ only in the four window callbacks [Callbacks] receives: the Activity finishes on `hide` and
 * owns its window's keep-screen-on flag, while the service removes the overlay and sends `UI_CLOSED`. The state
 * machine is the controller's either way.
 */
class GlassesPanelHost(
    private val context: Context,
    private val callbacks: Callbacks,
    private val wakePolicy: WakePolicy = WakePolicy.CONTENT,
) : PanelPort {

    /** How long the panel's own wake claim is held; the same bound the pre-Task-9 Activity used. */
    enum class WakePolicy {
        /** Keep the screen on while something is live or a route is working (the Activity test host). */
        CONTENT,

        /** Keep the screen on for the whole visible panel window (the production overlay). */
        PANEL_VISIBLE,
    }

    interface Callbacks {
        /** The controller decided the panel must be hidden (BACK idle, or a STOP that could not be sent). */
        fun hidePanel()

        /** The host window was asked to show the panel for [route] (Task 11's `PANEL_OPEN` path / the debug host). */
        fun showPanel(route: AssistantRoute)

        /** Hold/release the host window's claim that the display stay awake. */
        fun keepAwake(hold: Boolean)
    }

    private val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "edith-glasses-ttl").apply { isDaemon = true }
    }

    /** Locally scheduled expiry, lifted to the UI thread so only the UI touches the view. */
    private val scheduler = object : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val delayNanos = (deadlineNanos - clock.nowNanos()).coerceAtLeast(0L)
            val future = executor.schedule({ mainHandler.post(task) }, delayNanos, TimeUnit.NANOSECONDS)
            return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
        }
    }

    /** The extracted view tree (0.24 S2 Task 7): every panel child lives there. */
    val root: GlassesPanelView = GlassesPanelView(context)

    /**
     * The panel's state, cache and decisions (0.24 S2 Task 8), created before any host installs a bridge listener so
     * no command can arrive before the state it updates exists.
     */
    private val controller = GlassesPanelController(
        clock = clock,
        scheduler = scheduler,
        port = this,
        send = ::sendCommand,
        diagnostics = { message -> Log.i(TAG, message) },
    )

    /** The last model the controller published; probes read it, the controller stays its only writer. */
    private var model: PanelModel? = null

    private val entriesView: LinearLayout get() = root.entriesView
    private val hintScroll get() = root.hintScroll
    private val listeningView: TextView get() = root.listeningView
    private val orbView: VoiceOrbView get() = root.orbView as VoiceOrbView
    private val tipsView: TextView get() = root.tipsView
    private val pinTextView: TextView get() = root.pinTextView
    private val pinIconView: PinIconView get() = root.pinIconView
    private val complianceView: TextView get() = root.complianceView
    private val bottomMask: GradientMaskView get() = root.bottomMask

    /** The thinking row currently in the list, so its ring can be stopped with the panel; null when not thinking. */
    private var thinkingIcon: LoadingIndicatorView? = null
    private var thinkingTextView: TextView? = null

    /** The tool row's icon/copy (0.24 S4 Task 17); null when no tool row is on screen. */
    private var toolIcon: LoadingIndicatorView? = null
    private var toolTextView: TextView? = null

    /**
     * The turns last handed to the view and the row objects they became.
     *
     * This is what makes a streamed revision an in-place patch: the next model is compared structurally with these
     * rows ([ConversationView.plan]); only a new/removed row rebuilds the list. A row's text is applied to the exact
     * `TextView` the wearer is already looking at, which is also what makes the final `SHOW` replace the same row.
     */
    private var renderedTurns: List<ConversationView.TurnRow>? = null
    private var renderedTurnViews: List<TurnViews> = emptyList()

    /** The official 50 ms reveal/layout cadence for streamed text; text itself is applied immediately. */
    private val revealCoalescer = RevealCoalescer()

    /** New content arrived since the last rebuild; see [PanelModel.contentRevision]. */
    private var newEntryPending = false

    /** The 1 px/frame engine; null while the list is not moving. */
    private var tickerAnimator: android.animation.ValueAnimator? = null
    private var tickerFrames = 0
    private var tickerStartOffset = 0

    /** What the last BACK meant, so the device test can assert the branch rather than infer it from a log. */
    private var lastBackAction: String? = null
    private var lastBackStopSent: Boolean? = null

    /** Test-only override for the CXR send; production leaves it null and uses [RokidBridgeEndpoint]. */
    private var stopSenderForProbe: ((String) -> Boolean)? = null

    /** The wearer paged by hand: the ticker stays stopped until the next entry arrives. */
    private var userReadPaused = false

    /** False between `onStop` and `onStart`; prevents a pending post from restarting animators on a hidden screen. */
    private var resumed = false

    /** Monotonic rebuild token: only the newest layout pass may restore/reveal a scroll position. */
    private var rebuildSerial = 0

    /** Set by [destroy]; a posted rebuild must not touch a view tree whose host is gone. */
    private var destroyed = false

    init {
        // The bottom fade follows the scroll; the decision stays here, so the listener is installed before the tree
        // is attached - the same point in construction as before the extraction.
        root.hintScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollChrome() }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Installs the side-touch key channel on [touchRoot] for the Activity test host.
     *
     * The official sample registers on the content view. This path only owns the keys the Activity's own callbacks do
     * not reliably see: ENTER is acted on **`ACTION_UP`** (the official `AiAssistantDialog.onKeyUp`) and its DOWN is
     * consumed; the vertical page keys page on `ACTION_DOWN` (the official `ErAIRecyclerView.dispatchKeyEvent`). Left
     * and right are deliberately **not** consumed, so the window keeps them.
     */
    fun installSideTouch(touchRoot: View) {
        touchRoot.addOnUnhandledKeyEventListener(View.OnUnhandledKeyEventListener { _, event ->
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
                        return@OnUnhandledKeyEventListener false
                    }
                    if (event.isCanceled) return@OnUnhandledKeyEventListener true
                    controller.onKey(event.keyCode, event.action)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action != KeyEvent.ACTION_DOWN) return@OnUnhandledKeyEventListener false
                    pageBy(1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action != KeyEvent.ACTION_DOWN) return@OnUnhandledKeyEventListener false
                    pageBy(-1)
                    true
                }
                else -> false
            }
        })
    }

    /** The host window is visible: re-apply the level and re-arm the local busy bound. */
    fun onStart() {
        resumed = true
        controller.onStart()
        startTickerIfNeeded()
    }

    /** The host window is hidden: release the wake claim and stop everything that cannot be seen. */
    fun onStop() {
        resumed = false
        controller.onStop()
        callbacks.keepAwake(false)
        orbView.stop()
        thinkingIcon?.stop()
        stopTicker()
    }

    /**
     * The Activity's key-down path. The panel consumes ENTER/BACK's down so no other handler acts on it; the action
     * itself waits for the up. The vertical page keys are handled by [installSideTouch], and left/right stay the
     * window's.
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER -> controller.onKey(keyCode, event.action)
            else -> false
        }
    }

    /**
     * The Activity's key-up path. BACK and ENTER act here, exactly as the official `AiAssistantDialog.onKeyUp`; a
     * cancelled gesture (a lost focus mid-press) is consumed without acting because it is not a wearer decision.
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.isCanceled) return true
            return controller.onKey(keyCode, event.action)
        }
        return false
    }

    /**
     * The overlay window's full key path.
     *
     * The overlay is the focused window, so it receives the side keys directly (S1 proved all six arrive). ENTER/BACK
     * act on UP and consume their DOWN; the vertical page keys page on DOWN and do not consume their UP; left/right are
     * returned to the system untouched.
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (controller.onKey(event.keyCode, event.action)) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    pageBy(1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    pageBy(-1)
                    true
                }
                else -> false
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ENTER) return false
            if (event.isCanceled) return true
            return controller.onKey(event.keyCode, event.action)
        }
        return false
    }

    /** The host is going away: retire a visible hint, stop animators, release the wake claim and the scheduler. */
    fun destroy() {
        resumed = false
        destroyed = true
        runCatching { controller.disconnect() }
        callbacks.keepAwake(false)
        orbView.stop()
        thinkingIcon?.stop()
        stopTicker()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- inbound

    /**
     * The production entry point: one inbound bridge payload.
     *
     * The bridge calls from its callback thread, so a decoded command must be posted to the UI thread. On the main
     * thread the controller is called inline, which is what makes the instrumentation assertions synchronous.
     */
    fun accept(json: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return controller.accept(json)
        val understood = HintWireCodec.decode(json) != null
        if (understood) {
            mainHandler.post { controller.accept(json) }
        } else {
            Log.i(TAG, "inbound undecodable chars=${json.length}")
        }
        return understood
    }

    // ---------------------------------------------------------------- PanelPort

    override fun show(route: AssistantRoute) = callbacks.showPanel(route)

    /**
     * The controller's exit branch. The Activity's implementation finishes the Activity; the overlay service removes
     * the window and keeps the resident service alive.
     */
    override fun hide() {
        if (lastBackAction != "exit") {
            lastBackAction = "exit"
            lastBackStopSent = null
        }
        callbacks.hidePanel()
    }

    override fun render(model: PanelModel) {
        val previous = this.model
        this.model = model

        // A new session publishes an empty model; a hidden window gets the same reset so no stale scroll/ticker can
        // survive it.
        val newSession = model.entries.isEmpty() && model.liveEntryId == null
        if (newSession) {
            stopTicker()
            userReadPaused = false
            newEntryPending = false
        } else if (previous?.contentRevision != model.contentRevision) {
            newEntryPending = true
        }

        // 0.24 spec §3.3/§5.3: the pin is one-way "do not auto-exit" and never stops or starts the ticker. The official
        // `startAutoScroll` keeps running after `holdAIAssistant()`; only a manual page, the end of the list or a
        // hidden panel stops it.

        if (!resumed) {
            callbacks.keepAwake(false)
        } else if (wakePolicy == WakePolicy.PANEL_VISIBLE) {
            callbacks.keepAwake(true)
        } else {
            callbacks.keepAwake(!model.idle || model.liveEntryId != null)
        }
        when (val plan = ConversationView.plan(renderedTurns, turnsOf(model))) {
            is ConversationView.RenderPlan.Rebuild -> rebuild(model, plan.turns)
            is ConversationView.RenderPlan.Update -> updateRows(model, plan)
            is ConversationView.RenderPlan.Unchanged -> {
                renderedTurns = plan.turns
                applyVisualState(model)
                // Nothing on the list changed; only a new-content event still needs its reveal.
                if (newEntryPending) scheduleAfterContent(force = false)
            }
        }
    }

    /** The official per-turn items for one published model (0.24 S4 Task 17). */
    private fun turnsOf(model: PanelModel): List<ConversationView.TurnRow> {
        val tool = model.toolLabel?.let { label ->
            ToolRowState(
                turnId = model.toolTurnId ?: "",
                label = label,
                phase = model.toolPhase ?: ToolPhase.START,
            )
        }
        return ConversationView.turns(
            entries = model.entries,
            liveEntryId = model.liveEntryId,
            thinkingVisible = model.thinkingVisible,
            thinkingTurnId = model.thinkingTurnId,
            tool = tool,
        )
    }

    override fun wakeScreen() {
        callbacks.keepAwake(true)
    }

    /** The service sends PANEL_SHOWN after the overlay's first layout; there is no controller caller yet. */
    override fun sendPanelShown() = Unit

    /**
     * The production service owns the visible→hidden event: its `hidePanel()` removes the window and sends
     * `UI_CLOSED` with the visible route. The controller's EXIT branch triggers that transition through [hide], so a
     * second send here would report a close that already happened.
     */
    override fun sendUiClosed() = Unit

    // ---------------------------------------------------------------- reset (Task 9)

    /**
     * The panel window closed: the controller's conversation cache is cleared (Task 8's `contentRevision` included),
     * and the empty model is published so a re-opened panel starts from nothing. The session identity is deliberately
     * kept - `disconnect()` is the different, link-loss teardown.
     */
    fun resetForClose() {
        controller.resetForClose()
        resetVisuals()
    }

    /**
     * Task 11: the panel is about to become visible from a hidden window. The conversation cache is cleared exactly
     * like a close, so content that arrived while hidden cannot reappear; the session identity is kept.
     */
    fun resetForOpen() {
        controller.resetForOpen()
        resetVisuals()
    }

    private fun resetVisuals() {
        stopTicker()
        userReadPaused = false
        newEntryPending = false
        thinkingIcon?.stop()
        thinkingIcon = null
        thinkingTextView = null
        toolIcon?.stop()
        toolIcon = null
        toolTextView = null
        renderedTurns = null
        renderedTurnViews = emptyList()
        revealCoalescer.reset()
        root.setThinkingRow(null)
        root.setToolRow(null)
        root.setLiveRow(null)
    }

    // ---------------------------------------------------------------- probes (the existing instrumentation contract)

    fun hintText(): String = root.liveRow?.text?.toString().orEmpty()

    fun conversationText(route: AssistantRoute): String = controller.conversationText(route)

    fun selectedRoute(): AssistantRoute = model?.route ?: AssistantRoute.WAKE

    fun currentIdentity(): Pair<String, Long>? = controller.currentIdentity()

    fun contentRevisionForProbe(): Long = model?.contentRevision ?: 0L

    fun orbStateForProbe(): OrbState = orbView.state()

    fun listeningVisibleForProbe(): Boolean = model?.listeningVisible == true

    /** The `Hi, 我在听` TextView itself; separate from the "…" row because Task 13 decoupled them. */
    fun hiVisibleForProbe(): Boolean = listeningView.visibility == View.VISIBLE

    fun pinTextVisibleForProbe(): Boolean = pinTextView.visibility == View.VISIBLE

    fun pinIconVisibleForProbe(): Boolean = pinIconView.visibility == View.VISIBLE

    fun thinkingVisibleForProbe(): Boolean = root.thinkingRow?.visibility == View.VISIBLE

    fun thinkingIconWidthForProbe(): Int = thinkingIcon?.width ?: 0

    fun thinkingTextSizeForProbe(): Float = thinkingTextView?.textSize ?: 0f

    /** True when the official status-7 tool row is on screen (0.24 S4 Task 17). */
    fun toolVisibleForProbe(): Boolean = root.toolRow?.visibility == View.VISIBLE

    fun toolTextForProbe(): String = toolTextView?.text?.toString().orEmpty()

    /** The row object itself, so a test can prove END restyles the START row instead of adding a second one. */
    fun toolRowForProbe(): View? = root.toolRow

    fun toolIconWidthForProbe(): Int = toolIcon?.width ?: 0

    fun toolLabelTextSizeForProbe(): Float = toolTextView?.textSize ?: 0f

    fun toolTextColorForProbe(): Int = toolTextView?.currentTextColor ?: 0

    fun listeningTextForProbe(): String = listeningView.text.toString()

    fun thinkingTextForProbe(): String = thinkingTextView?.text?.toString().orEmpty()

    fun tipsTextForProbe(): String = tipsView.text.toString()

    fun complianceTextForProbe(): String = complianceView.text.toString()

    fun lastMessageRowForProbe(): TextView? =
        (0 until entriesView.childCount).mapNotNull { entriesView.getChildAt(it) as? TextView }.lastOrNull()

    fun scrollOffsetY(): Int = hintScroll.scrollY

    fun maxScrollY(): Int {
        val viewport = (hintScroll.height - hintScroll.paddingTop - hintScroll.paddingBottom).coerceAtLeast(0)
        return (entriesView.height - viewport).coerceAtLeast(0)
    }

    fun isPinnedForProbe(): Boolean = model?.pinned == true

    fun tickerRunningForProbe(): Boolean = tickerAnimator != null

    fun lastBackActionForProbe(): String? = lastBackAction

    fun lastBackStopSentForProbe(): Boolean? = lastBackStopSent

    fun setStopSenderForProbe(sender: ((String) -> Boolean)?) {
        stopSenderForProbe = sender
    }

    fun lastMessageTopForProbe(): Int = lastMessageRowForProbe()?.top ?: 0

    // ---------------------------------------------------------------- rendering

    /**
     * The official panel's state row, applied from the one model so the listening line, the thinking row, the orb and
     * the tips cannot disagree with each other.
     */
    private fun applyVisualState(model: PanelModel) {
        if (!resumed) return
        val route = model.route
        val live = model.liveEntryId != null
        val listening = model.listeningVisible
        val hi = model.hiVisible
        val thinking = model.thinkingVisible
        val tool = model.toolLabel != null
        val pinned = model.pinned
        val orb = orbStateFor(model)

        listeningView.visibility = if (hi) View.VISIBLE else View.GONE
        root.thinkingRow?.let { row -> row.visibility = if (thinking) View.VISIBLE else View.GONE }
        if (thinking) thinkingIcon?.start() else thinkingIcon?.stop()
        root.toolRow?.let { row -> row.visibility = if (tool) View.VISIBLE else View.GONE }
        if (tool) {
            toolTextView?.setTextColor(GlassesPanelSpec.toolTextColor(model.toolPhase ?: ToolPhase.START))
            if (model.toolPhase == ToolPhase.END) toolIcon?.stop() else toolIcon?.start()
        } else {
            toolIcon?.stop()
        }
        orbView.setState(orb)
        tipsView.text = model.tips
        pinTextView.visibility = if (pinned) View.GONE else View.VISIBLE
        pinIconView.visibility = if (pinned) View.VISIBLE else View.GONE
        Log.i(TAG,
            "P3_STATE route=${route.name} busy=${listening || thinking || (live && !model.idle)} " +
                "any=${!model.idle} live=$live orb=${orb.name} listening=$listening hi=$hi thinking=$thinking " +
                "tool=$tool status=${model.status?.name ?: "none"} idle=${model.idle} pinned=$pinned " +
                "offset=${scrollOffsetY()} max=${maxScrollY()} ticker=${tickerAnimator != null}")
    }

    /** The orb state from the one model; the pure priority rule lives in `orbStateOf` so it is JVM-testable. */
    private fun orbStateFor(model: PanelModel): OrbState = orbStateOf(model)

    /** Rebuilds the conversation list from the per-turn items; only a structural change takes this path. */
    private fun rebuild(model: PanelModel, turns: List<ConversationView.TurnRow>) {
        val previousScroll = scrollOffsetY()
        entriesView.removeAllViews()
        root.setLiveRow(null)
        thinkingIcon = null
        thinkingTextView = null
        toolIcon = null
        toolTextView = null
        root.setThinkingRow(null)
        root.setToolRow(null)
        renderedTurnViews = turns.map { turn -> addTurn(turn) }
        renderedTurns = turns
        Log.i(TAG, HintPresentation.conversationReport(
            model.route,
            turns.size,
            turns.sumOf { (it.asrText?.length ?: 0) + (it.answerText?.length ?: 0) },
        ))
        applyVisualState(model)
        revealCoalescer.reset()
        scheduleAfterContent(force = true, previousScroll = previousScroll)
    }

    /**
     * Applies a text/style-only model to the rows already on screen (0.24 S4 Task 17).
     *
     * This is the streaming path: an `ASR`/`ANSWER` revision (and the final `SHOW`, which replaces the same
     * `say-${hintId}` row) changes one `TextView`'s text, so the other rows keep their objects, their scroll position
     * and their selection. Only [plan]'s [ConversationView.RenderPlan.Update] reaches this method.
     */
    private fun updateRows(model: PanelModel, plan: ConversationView.RenderPlan.Update) {
        val previousScroll = scrollOffsetY()
        plan.turns.forEachIndexed { index, turn ->
            val views = renderedTurnViews.getOrNull(index) ?: return@forEachIndexed
            views.asr?.let { view ->
                if (view.text.toString() != turn.asrText) view.text = turn.asrText
                view.setTextColor(turn.asrTextColor)
            }
            views.answer?.let { view ->
                if (view.text.toString() != turn.answerText) view.text = turn.answerText
            }
            views.toolText?.let { label ->
                if (label.text.toString() != turn.toolLabel) label.text = turn.toolLabel
                label.setTextColor(turn.toolTextColor)
                if (turn.toolPhase == ToolPhase.END) views.toolIcon?.stop() else views.toolIcon?.start()
            }
        }
        renderedTurns = plan.turns
        val liveIndex = plan.turns.indexOfFirst { it.answerLive }
        root.setLiveRow(if (liveIndex >= 0) renderedTurnViews.getOrNull(liveIndex)?.answer else null)
        applyVisualState(model)
        scheduleAfterContent(force = plan.liveChanged, previousScroll = previousScroll)
        Log.i(TAG, "P3_PATCH turns=${plan.turns.size} text=${plan.textChanged} live=${plan.liveChanged}")
    }

    /**
     * Posts the expensive reveal/scroll/layout pass.
     *
     * Text is always applied synchronously before this; the pass is coalesced to the official 50 ms cadence so a delta
     * burst cannot queue one reveal per revision. A structural rebuild and the final `SHOW` force the pass through.
     */
    private fun scheduleAfterContent(force: Boolean, previousScroll: Int = scrollOffsetY()) {
        val now = System.nanoTime()
        if (force) revealCoalescer.reset()
        if (!revealCoalescer.allow(now)) return
        val serial = ++rebuildSerial
        entriesView.post {
            if (serial != rebuildSerial || destroyed) return@post
            val max = maxScrollY()
            if (newEntryPending) {
                stopTicker()
                val top = (lastMessageRowForProbe()?.top ?: 0).coerceIn(0, max)
                hintScroll.scrollTo(0, top)
                Log.i(TAG, "P4_REVEAL top=$top offset=${scrollOffsetY()} max=$max")
                userReadPaused = false
                newEntryPending = false
            } else {
                hintScroll.scrollTo(0, previousScroll.coerceIn(0, max))
            }
            updateScrollChrome()
            startTickerIfNeeded()
            root.liveRow?.let { live ->
                Log.i(TAG, HintPresentation.renderReport(live.lineCount, live.text.length, max))
            }
        }
    }

    /** Starts the official ticker when there is somewhere to go and neither the wearer nor the host stopped it. */
    private fun startTickerIfNeeded() {
        if (tickerAnimator != null) return
        if (!HintTicker.shouldRun(
                hasOverflow = HintTicker.shouldScroll(scrollOffsetY(), maxScrollY()),
                userPaused = userReadPaused,
                paused = !resumed,
            )
        ) {
            return
        }
        tickerStartOffset = scrollOffsetY()
        tickerFrames = 0
        tickerAnimator = android.animation.ValueAnimator.ofInt(0, Int.MAX_VALUE).apply {
            duration = Int.MAX_VALUE.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                tickerFrames++
                val next = HintTicker.offsetAfterFrames(tickerStartOffset, tickerFrames, maxScrollY())
                val delta = next - scrollOffsetY()
                if (delta > 0) hintScroll.scrollBy(0, delta)
                if (tickerFrames % 30 == 0) {
                    Log.i(TAG, "P4_TICKER frames=$tickerFrames offset=${scrollOffsetY()} max=${maxScrollY()}")
                }
                if (!HintTicker.shouldScroll(scrollOffsetY(), maxScrollY())) {
                    Log.i(TAG, "P4_TICKER_STOP offset=${scrollOffsetY()} max=${maxScrollY()}")
                    stopTicker()
                }
            }
            start()
        }
    }

    private fun stopTicker() {
        tickerAnimator?.cancel()
        tickerAnimator = null
        tickerFrames = 0
    }

    /** One page key: `min(remaining, viewport * 0.85)`, and the ticker stops. */
    private fun pageBy(direction: Int) {
        stopTicker()
        userReadPaused = true
        val viewport = hintScroll.height - hintScroll.paddingTop - hintScroll.paddingBottom
        val remaining = if (direction > 0) maxScrollY() - scrollOffsetY() else scrollOffsetY()
        val step = HintTicker.pageStepPx(remaining, viewport.coerceAtLeast(0))
        if (step <= 0) return
        hintScroll.smoothScrollBy(0, direction * step)
        Log.i(TAG, "P4_PAGE direction=$direction step=$step offset=${scrollOffsetY()} max=${maxScrollY()}")
    }

    /** The bottom fade is visible only while the list has more content below. */
    private fun updateScrollChrome() {
        bottomMask.visibility =
            if (HintTicker.shouldScroll(scrollOffsetY(), maxScrollY())) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- per-turn rows (0.24 S4 Task 17)

    /** One turn's views, kept so a text-only update patches the objects already on screen. */
    private class TurnViews(
        val asr: TextView?,
        val answer: TextView?,
        val thinking: LinearLayout?,
        val tool: LinearLayout?,
        val toolIcon: LoadingIndicatorView?,
        val toolText: TextView?,
    )

    /**
     * Adds one turn's item in the official order (ASR line, answer line, thinking row, tool row) and returns its views.
     * A `null` field means the row does not exist for this turn, so a later update can never patch the wrong view.
     */
    private fun addTurn(turn: ConversationView.TurnRow): TurnViews {
        val asr = turn.asrText?.let { text ->
            userRow(text, answered = turn.answerText != null).also { entriesView.addView(it) }
        }
        val answer = turn.answerText?.let { text ->
            answerRow(text).also { view ->
                entriesView.addView(view)
                if (turn.answerLive) root.setLiveRow(view)
            }
        }
        val thinking = if (turn.thinkingVisible) thinkingRow().also { entriesView.addView(it) } else null
        var toolRowView: LinearLayout? = null
        var toolIconView: LoadingIndicatorView? = null
        var toolTextViewView: TextView? = null
        if (turn.toolLabel != null && turn.toolPhase != null) {
            val built = toolRow(turn.toolLabel, turn.toolPhase)
            toolRowView = built.third
            toolIconView = built.first
            toolTextViewView = built.second
            entriesView.addView(toolRowView)
        }
        return TurnViews(asr, answer, thinking, toolRowView, toolIconView, toolTextViewView)
    }

    /** The wearer's line: 14 px, full green while recognising, 70% once this turn's answer exists. */
    private fun userRow(text: String, answered: Boolean): TextView =
        TailTextView(context, maxVisibleLines = GlassesPanelSpec.USER_MAX_LINES).apply {
            this.text = text
            setTextColor(if (answered) GlassesPanelSpec.GREEN_NORMAL_70 else GlassesPanelSpec.GREEN_NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.USER_TEXT_SIZE_PX)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, rowVerticalPadding(), 0, rowVerticalPadding())
        }

    /** The answer line: 16 px full green. Markdown waits for Task 19; the row identity is what Task 17 fixes. */
    private fun answerRow(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(GlassesPanelSpec.GREEN_NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.ASSISTANT_TEXT_SIZE_PX)
        maxLines = GlassesPanelSpec.ASSISTANT_MAX_LINES
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setPadding(0, rowVerticalPadding(), 0, rowVerticalPadding())
    }

    /** The official 4 dp row padding used by every conversation line. */
    private fun rowVerticalPadding(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics).toInt()

    /**
     * The tool row: the official 22x22 icon slot plus the 16 px mapped copy, full green while running and 50% once the
     * action returned (spec §5.6). The icon is the existing `LoadingIndicatorView` in that slot until Task 19 copies the
     * official `ai_agent_tool_start/end` GIFs; the row geometry and copy rules are what this task fixes.
     */
    private fun toolRow(label: String, phase: ToolPhase): Triple<LoadingIndicatorView, TextView, LinearLayout> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, rowVerticalPadding(), 0, rowVerticalPadding())
        }
        val icon = LoadingIndicatorView(context, dotRadiusPx = 2f, colorInt = GlassesPanelSpec.GREEN_NORMAL)
        row.addView(icon, LinearLayout.LayoutParams(
            GlassesPanelSpec.TOOL_ICON_PX, GlassesPanelSpec.TOOL_ICON_PX))
        val text = TextView(context).apply {
            this.text = label
            setTextColor(GlassesPanelSpec.toolTextColor(phase))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.TOOL_TEXT_SIZE_PX)
            gravity = Gravity.START
        }
        row.addView(text, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 5 })
        if (phase == ToolPhase.START) icon.start() else icon.stop()
        toolIcon = icon
        toolTextView = text
        root.setToolRow(row)
        return Triple(icon, text, row)
    }

    /** The `正在思考` row: the official 22x22 icon plus 16 px full-green copy, as the last line. */
    private fun thinkingRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val icon = LoadingIndicatorView(context, dotRadiusPx = 2f, colorInt = GlassesPanelSpec.GREEN_NORMAL)
        addView(icon, LinearLayout.LayoutParams(
            GlassesPanelSpec.THINKING_ICON_PX, GlassesPanelSpec.THINKING_ICON_PX))
        val label = TextView(context).apply {
            text = GlassesPanelSpec.THINKING_TEXT
            setTextColor(GlassesPanelSpec.GREEN_NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.THINKING_TEXT_SIZE_PX)
            gravity = Gravity.START
        }
        addView(label, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 5 })
        thinkingIcon = icon
        root.setThinkingRow(this)
        thinkingTextView = label
    }

    /** The one outbound seam for the controller's traffic (ACK/PONG/STOP). */
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

    private companion object {
        const val TAG = "EdithGlasses"
    }
}
