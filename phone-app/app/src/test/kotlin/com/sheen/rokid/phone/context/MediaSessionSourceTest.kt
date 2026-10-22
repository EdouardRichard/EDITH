package com.sheen.rokid.phone.context

import com.sheen.rokid.plugin.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 2 (0.3 Phase 2): the media-session mapping, pinned without Android.
 *
 * [MediaSessionMapper] is the whole decision surface of the media fact: which platform playback code becomes which
 * contract state, and what happens to a field the platform does not report. The Android half
 * ([MediaSessionSource]) only collects a state code, a metadata map and two timings, so these cases are the complete
 * mapping rule rather than a sample of it.
 *
 * The cases use the raw platform codes (`STATE_PLAYING = 3`, `STATE_PAUSED = 2`, `STATE_STOPPED = 1`, and a code the
 * contract does not model) because that is exactly what the platform hands over.
 */
class MediaSessionSourceTest {

    private val fullMetadata = mapOf(
        MediaSessionMapper.KEY_TITLE to "Synthetic",
        MediaSessionMapper.KEY_ARTIST to "Synthetic Artist",
        MediaSessionMapper.KEY_ALBUM to "Synthetic Album",
        MediaSessionMapper.KEY_DURATION to "180000",
    )

    @Test
    fun `playing maps to PLAYING and carries the reported metadata`() {
        val mapped = MediaSessionMapper.map(
            state = 3,
            metadata = fullMetadata,
            positionMs = 42_000L,
            durationMs = 180_000L,
        )

        assertEquals(
            MediaState(
                state = "PLAYING",
                title = "Synthetic",
                artist = "Synthetic Artist",
                album = "Synthetic Album",
                positionMs = 42_000L,
                durationMs = 180_000L,
            ),
            mapped,
        )
    }

    @Test
    fun `paused and stopped are their own states`() {
        assertEquals("PAUSED", MediaSessionMapper.map(2, fullMetadata, null, null)?.state)
        assertEquals("STOPPED", MediaSessionMapper.map(1, fullMetadata, null, null)?.state)
    }

    @Test
    fun `a playback code the contract does not model is UNKNOWN`() {
        assertEquals(
            "an unmodelled platform code is reported as unknown, never guessed into play/pause/stop",
            "UNKNOWN",
            MediaSessionMapper.map(7, fullMetadata, null, null)?.state,
        )
    }

    @Test
    fun `a missing playback state is no media fact at all`() {
        assertNull(
            "the platform reporting no state is not a session this host can describe",
            MediaSessionMapper.map(null, fullMetadata, null, null),
        )
    }

    @Test
    fun `missing metadata and timings stay null instead of being invented`() {
        val mapped = MediaSessionMapper.map(3, emptyMap(), null, null)

        assertEquals(MediaState(state = "PLAYING"), mapped)
    }

    @Test
    fun `blank metadata is an absence, not an empty fact`() {
        val mapped = MediaSessionMapper.map(3, mapOf(MediaSessionMapper.KEY_TITLE to "   "), null, null)

        assertNull(mapped?.title)
    }

    @Test
    fun `an unknown position or duration is not a fabricated zero`() {
        // `PlaybackState.PLAYBACK_POSITION_UNKNOWN` is -1 and a missing duration reads as 0; neither is a timing.
        val mapped = MediaSessionMapper.map(3, emptyMap(), positionMs = -1L, durationMs = 0L)

        assertNull(mapped?.positionMs)
        assertNull(mapped?.durationMs)
    }
}
