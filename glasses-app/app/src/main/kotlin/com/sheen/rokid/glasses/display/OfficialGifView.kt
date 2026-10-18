package com.sheen.rokid.glasses.display

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import android.widget.ImageView

/**
 * 0.24 S4 Task 19 (spec §3.4/§5.6): one official GIF, decoded by the platform and scaled into its official slot.
 *
 * The decoded official APK ships four animated GIFs (`gif_loading`, `ai_tts_thinking`, `ai_agent_tool_start`,
 * `ai_agent_tool_end`). The official code hands them to Glide, which is a second animation engine and a second image
 * loader on the glasses; Android 12's `ImageDecoder` already returns an [AnimatedImageDrawable] for a GIF, so the panel
 * uses the platform decoder and no new library. The GIF files are copied byte-for-byte into `res/drawable`.
 *
 * **The slot size is the layout's, not the GIF's.** The official GIFs have 40x20 and 72x72 intrinsic sizes, while the
 * official XML gives the ASR "…" a 26x13 box and the thinking/tool icons 22x22. `FIT_CENTER` reproduces the official
 * `ImageView` scaling (both aspect ratios match: 2:1 and 1:1), so the wearer sees the authored artwork at the layout's
 * size.
 *
 * **Looping follows the official runtime.** The loading/thinking/start GIFs loop while their state is on screen; the
 * end GIF is played once, exactly the official `agentToolStatus` `gifDrawable.setLoopCount(1)`.
 *
 * [start]/[stop] are idempotent and are the host's lifecycle seam: the panel stops every animation when its window is
 * hidden, and resumes the visible ones on `onStart`. A decode failure is logged and leaves the view empty - it never
 * pretends the official animation rendered.
 */
class OfficialGifView(
    context: Context,
    resId: Int,
    repeatInfinite: Boolean = true,
) : ImageView(context) {

    private var animated: AnimatedImageDrawable? = null
    private var gifRes: Int = resId
    private var loopForever: Boolean = repeatInfinite

    /** The host's desire, kept across detach/attach: a visible row restarts when its window returns. */
    private var wantRunning = false

    init {
        scaleType = ScaleType.FIT_CENTER
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        load(resId, repeatInfinite)
    }

    /** Swaps the animation in the same view (used by the tool row START -> END restyle, which must not add a row). */
    fun setGif(resId: Int, repeatInfinite: Boolean = true) {
        if (resId == gifRes && loopForever == repeatInfinite && animated != null) return
        gifRes = resId
        loopForever = repeatInfinite
        load(resId, repeatInfinite)
        if (wantRunning) animated?.start()
    }

    private fun load(resId: Int, repeatInfinite: Boolean) {
        animated?.stop()
        animated = null
        val decoded = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resId))
        }.getOrElse { error ->
            // No invented fallback here: a missing/corrupt official asset is a build error, and the log is the only
            // truthful evidence that this row will be blank.
            Log.e(TAG, "GIF_LOAD_FAILED res=${resourceName(resId)} reason=${error.javaClass.simpleName}:${error.message}")
            null
        }
        if (decoded is AnimatedImageDrawable) {
            decoded.repeatCount = if (repeatInfinite) AnimatedImageDrawable.REPEAT_INFINITE else 0
            animated = decoded
            setImageDrawable(decoded)
        } else if (decoded != null) {
            Log.w(TAG, "GIF_NOT_ANIMATED res=${resourceName(resId)} type=${decoded.javaClass.simpleName}")
            setImageDrawable(decoded)
        } else {
            setImageDrawable(null)
        }
    }

    /** Starts (or resumes) the animation. */
    fun start() {
        wantRunning = true
        animated?.start()
    }

    /** Stops the animation and keeps the current frame, exactly Android's `AnimatedImageDrawable.stop()`. */
    fun stop() {
        wantRunning = false
        animated?.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (wantRunning) animated?.start()
    }

    override fun onDetachedFromWindow() {
        // The view may be re-attached (a rebuilt panel row): stop the frame loop now, resume only if the host asked.
        animated?.stop()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- instrumentation probes

    fun isRunningForProbe(): Boolean = animated?.isRunning == true

    /** True when the platform decoded an [AnimatedImageDrawable] rather than a static image. */
    fun isAnimatedForProbe(): Boolean = animated != null

    fun intrinsicWidthForProbe(): Int = animated?.intrinsicWidth ?: 0

    fun intrinsicHeightForProbe(): Int = animated?.intrinsicHeight ?: 0

    fun repeatCountForProbe(): Int = animated?.repeatCount ?: Int.MIN_VALUE

    fun resourceNameForProbe(): String = resourceName(gifRes)

    private fun resourceName(resId: Int): String =
        runCatching { context.resources.getResourceEntryName(resId) }.getOrDefault("res:$resId")

    private companion object {
        const val TAG = "EdithGlasses"
    }
}
