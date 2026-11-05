package com.sheen.rokid.phone.actions.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import com.sheen.rokid.phone.context.MediaSessionMapper
import com.sheen.rokid.phone.context.NotificationCategoryService
import kotlin.math.abs

/**
 * One active media session as `media list` reports it (0.5.4 Task 15, spec §6.5).
 *
 * Every member is a platform fact, never an inference:
 * - [actions] is `PlaybackState.getActions()`, the same bit mask the tool checks before it sends anything;
 * - [current] is the API 33+ `MediaSessionManager.getMediaKeyEventSession()` session when the platform names one;
 * - [controllable] means the session advertises at least one transport action, so a call has something to send;
 * - an unknown position/duration stays `null`, never a fabricated zero.
 *
 * [packageName], [state] and the timings are the only fields a log line may carry; [title], [artist] and [album] are
 * user content and must never reach [com.sheen.rokid.phone.platform.P3Log].
 */
data class MediaSessionInfo(
    val packageName: String,
    val state: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val actions: Long,
    val current: Boolean,
    val controllable: Boolean,
)

/**
 * What one control command proved (0.5.4 Task 15, spec §6.5).
 *
 * `NoChange` and `SessionGone` are **post-dispatch** answers: the command reached the transport, so the tool reports
 * `accepted = true` for them but must not claim it applied. `Done` exists only with a `before`/`after` pair read from
 * `PlaybackState` inside the verification window.
 */
sealed interface MediaControlResult {
    data class Done(val before: MediaSessionInfo, val after: MediaSessionInfo) : MediaControlResult

    /** Notification access was lost between the list and the send. */
    data object NoPermission : MediaControlResult

    /** The session that was listed is no longer present. */
    data object NoSession : MediaControlResult

    /** More than one session matches and the caller did not name a package. */
    data class Ambiguous(val candidates: List<String>) : MediaControlResult

    /** The session does not advertise the transport action's `PlaybackState.actions` bit. */
    data object Unsupported : MediaControlResult

    /** The transport command was sent but no evidence appeared inside the window. */
    data object NoChange : MediaControlResult

    /** The controller disappeared while the command was being verified. */
    data object SessionGone : MediaControlResult
}

/**
 * The Android touch point of `media` (0.5.4 Task 15, spec §6.5).
 *
 * [list] throws [MediaNotificationAccessException] when the notification listener is not connected (or the platform
 * refuses `getActiveSessions`); `list()` has no result envelope of its own, so the absence of access cannot be confused
 * with an empty list. [control] answers [MediaControlResult.NoPermission] for the same condition, because there it has
 * a result to carry it.
 */
interface MediaControlPort {

    /** Every active session. @throws MediaNotificationAccessException when notification access is not granted. */
    fun list(): List<MediaSessionInfo>

    /** Sends one transport command and verifies it; [packageName] selects the session when several are active. */
    fun control(action: String, packageName: String?, positionMs: Long?): MediaControlResult
}

/** The notification listener is not connected, or the platform refused `getActiveSessions`. */
class MediaNotificationAccessException : Exception("notification access is not granted")

/** The action vocabulary shared by the tool and the port: one string, one transport bit, one command. */
internal object MediaAction {
    const val LIST = "list"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val TOGGLE = "toggle"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val STOP = "stop"
    const val SEEK = "seek"

    val ALL: Set<String> = setOf(LIST, PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP, SEEK)

    /**
     * The `PlaybackState.getActions()` bits any one of which proves the session accepts [action], or `null` for an
     * action that is not a transport command (`list`).
     *
     * A play/pause session commonly advertises only `ACTION_PLAY_PAUSE`, which is why play and pause accept that bit
     * beside their own; a session that advertises neither still answers `ACTION_UNSUPPORTED` instead of a fake send.
     */
    fun requiredBit(action: String): Long? = when (action) {
        PLAY -> PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
        PAUSE -> PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        TOGGLE -> PlaybackState.ACTION_PLAY_PAUSE
        NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
        PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
        STOP -> PlaybackState.ACTION_STOP
        SEEK -> PlaybackState.ACTION_SEEK_TO
        else -> null
    }
}

/** One platform controller, behind a seam a JVM test can fake (0.5.4 Task 15). */
internal interface MediaSessionHandle {
    val packageName: String

    /** The current platform facts, or `null` when the session cannot be read (gone, or no playback state). */
    fun snapshot(): MediaSessionInfo?

    /** Sends one transport command. `false` means the platform refused it (the session is gone). */
    fun send(action: String, positionMs: Long?): Boolean

    /** Releases the underlying controller. Every controller obtained by one call is released. */
    fun release()
}

/** The platform's active-session source; the one place `getActiveSessions` is read. */
internal fun interface MediaSessionHandles {
    /** @throws MediaNotificationAccessException when the notification listener is not connected. */
    fun activeSessions(): List<MediaSessionHandle>
}

