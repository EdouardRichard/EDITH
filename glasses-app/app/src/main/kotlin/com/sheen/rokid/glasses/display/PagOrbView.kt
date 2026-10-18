package com.sheen.rokid.glasses.display

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import com.sheen.rokid.output.OfficialStatus
import org.libpag.PAGFile
import org.libpag.PAGView

/**
 * 0.24 S4 Task 18 (spec §3.4, D159): the official 55×45 PAG voice orb.
 *
 * The official `AiAssistantDialog` owns one `id_pv` `PAGView` and drives nine `.pag` clips through it: a status asks
 * for the next clip, then every `PAGView.onAnimationEnd` uses the view's **tag** to pick the following one. This class
 * is that loop. The pure table and tag gates live in [OrbPagChain] so the chain fails a JVM test; this class only loads
 * the binaries, owns the `PAGView`, and guarantees the lifecycle:
 *
 * - `setStatus` / `setIdle` translate the panel's model into one clip request. A request that the current tag does not
 *   allow is ignored - the table is a target chain, not a forced jump (spec §3.4).
 * - `first`/`last` clips play once and `middle` clips loop (`repeatCount` 1/0, the official values).
 * - `start`/`stop` are the host window's visibility: a stopped once clip resumes from where it paused. A stopped
 *   middle resumes too; an ended tail does not replay just because the window came back.
 * - If libpag cannot create the view or load a clip, the PAG view is replaced by the existing [VoiceOrbView] and
 *   `PAG_LOAD_FAILED` is logged. The fallback only keeps the panel usable; it is not a claim to reproduce the PAG.
 *
 * There is no `postDelayed` anywhere: every transition is the renderer's own end callback, exactly like the official
 * code (spec §3.4).
 */
