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
import com.sheen.rokid.glasses.display.TailTextView
import com.sheen.rokid.glasses.display.VoiceOrbView
import com.sheen.rokid.output.ConversationRole
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
     * Installs the side-touch key channel on [touchRoot].
     *
     * The Activity passes its content view (the official sample registers there); the overlay service passes its
     * focusable window root. Only `ACTION_DOWN` is handled, as the official sample warns, and `true` stops one gesture
     * from being acted on twice.
     */
    fun installSideTouch(touchRoot: View) {
        touchRoot.addOnUnhandledKeyEventListener(View.OnUnhandledKeyEventListener { _, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnUnhandledKeyEventListener false
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
     * The Activity's BACK key path. The controller decides; a cancelled gesture (a lost focus mid-press) is not a
     * wearer decision and is consumed without ending anything.
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.isCanceled) return true
            return controller.onKey(keyCode, event.action)
        }
        return false
    }

    /**
     * The overlay window's full key path.
     *
     * The overlay is the focused window, so it receives the side keys directly (S1 proved all six arrive). BACK is
     * handled on UP like the Activity does, and its DOWN is consumed so the system never interprets it as leaving the
     * focused window; ENTER/pin and the four page directions are handled on DOWN.
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) return true
            if (event.action == KeyEvent.ACTION_UP) {
                if (event.isCanceled) return true
                return controller.onKey(event.keyCode, event.action)
            }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (controller.onKey(event.keyCode, event.action)) return true
        return when (event.keyCode) {
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

        if (previous != null && previous.pinned != model.pinned) {
            if (model.pinned) stopTicker() else userReadPaused = false
        }

        if (!resumed) {
            callbacks.keepAwake(false)
        } else if (wakePolicy == WakePolicy.PANEL_VISIBLE) {
            callbacks.keepAwake(true)
        } else {
            callbacks.keepAwake(!model.idle || model.liveEntryId != null)
        }
        rebuild(model)
    }

    override fun wakeScreen() {
        callbacks.keepAwake(true)
    }

    /** The service sends PANEL_SHOWN after the overlay's first layout; there is no controller caller yet. */
    override fun sendPanelShown() = Unit

    /** The service sends UI_CLOSED after the overlay is removed; there is no controller caller yet. */
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
        root.setThinkingRow(null)
        root.setLiveRow(null)
    }

    // ---------------------------------------------------------------- probes (the existing instrumentation contract)

    fun hintText(): String = root.liveRow?.text?.toString().orEmpty()

    fun conversationText(route: AssistantRoute): String = controller.conversationText(route)

    fun selectedRoute(): AssistantRoute = model?.route ?: AssistantRoute.WAKE

    fun currentIdentity(): Pair<String, Long>? = controller.currentIdentity()

    fun contentRevisionForProbe(): Long = model?.contentRevision ?: 0L

    fun orbStateForProbe(): OrbState = orbView.state()

    fun listeningVisibleForProbe(): Boolean = listeningView.visibility == View.VISIBLE

    fun thinkingVisibleForProbe(): Boolean = root.thinkingRow?.visibility == View.VISIBLE

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
        val thinking = model.thinkingVisible
        val pinned = model.pinned
        val orb = orbStateFor(model)

        listeningView.visibility = if (model.hiVisible) View.VISIBLE else View.GONE
        root.thinkingRow?.let { row -> row.visibility = if (thinking) View.VISIBLE else View.GONE }
        if (thinking) thinkingIcon?.start() else thinkingIcon?.stop()
        orbView.setState(orb)
        tipsView.text = model.tips
        pinTextView.visibility = if (pinned) View.GONE else View.VISIBLE
        pinIconView.visibility = if (pinned) View.VISIBLE else View.GONE
        Log.i(TAG,
            "P3_STATE route=${route.name} busy=${listening || thinking || (live && !model.idle)} " +
                "any=${!model.idle} live=$live orb=${orb.name} listening=$listening thinking=$thinking " +
                "pinned=$pinned offset=${scrollOffsetY()} max=${maxScrollY()} ticker=${tickerAnimator != null}")
    }

    /** The orb state from the one model; same priority order as before the extraction. */
    private fun orbStateFor(model: PanelModel): OrbState = when {
        model.liveEntryId != null -> OrbState.ANSWERING
        model.listeningVisible -> OrbState.LISTENING
        model.thinkingVisible -> OrbState.THINKING
        else -> OrbState.IDLE
    }

    /** Redraws the conversation in front of the wearer from the published model. */
    private fun rebuild(model: PanelModel) {
        val route = model.route
        val rows = ConversationView.rows(model.entries, model.liveEntryId)
        val previousScroll = scrollOffsetY()
        entriesView.removeAllViews()
        root.setLiveRow(null)
        thinkingIcon = null
        root.setThinkingRow(null)
        thinkingTextView = null
        rows.forEach { row -> entriesView.addView(entryRow(row)) }
        if (model.thinkingVisible) {
            entriesView.addView(thinkingRow())
        }
        Log.i(TAG, HintPresentation.conversationReport(route, rows.size, rows.sumOf { it.entry.text.length }))
        applyVisualState(model)
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

    /** Starts the official ticker when there is somewhere to go and the wearer has not stopped it. */
    private fun startTickerIfNeeded() {
        if (!resumed) return
        if (tickerAnimator != null) return
        if (model?.pinned == true || userReadPaused) return
        if (!HintTicker.shouldScroll(scrollOffsetY(), maxScrollY())) return
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

    /** One row of the conversation, in the official style (same rules as the pre-extraction Activity). */
    private fun entryRow(row: ConversationView.Row): TextView {
        val role = row.entry.role
        val view: TextView = if (role == ConversationRole.USER) {
            TailTextView(context, maxVisibleLines = GlassesPanelSpec.maxLines(ConversationRole.USER))
        } else {
            TextView(context)
        }
        view.apply {
            text = row.entry.text
            setTextColor(GlassesPanelSpec.textColor(role))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.textSizePx(role))
            if (role != ConversationRole.USER) {
                maxLines = GlassesPanelSpec.maxLines(role)
            }
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val vertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics).toInt()
            setPadding(0, vertical, 0, vertical)
            if (row.live) root.setLiveRow(this)
        }
        return view
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
