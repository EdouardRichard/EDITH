package com.sheen.rokid.glasses.app
import com.sheen.rokid.glasses.display.BusyState
import com.sheen.rokid.glasses.display.HintPresentation
import com.sheen.rokid.glasses.display.HintTicker
import com.sheen.rokid.glasses.display.ConversationView
import com.sheen.rokid.glasses.display.SaidSequenceGate
import com.sheen.rokid.glasses.display.ConversationOrder
import com.sheen.rokid.glasses.bridge.GlassClockResponder
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
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.DisplayedHint
import com.sheen.rokid.output.GlassHintController
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
 * The phone never learns whether a hint became visible, so the last line of defence lives here: [GlassHintController]
 * refuses a `SHOW` for another session, an older revision, a sequence behind a `CLEAR` fence, an unknown or stale
 * clock mapping, or a deadline that has already passed - and it expires the text locally, because the phone may stop
 * sending `CLEAR` and must not be relied upon to deliver it.
 *
 * Everything arrives on the bridge's callback thread, so commands are applied on the UI thread. That is what makes the
 * acknowledgement honest: `GlassHintController` reports `Displayed` only after the render callback has committed, and
 * a view update that had merely been *posted* would have acknowledged something the wearer could not see yet.
 *
 * **0.23 P3/P4 changed what this screen is.** It shows one **conversation** at a time, every line left-aligned
 * (including the wearer's own), with the official bottom panel and a 1 px/frame ticker; the route is chosen by `SHOW`,
 * and `ENTER` pins the reading position. The live hint is not emphasised - it is simply the newest row; retiring it
 * does not erase anything (`render(null)` means "nothing is live now").
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

    /** Which route is in front of the wearer, and whether the wearer pinned the reading position (`KEYCODE_ENTER`). */
    private val order = ConversationOrder()

    /** Rejects an out-of-order `SAID`; the custom-command channel promises nothing about ordering. */
    private val saidGate = SaidSequenceGate()

    /** Whether the assistant is working, per route (Phase C 加载动画); bounded so a leaked level cannot spin forever. */
    private val busyState = BusyState()

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

    /** The entry id of the live hint, so exactly one row is emphasised. */
    private var liveEntryId: String? = null

    /**
     * 0.23 P4: one entry arrived since the last rebuild, so the rebuild must bring it into view before the ticker
     * starts. This is the "new content first" rule - it applies while pinned as well, because a pin is a reading
     * position, not a reason for the next answer to stay hidden.
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

    /** Whether a deadline is already armed for the current busy spell; see [applyPanelAndAnimation]. */
    private var busyDeadlineArmed = false

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
        // The endpoint outlives this Activity, so it is given a listener rather than a reference to it.
        RokidBridgeEndpoint.instance.setListener { json -> acceptCommand(json) }
    }

    /** Keeps the display awake only while something is being shown; the wake lock itself lives in [holdAwakeFor]. */
    override fun onResume() {
        super.onResume()
        resumed = true
        applyPanelAndAnimation()
        // `applyPanelAndAnimation` restores the state row; the scroll needs its own start because `applyVisualState`
        // deliberately does not touch the ticker.
        startTickerIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // Leaving the foreground ends the visible hint's claim on the panel; the controller clears the text too.
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
     * **Busy, with an established session identity**: send `STOP` to the phone and keep the window open - the phone
     * cancels the turn/proactive request and broadcasts `BUSY=false`, which puts the thinking row and orb back to
     * idle. **Idle, or busy with no identity to name**: exit, exactly as the wearer expects from a back key.
     *
     * Handled here rather than in `installSideTouch`: an Activity consumes BACK through its default key path before an
     * unhandled-key listener can see it (the official app handles it in a `Dialog.onKeyUp` instead). Returning true
     * stops the framework from also calling `onBackPressed()`.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // A cancelled gesture (the window lost focus mid-press) is not a wearer decision. Consume it without
            // finishing so a lost focus cannot close the panel.
            if (event.isCanceled) return true
            val identity = controller.currentIdentity()
            val busy = busyState.any(clock.nowNanos())
            if (busy && identity != null) {
                // A malformed identity/encoder failure must fall through to the exit branch rather than throw on the
                // UI thread. `encode` is total for a decoded HELLO, but this is the one path a wearer uses to get out.
                val sent = runCatching {
                    val json = HintWireCodec.encode(HintCommand.Stop(identity.first, identity.second))
                    stopSenderForProbe?.invoke(json) ?: RokidBridgeEndpoint.instance.send(json)
                }.getOrDefault(false)
                if (sent) {
                    lastBackAction = "stop"
                    lastBackStopSent = true
                    android.util.Log.i("EdithGlasses",
                        "P5_GLASS_BACK action=stop sent=true session=${identity.first} " +
                            "generation=${identity.second}")
                    return true
                }
                // The link is dead, so the STOP cannot reach the phone. Keeping the window open would trap the wearer
                // until the local 60 s busy bound expires; a STOP that was not sent is not an interrupt.
                lastBackAction = "exit"
                lastBackStopSent = false
                android.util.Log.i("EdithGlasses",
                    "P5_GLASS_BACK action=exit reason=stop_unsent busy=true identity=true")
                finish()
                return true
            }
            lastBackAction = "exit"
            lastBackStopSent = null
            android.util.Log.i("EdithGlasses",
                "P5_GLASS_BACK action=exit busy=$busy identity=${identity != null}")
            finish()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        // Leaving the screen must never leave text on the glasses, and the endpoint must not keep a dead Activity.
        runCatching { controller.disconnect() }
        holdAwakeFor(false)
        // The conversation is session state: a destroyed Activity must not hand its words to the next one.
        log.clear()
        order.reset()
        saidGate.reset()
        busyState.reset()
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
     */
    fun acceptCommand(json: String): Boolean {
        val command = HintWireCodec.decode(json) ?: run {
            // Recorded because this is the mixed-build safety net: an old glasses APK silently drops every command it
            // does not know, and without a line here "the wearer saw nothing" has no cause on this side either. The
            // length is a count, never the payload (the same rule as `HintPresentation.inboundReport`).
            android.util.Log.i("EdithGlasses", "inbound undecodable chars=${json.length}")
            return false
        }
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

                // Phase C: whether the assistant is working. Same session gate as a SHOW, because a stale level would
                // light the ring for a session that has ended.
                is HintCommand.Busy -> onBusy(command)

                // A **new** session starts clean. Only a new one: the phone repeats its opening HELLO while it waits
                // for the clock exchange (up to five times), and clearing on every HELLO wiped a conversation whose
                // utterances had already arrived - the wearer's own words disappearing from the glasses while the
                // phone still showed them.
                is HintCommand.Hello -> {
                    if (!controller.isCurrent(command.sessionId, command.generation)) {
                        stopTicker()
                        userReadPaused = false
                        newEntryPending = false
                        log.clear()
                        order.reset()
                        saidGate.reset()
                        busyState.reset()
                        liveEntryId = null
                        panel.setLiveRow(null)
                    }
                    controller.accept(command)
                    applyPanelAndAnimation()
                    rebuild()
                }

                else -> controller.accept(command)
            }
        }
        return true
    }

    /** The hint text as the wearer sees it, exposed so an instrumentation test can assert the committed view. */
    fun hintText(): String = panel.liveRow?.text?.toString().orEmpty()

    /** Everything said in one conversation, one entry per line - the probe's view of [ConversationLog]. */
    fun conversationText(route: AssistantRoute): String =
        log.entries(route).joinToString("\n") { it.text }

    /** Which conversation is in front of the wearer, for the same reason. */
    fun selectedRoute(): AssistantRoute = order.selected()

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
            panel.setLiveRow(null)
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
            // A new entry has to be brought into view whatever the wearer was doing with the scroll: the pin exists
            // so the continuous ticker never fights someone reading, not so that the next answer stays hidden (P4).
            newEntryPending = true
        }
        holdAwakeFor(liveEntryId != null)
        // The panel has two claimants, so it is resolved in one place: calling `holdAwakeFor` directly here released
        // the wake lock a still-running proactive turn was holding (a hint expiring mid-turn is the ordinary case).
        applyPanelAndAnimation()
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
        // A SAID for the route that is not on screen is recorded but must not move the current view: only a SHOW
        // changes the route (U8), and its rebuild will bring the updated conversation in then.
        if (command.route == order.selected()) {
            if (stored) newEntryPending = true
            rebuild()
        }
    }

    /**
     * Records the level the phone stated and puts the ring and the panel in line with it.
     *
     * Must run on the UI thread. The level is bounded in [BusyState], so a "finished" that never arrives stops by
     * itself rather than holding the ring and the wake lock for the life of the process.
     */
    private fun onBusy(command: HintCommand.Busy) {
        if (!controller.isCurrent(command.sessionId, command.generation)) {
            android.util.Log.i("EdithGlasses", "busy refused: session or generation mismatch")
            return
        }
        busyState.set(command.route, command.busy, clock.nowNanos())
        android.util.Log.i("EdithGlasses",
            "busy route=${command.route.name} stated=${command.busy} any=${busyState.any(clock.nowNanos())}")
        applyPanelAndAnimation()
        rebuild()
    }

    /**
     * The single place that decides whether the panel is held and whether the ring turns.
     *
     * Two claimants share the panel — a live hint and a working turn — and they must be resolved in one place: two
     * separate `holdAwakeFor` callers would release each other's claim, and the panel would go dark mid-answer.
     *
     * **The bound is armed, not merely computed.** `BusyState` answers "is it still busy" from the clock, but nothing
     * asks it again once the phone stops talking — and the case the bound exists for is exactly that one (the phone
     * died mid-turn, or the link dropped). Without a scheduled re-check the ring would turn and
     * `FLAG_KEEP_SCREEN_ON` would stay set for the life of this process, on a device whose wearer cannot switch the
     * display off from the app. So raising a level arms a deadline, and the deadline re-runs this method.
     */
    private fun applyPanelAndAnimation() {
        // A command can arrive while paused; it must not re-acquire the wake lock or start animators behind a blank
        // display. `onResume` applies the state again once the screen is back.
        if (!resumed) {
            holdAwakeFor(false)
            return
        }
        val now = clock.nowNanos()
        val busy = busyState.any(now)
        // 0.23 P3: the whole-window loading ring is gone. The orb is the working indicator, and its state (idle /
        // listening / thinking / answering) is applied by `applyVisualState` from the same busy level.
        applyVisualState()
        holdAwakeFor(busy || liveEntryId != null)
        if (!busy) return
        // One armed deadline per busy spell: a repeat of the same level must not keep pushing it back.
        if (busyDeadlineArmed) return
        busyDeadlineArmed = true
        scheduler.at(now + BusyState.MAX_MILLIS * 1_000_000L) {
            busyDeadlineArmed = false
            applyPanelAndAnimation()
            rebuild()
        }
    }

    /**
     * The official panel's state row, computed in one place so the listening line, the thinking row, the orb and the
     * tips cannot disagree with each other.
     *
     * The busy boolean is **per displayed route** (`isBusy(selected)`): a proactive turn must not make the wake
     * conversation look busy, and the other way round (0.23 P3/P4 route isolation). The tips and BACK use `any`,
     * because either route working is something the wearer can interrupt.
     */
    private fun applyVisualState() {
        if (!::panel.isInitialized) return
        // A command can arrive while the screen is paused (the bridge outlives the Activity); it must not restart the
        // orb or the ticker behind a blank display. `onResume` applies the state once the screen is back.
        if (!resumed) return
        val now = clock.nowNanos()
        val route = order.selected()
        val live = liveEntryId != null
        val busyForRoute = busyState.isBusy(route, now)
        val entries = log.entries(route)
        val lastRole = entries.lastOrNull()?.role
        val listening = GlassesPanelSpec.listeningVisible(busyForRoute, live, route, lastRole)
        val thinking = GlassesPanelSpec.thinkingVisible(busyForRoute, live, route, lastRole)

        listeningView.visibility = if (listening) View.VISIBLE else View.GONE
        panel.thinkingRow?.let { row -> row.visibility = if (thinking) View.VISIBLE else View.GONE }
        if (thinking) thinkingIcon?.start() else thinkingIcon?.stop()
        orbView.setState(GlassesPanelSpec.orbState(busyForRoute, live, route, lastRole))
        tipsView.text = GlassesPanelSpec.tipsText(busyState.any(now))
        val pinned = order.isPinned()
        pinTextView.visibility = if (pinned) View.GONE else View.VISIBLE
        pinIconView.visibility = if (pinned) View.VISIBLE else View.GONE
        android.util.Log.i("EdithGlasses",
            "P3_STATE route=${route.name} busy=$busyForRoute any=${busyState.any(now)} live=$live " +
                "orb=${GlassesPanelSpec.orbState(busyForRoute, live, route, lastRole).name} " +
                "listening=$listening thinking=$thinking pinned=$pinned " +
                "offset=${scrollOffsetY()} max=${maxScrollY()} ticker=${tickerAnimator != null}")
    }

    /**
     * Redraws the conversation in front of the wearer.
     *
     * The list is rebuilt rather than patched: it is bounded at tens of entries, and a rebuild is what makes "the view
     * shows the log" true by construction instead of by careful incremental updates.
     */
    private fun rebuild() {
        val route = order.selected()
        val rows = ConversationView.rows(log.entries(route), liveEntryId)
        val now = clock.nowNanos()
        // `removeAllViews` can clamp the scroll to zero, so the position to restore is read before the rebuild.
        val previousScroll = scrollOffsetY()
        entriesView.removeAllViews()
        panel.setLiveRow(null)
        thinkingIcon = null
        panel.setThinkingRow(null)
        thinkingTextView = null
        rows.forEach { row -> entriesView.addView(entryRow(row)) }
        val busyForRoute = busyState.isBusy(route, now)
        val lastRole = log.entries(route).lastOrNull()?.role
        if (GlassesPanelSpec.thinkingVisible(busyForRoute, liveEntryId != null, route, lastRole)) {
            entriesView.addView(thinkingRow())
        }
        android.util.Log.i("EdithGlasses",
            HintPresentation.conversationReport(route, rows.size, rows.sumOf { it.entry.text.length }))
        applyVisualState()
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
        if (order.isPinned() || userReadPaused) return
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

    private fun acknowledge(ack: HintCommand.Ack) {
        RokidBridgeEndpoint.instance.send(HintWireCodec.encode(ack))
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
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    pageBy(1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    pageBy(-1)
                    true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    val pinned = order.togglePin()
                    android.util.Log.i("EdithGlasses", "side touch: pinned=$pinned")
                    if (pinned) {
                        stopTicker()
                    } else {
                        userReadPaused = false
                        startTickerIfNeeded()
                    }
                    applyVisualState()
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

    fun isPinnedForProbe(): Boolean = order.isPinned()

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