class PagOrbView internal constructor(
    context: Context,
    /** Asset prefix; the production constructor fixes it to `pag/`. Test-only so a missing-clip fallback is testable. */
    private val assetRoot: String,
) : FrameLayout(context), PAGView.PAGViewListener {

    constructor(context: Context) : this(context, ASSET_ROOT)

    /** The libpag view, or null when this device/ABI cannot run libpag and [fallbackView] is used instead. */
    private val pagView: PAGView?

    /** The pre-Task-18 drawing, created only after a PAG failure. */
    private var fallbackView: VoiceOrbView? = null

    /** Official `id_pv` tag (`none` before the first clip, then the official tag names in [OrbPagChain]). */
    @Volatile
    private var tag: String = OrbPagChain.TAG_NONE

    /** The asset currently loaded, or null before the first successful load. */
    @Volatile
    private var segmentAsset: String? = null

    /** The current segment's libpag repeat count; -1 before the first load. */
    @Volatile
    private var repeatCount: Int = -1

    /** True while the host window is visible; a load while hidden does not play until [start]. */
    @Volatile
    private var running: Boolean = false

    /** Whether the current clip is playing (mirrors libpag's own `isPlaying`). */
    @Volatile
    private var playing: Boolean = false

    /** The current once-clip reached its end and nothing followed; a resume must not replay it. */
    @Volatile
    private var ended: Boolean = false

    /** The four-state probe shape, kept for the existing panel probes and the fallback view. */
    @Volatile
    private var orbState: OrbState = OrbState.IDLE

    /**
     * The last request applied (a status, a legacy [OrbState], or the idle marker). Repeated renders publish the same
     * status; without this a status 5 re-render would restart `finish_last` every time.
     */
    private var lastRequest: Any? = null

    /** The loaded clips in order, so the device test can assert an end-to-end chain rather than poll a race. */
    private val history = ArrayDeque<String>()

    init {
        val created = runCatching { PAGView(context) }.getOrNull()
        pagView = created
        if (created == null) {
            Log.e(TAG, "PAG_LOAD_FAILED view=init")
            installFallback()
        } else {
            addView(created, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            created.addListener(this)
        }
    }

    // ---------------------------------------------------------------- panel contract

    /** The official status entry for [status]. A duplicate status is a no-op (the tag gate anyway refuses a reload). */
    fun setStatus(status: OfficialStatus) {
        orbState = GlassesPanelSpec.orbStateForStatus(status)
        fallbackView?.setState(orbState)
        if (lastRequest == status) return
        lastRequest = status
        apply(OrbPagChain.entryFor(status))
    }

    /** Official `showIdleStatus`: play the finish tail and hold its last frame. */
    fun setIdle() {
        orbState = OrbState.IDLE
        fallbackView?.setState(OrbState.IDLE)
        if (lastRequest === IDLE_REQUEST) return
        lastRequest = IDLE_REQUEST
        apply(OrbPagChain.idleEntry())
    }

    /**
     * The legacy busy-level path (an old phone with no `STATE`/`IDLE`). It asks for the same segment the equivalent
     * official status would; no new visual rule is introduced.
     */
    fun setState(state: OrbState) {
        orbState = state
        fallbackView?.setState(state)
        if (lastRequest == state) return
        if (state == OrbState.IDLE) {
            lastRequest = IDLE_REQUEST
            apply(OrbPagChain.idleEntry())
            return
        }
        lastRequest = state
        val status = when (state) {
            OrbState.LISTENING -> OfficialStatus.LISTENING
            OrbState.THINKING -> OfficialStatus.ASR_END
            OrbState.ANSWERING -> OfficialStatus.ANSWERING
            OrbState.IDLE -> OfficialStatus.AUDIO_FINISHED
        }
        apply(OrbPagChain.entryFor(status))
    }

    /** The host window is visible: resume a paused clip (an ended tail deliberately stays ended). */
    fun start() {
        running = true
        val fallback = fallbackView
        if (fallback != null) {
            fallback.start()
            return
        }
        val pag = pagView ?: return
        if (segmentAsset != null && !playing && !ended) {
            pag.play()
            playing = true
            Log.i(TAG, "PAG_RESUME tag=$tag")
        }
    }

    /** The host window is hidden: stop the frame loop; libpag pauses, so [start] can resume. */
    fun stop() {
        running = false
        playing = false
        pagView?.stop()
        fallbackView?.stop()
    }

    fun state(): OrbState = orbState

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- PAGView.PAGViewListener

    override fun onAnimationStart(view: PAGView) = Unit

    /**
     * The official chain. It is called on the UI thread by libpag; the tag written by [load] is what selects the next
     * clip, exactly as `AiAssistantDialog`'s listener does.
     */
    override fun onAnimationEnd(view: PAGView) {
        if (view !== pagView) return
        playing = false
        val next = OrbPagChain.nextOnEnd(tag)
        Log.i(TAG, "PAG_END tag=$tag next=${next?.asset ?: "stop"}")
        if (next == null) {
            ended = true
            return
        }
        apply(next)
    }

    override fun onAnimationCancel(view: PAGView) {
        if (view !== pagView) return
        playing = false
    }

    override fun onAnimationRepeat(view: PAGView) = Unit

    override fun onAnimationUpdate(view: PAGView) = Unit

    // ---------------------------------------------------------------- loading

    /** Applies one segment request through the official tag gate. */
    private fun apply(segment: OrbPagChain.Segment) {
        if (fallbackView != null) {
            fallbackView?.setState(orbState)
            return
        }
        if (!OrbPagChain.canLoad(segment, tag)) {
            Log.i(TAG, "PAG_GATED segment=${segment.asset} tag=$tag")
            return
        }
        load(segment)
    }

    /**
     * Loads [segment] and starts it when the window is visible.
     *
     * A load or composition failure is terminal for the PAG path: the view is replaced by [VoiceOrbView] once and
     * `PAG_LOAD_FAILED` is logged with the official asset name.
     */
    private fun load(segment: OrbPagChain.Segment) {
        val pag = pagView ?: return
        val file = runCatching { PAGFile.Load(context.assets, assetRoot + segment.asset) }.getOrElse { failure ->
            fail(segment.asset, failure)
            return
        }
        if (file == null) {
            fail(segment.asset, null)
            return
        }
        runCatching {
            pag.stop()
            pag.setComposition(file)
            pag.setRepeatCount(segment.repeatCount)
        }.onFailure { failure ->
            fail(segment.asset, failure)
            return
        }
        tag = segment.tag
        segmentAsset = segment.asset
        repeatCount = segment.repeatCount
        history.addLast(segment.asset)
        if (history.size > HISTORY_LIMIT) history.removeFirst()
        playing = false
        ended = false
        Log.i(TAG, "PAG_SEGMENT ${segment.asset} tag=$tag repeat=${segment.repeatCount} running=$running")
        if (running) {
            pag.play()
            playing = true
        }
    }

    private fun fail(asset: String, failure: Throwable?) {
        val reason = failure?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "load returned null"
        Log.e(TAG, "PAG_LOAD_FAILED asset=$asset reason=$reason")
        installFallback()
    }

    private fun installFallback() {
        if (fallbackView != null) return
        val voice = VoiceOrbView(context)
        voice.setState(orbState)
        fallbackView = voice
        pagView?.let { view -> runCatching { removeView(view) } }
        addView(voice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        playing = false
        Log.e(TAG, "PAG_FALLBACK voice_orb=on state=${orbState.name}")
    }

    // ---------------------------------------------------------------- probes (instrumentation contract)

    /** The official `id_pv` tag; `none` before the first clip. */
    fun currentTagForProbe(): String = tag

    /** The loaded asset, e.g. `ai_listener_middle.pag`; empty before the first load or after a PAG failure. */
    fun currentSegmentForProbe(): String = segmentAsset.orEmpty()

    /** Every clip loaded since construction, oldest first (bounded). */
    fun historyForProbe(): List<String> = history.toList()

    /** The current segment's libpag repeat count (1 = once, 0 = loop), or -1 before the first load. */
    fun repeatCountForProbe(): Int = repeatCount

    /** Whether libpag's own animator is playing. */
    fun isRunningForProbe(): Boolean = pagView?.isPlaying == true

    /** libpag's current progress for the loaded clip, 0..1; -1 when the PAG path is gone. */
    fun progressForProbe(): Double = pagView?.progress ?: -1.0

    /** libpag's current clip duration in microseconds; -1 when the PAG path is gone. */
    fun durationForProbe(): Long = pagView?.duration() ?: -1L

    /** True once the PAG path failed and [VoiceOrbView] took over. */
    fun isFallbackForProbe(): Boolean = fallbackView != null

    private companion object {
        const val TAG = "EdithGlasses"
        const val ASSET_ROOT = "pag/"
        const val HISTORY_LIMIT = 64
        val IDLE_REQUEST = Any()
    }
}
