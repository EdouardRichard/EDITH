package com.sheen.rokid.phone.context

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.sheen.rokid.plugin.MediaState

/**
 * The pure half of the media fact (edith-0.3 Phase 2, Task 2; spec §3.2/§7.2).
 *
 * It maps exactly what the platform reports - one playback state code, a metadata map and two timings - into the
 * contract's [MediaState]. Nothing Android-specific happens here: the Android half ([MediaSessionSource]) collects the
 * inputs, so every mapping rule below is pinned by a plain JVM test.
 *
 * The rules follow the contract's "no invented facts" shape:
 * - a playback code the contract does not model is `UNKNOWN`, never guessed into play/pause/stop;
 * - a playback state the platform does not report at all is no media fact (`null`), because there is nothing to
 *   describe - that is also why [MediaSessionSource] skips such a session;
 * - a metadata field the session does not carry stays `null` (blank is an absence, not an empty fact);
 * - `PLAYBACK_POSITION_UNKNOWN` (-1) and a missing duration (0) stay `null` rather than becoming a fabricated zero.
 *
 * The metadata keys are the platform's own key strings, restated here so the mapper itself needs no Android class.
 * They are read from `MediaMetadata` by [MediaSessionSource] with these very constants, so one name is the key on both
 * sides of the seam.
 */
internal object MediaSessionMapper {

    /** `MediaMetadata.METADATA_KEY_TITLE` ("android.media.metadata.TITLE"). */
    const val KEY_TITLE = "android.media.metadata.TITLE"

    /** `MediaMetadata.METADATA_KEY_ARTIST` ("android.media.metadata.ARTIST"). */
    const val KEY_ARTIST = "android.media.metadata.ARTIST"

    /** `MediaMetadata.METADATA_KEY_ALBUM` ("android.media.metadata.ALBUM"). */
    const val KEY_ALBUM = "android.media.metadata.ALBUM"

    /** `MediaMetadata.METADATA_KEY_DURATION` ("android.media.metadata.DURATION"). */
    const val KEY_DURATION = "android.media.metadata.DURATION"

    /**
     * The contract state for one platform playback code, with the reported metadata attached.
     *
     * @param state `PlaybackState.getState()`, or `null` when the session reports no playback state at all.
     * @param metadata metadata key (the `KEY_*` constants above) to its reported string value.
     * @param positionMs the reported position, or `null`/`PLAYBACK_POSITION_UNKNOWN` when it is unknown.
     * @param durationMs the reported duration, or `null`/0 when the metadata carries none.
     */
    fun map(
        state: Int?,
        metadata: Map<String, String>,
        positionMs: Long?,
        durationMs: Long?,
    ): MediaState? {
        // No platform state is no fact: `UNKNOWN` is reserved for a state code the contract does not model.
        if (state == null) return null
        return MediaState(
            state = when (state) {
                PlaybackState.STATE_PLAYING -> "PLAYING"
                PlaybackState.STATE_PAUSED -> "PAUSED"
                PlaybackState.STATE_STOPPED -> "STOPPED"
                else -> "UNKNOWN"
            },
            title = metadata.text(KEY_TITLE),
            artist = metadata.text(KEY_ARTIST),
            album = metadata.text(KEY_ALBUM),
            // `PlaybackState.PLAYBACK_POSITION_UNKNOWN` is -1; a non-positive duration is not a duration.
            positionMs = positionMs?.takeIf { it >= 0 },
            durationMs = durationMs?.takeIf { it > 0 },
        )
    }

    /** A reported metadata string, or `null` when it is absent or only whitespace. */
    private fun Map<String, String>.text(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The Android half of the media fact: the currently playing session, read through the existing notification-listener
 * grant (edith-0.3 Phase 2, Task 2).
 *
 * `MediaSessionManager.getActiveSessions` requires the component named in [listenerComponent] to be an enabled
 * `NotificationListenerService`; without that grant it throws `SecurityException`, and a dead system binder throws
 * `DeadObjectException`/`RuntimeException`. Every one of those is answered `null` - "no media fact right now" - never a
 * fabricated state, because a permission the user has not given cannot be turned into a playback fact.
 *
 * The **first** active session that reports a playback state wins; a session with no playback state describes nothing,
 * so it is skipped instead of being rendered as a stopped/unknown session. No title, artist or album is ever logged:
 * this class adds no log line at all.
 */
internal class MediaSessionSource(
    private val context: Context,
    private val listenerComponent: ComponentName,
) {

    /** The current media fact, or `null` when no session is readable. */
    fun current(): MediaState? {
        // A null manager (no media service on this build) is the same honest absence as a refused binder.
        val sessions = runCatching {
            (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
                ?.getActiveSessions(listenerComponent)
        }.getOrNull() ?: return null

        for (controller in sessions) {
            // The first session with a state is the one the device is playing; one without is not describable.
            val playback = runCatching { controller.playbackState }.getOrNull() ?: continue
            val metadata = runCatching { controller.metadata }.getOrNull()
            return runCatching {
                MediaSessionMapper.map(
                    state = playback.state,
                    metadata = metadataValues(metadata),
                    positionMs = playback.position,
                    durationMs = metadata?.getLong(MediaSessionMapper.KEY_DURATION),
                )
            }.getOrNull() ?: continue
        }
        return null
    }

    /** Only the three content fields the contract carries (plus the duration); nothing else is copied out. */
    private fun metadataValues(metadata: MediaMetadata?): Map<String, String> {
        if (metadata == null) return emptyMap()
        return buildMap {
            metadata.getString(MediaSessionMapper.KEY_TITLE)?.let { put(MediaSessionMapper.KEY_TITLE, it) }
            metadata.getString(MediaSessionMapper.KEY_ARTIST)?.let { put(MediaSessionMapper.KEY_ARTIST, it) }
            metadata.getString(MediaSessionMapper.KEY_ALBUM)?.let { put(MediaSessionMapper.KEY_ALBUM, it) }
        }
    }
}