/**
 * The production media port (0.5.4 Task 15, spec §6.5): `getActiveSessions` → transport → `PlaybackState` poll →
 * release.
 *
 * The controller lifecycle is the whole contract:
 * - **every** controller obtained by one call is released in a `finally`, including sessions that were not selected -
 *   `getActiveSessions` hands out a fresh controller per call, and leaking one would keep a binder alive;
 * - no token is cached across calls or turns, so a session that appears between calls is seen on the next one;
 * - a command is never called success: the port sends it, then polls [snapshot] until [hasEvidence] accepts the new
 *   facts or the 1.5 s window expires. A session that disappears during the poll is [MediaControlResult.SessionGone].
 *
 * The primary constructor is the test seam (a fake [MediaSessionHandles] and a fake clock/sleeper, so no JVM test
 * sleeps); production uses the `(context, listenerComponent)` constructor.
 */
internal class AndroidMediaControlPort internal constructor(
    private val platform: MediaSessionHandles,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
) : MediaControlPort {

    constructor(context: Context, listenerComponent: ComponentName) : this(
        AndroidMediaSessionHandles(context, listenerComponent),
        { SystemClock.elapsedRealtime() },
        { Thread.sleep(it) },
    )

    override fun list(): List<MediaSessionInfo> {
        val handles = platform.activeSessions()
        return try {
            // A session with no playback state describes nothing; it is skipped rather than rendered as stopped.
            handles.mapNotNull { handle -> runCatching { handle.snapshot() }.getOrNull() }
        } finally {
            releaseAll(handles)
        }
    }

    override fun control(action: String, packageName: String?, positionMs: Long?): MediaControlResult {
        val handles = try {
            platform.activeSessions()
        } catch (_: MediaNotificationAccessException) {
            return MediaControlResult.NoPermission
        }
        return try {
            if (handles.isEmpty()) return MediaControlResult.NoSession
            val candidates = if (packageName == null) {
                handles
            } else {
                handles.filter { it.packageName == packageName }
            }
            if (candidates.isEmpty()) return MediaControlResult.NoSession
            if (packageName == null && candidates.size > 1) {
                return MediaControlResult.Ambiguous(candidates.map { it.packageName })
            }
            // The media-key-event session is the phone's current one when the platform names it; otherwise the
            // platform's own order is the only fact available and the first candidate is used.
            val target = candidates.firstOrNull { candidate ->
                runCatching { candidate.snapshot()?.current == true }.getOrDefault(false)
            } ?: candidates.first()
            val before = runCatching { target.snapshot() }.getOrNull() ?: return MediaControlResult.SessionGone
            val bit = MediaAction.requiredBit(action) ?: return MediaControlResult.Unsupported
            if ((before.actions and bit) == 0L) return MediaControlResult.Unsupported
            if (!runCatching { target.send(action, positionMs) }.getOrDefault(false)) {
                return MediaControlResult.SessionGone
            }
            val after = awaitEvidence(target, action, before, positionMs) ?: return MediaControlResult.SessionGone
            if (!hasEvidence(action, before, after, positionMs)) return MediaControlResult.NoChange
            MediaControlResult.Done(before, after)
        } finally {
            releaseAll(handles)
        }
    }

    /** Polls the session's facts until the evidence appears, the session disappears, or the window expires. */
    private fun awaitEvidence(
        handle: MediaSessionHandle,
        action: String,
        before: MediaSessionInfo,
        positionMs: Long?,
    ): MediaSessionInfo? {
        val deadline = now() + VERIFY_TIMEOUT_MILLIS
        while (true) {
            val snapshot = runCatching { handle.snapshot() }.getOrNull() ?: return null
            if (hasEvidence(action, before, snapshot, positionMs)) return snapshot
            val remaining = deadline - now()
            if (remaining <= 0L) return snapshot
            try {
                sleep(minOf(POLL_INTERVAL_MILLIS, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return snapshot
            } catch (_: RuntimeException) {
                return snapshot
            }
        }
    }

    private fun releaseAll(handles: List<MediaSessionHandle>) {
        handles.forEach { handle -> runCatching { handle.release() } }
    }

    private companion object {
        /** Spec §6.5's verification window: 0.5-1.5 s. The upper bound is the honest one to spend. */
        const val VERIFY_TIMEOUT_MILLIS = 1_500L
        const val POLL_INTERVAL_MILLIS = 100L

        /** A seek is proven when the reported position is within two seconds of the requested one. */
        const val SEEK_TOLERANCE_MILLIS = 2_000L

        const val STATE_PLAYING = "PLAYING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_BUFFERING = "BUFFERING"

        /**
         * Spec §6.5's evidence table, one entry per action. The command is not success; this predicate is.
         */
        fun hasEvidence(
            action: String,
            before: MediaSessionInfo,
            after: MediaSessionInfo,
            positionMs: Long?,
        ): Boolean = when (action) {
            MediaAction.PLAY -> after.state == STATE_PLAYING || after.state == STATE_BUFFERING
            MediaAction.PAUSE -> after.state == STATE_PAUSED
            MediaAction.STOP -> after.state == STATE_STOPPED
            MediaAction.TOGGLE ->
                after.state != before.state &&
                    (after.state == STATE_PLAYING || after.state == STATE_BUFFERING || after.state == STATE_PAUSED)
            // A next/previous may keep the same state on a paused player, so a changed metadata identity is the
            // second valid witness; a state transition alone is the first.
            MediaAction.NEXT, MediaAction.PREVIOUS ->
                after.state != before.state || identityChanged(before, after)
            MediaAction.SEEK ->
                positionMs != null && after.positionMs != null &&
                    abs(after.positionMs - positionMs) <= SEEK_TOLERANCE_MILLIS &&
                    after.durationMs != null
            else -> false
        }

        /** The metadata identity available to this port; track/queue id are not part of the public result. */
        fun identityChanged(before: MediaSessionInfo, after: MediaSessionInfo): Boolean =
            before.title != after.title ||
                before.artist != after.artist ||
                before.album != after.album ||
                before.durationMs != after.durationMs
    }
}

/**
 * The production [MediaSessionHandle]: one `MediaController`, read through the same metadata keys the context signal
 * uses (`MediaSessionMapper.KEY_*`), so the two paths cannot disagree about which extras are a title/artist/album.
 */
internal class AndroidMediaSessionHandle(
    controller: MediaController,
    private val isCurrent: Boolean,
) : MediaSessionHandle {

    private var controller: MediaController? = controller

    override val packageName: String
        get() = controller?.packageName.orEmpty()

    override fun snapshot(): MediaSessionInfo? = runCatching {
        val live = controller ?: return@runCatching null
        val playback = live.playbackState ?: return@runCatching null
        val metadata = live.metadata
        MediaSessionInfo(
            packageName = live.packageName,
            state = stateName(playback.state),
            title = metadata?.getString(MediaSessionMapper.KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() },
            artist = metadata?.getString(MediaSessionMapper.KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() },
            album = metadata?.getString(MediaSessionMapper.KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() },
            positionMs = playback.position.takeIf { it >= 0L },
            durationMs = metadata?.getLong(MediaSessionMapper.KEY_DURATION)?.takeIf { it > 0L },
            actions = playback.actions,
            current = isCurrent,
            controllable = playback.actions != 0L,
        )
    }.getOrNull()

    override fun send(action: String, positionMs: Long?): Boolean = runCatching {
        val live = controller ?: return@runCatching false
        val controls = live.transportControls
        when (action) {
            MediaAction.PLAY -> controls.play()
            MediaAction.PAUSE -> controls.pause()
            MediaAction.TOGGLE -> {
                // There is no toggle() on TransportControls; the session's own state decides the target command.
                if (live.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            }
            MediaAction.NEXT -> controls.skipToNext()
            MediaAction.PREVIOUS -> controls.skipToPrevious()
            MediaAction.STOP -> controls.stop()
            MediaAction.SEEK -> controls.seekTo(requireNotNull(positionMs))
            else -> return@runCatching false
        }
        true
    }.getOrDefault(false)

    override fun release() {
        // `android.media.session.MediaController` exposes no public release() (checked against the API 35/36 stubs and
        // the AOSP sources: the class has no such method). `getActiveSessions` hands out controllers that have not
        // registered a callback, so there is no binder registration to undo; dropping this handle's strong reference is
        // the release this API allows. The seam still exists because every controller obtained by one call must be
        // released, and the port's tests pin that all of them are.
        controller = null
    }

    private companion object {
        /** The `PlaybackState` codes this port names; an unmodelled code is reported as `UNKNOWN`. */
        fun stateName(code: Int): String = when (code) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
            else -> "UNKNOWN"
        }
    }
}

/**
 * The production session source: the already-granted notification listener, never a new permission or Service.
 *
 * `getActiveSessions` requires [listenerComponent] to be enabled; without that grant it throws `SecurityException`, and
 * a disconnected listener is [NotificationCategoryService.isConnected]'s `false`. Both become
 * [MediaNotificationAccessException], the one refusal the tool reports as `NO_NOTIFICATION_ACCESS`. The API 33+
 * `getMediaKeyEventSession()` (when available) names the phone's current session; on older platforms no session is
 * marked current rather than guessing one from list order.
 */
internal class AndroidMediaSessionHandles(
    private val context: Context,
    private val listenerComponent: ComponentName,
) : MediaSessionHandles {

    override fun activeSessions(): List<MediaSessionHandle> {
        if (!NotificationCategoryService.isConnected()) throw MediaNotificationAccessException()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: throw MediaNotificationAccessException()
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            throw MediaNotificationAccessException()
        } catch (_: RuntimeException) {
            // A dead system binder is the same honest answer as a refused one: no readable session right now.
            throw MediaNotificationAccessException()
        }
        val currentToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { manager.mediaKeyEventSession }.getOrNull()
        } else {
            null
        }
        return controllers.map { controller ->
            AndroidMediaSessionHandle(
                controller = controller,
                isCurrent = currentToken != null &&
                    runCatching { controller.sessionToken == currentToken }.getOrDefault(false),
            )
        }
    }
}
