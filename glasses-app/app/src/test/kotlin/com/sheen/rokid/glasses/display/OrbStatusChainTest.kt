package com.sheen.rokid.glasses.display

import com.sheen.rokid.output.OfficialStatus
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24 S4 Task 18 (spec §3.4): the official PAG orb's status→segment chain, without a device.
 *
 * The official `AiAssistantDialog` runs nine `.pag` clips through one `id_pv` PAGView. Which clip is loaded is decided
 * by the incoming status and, on every `onAnimationEnd`, by the **tag** the view currently carries. Those two decisions
 * are pure; only loading the binary and measuring the animation need the glasses. The expected tags below are the
 * official constants (`AiAssistantDialog.ANIMATION_*`), not invented names.
 *
 * The asset check at the end is deliberately part of this JVM suite: the task's asset requirement is "nine official
 * files, byte-for-byte", and a copy that silently loses one file or replaces one must fail in CI rather than on a worn
 * device. Sizes and SHA-256 come from the decoded official APK used for the copy.
 */
class OrbStatusChainTest {

    @Test
    fun everyOfficialStatusStartsItsDocumentedSegment() {
        val expected = mapOf(
            // status 0/1/2: the listener chain (official showNoneView / showListenerUI / showASRResultMessage).
            OfficialStatus.EMPTY to OrbPagChain.Segment.LISTENER_FIRST,
            OfficialStatus.LISTENING to OrbPagChain.Segment.LISTENER_FIRST,
            OfficialStatus.ASR to OrbPagChain.Segment.LISTENER_FIRST,
            // status 4: the official showTranslateStateUI loads `ai_listener_last.pag`, whose end starts the thought.
            OfficialStatus.ASR_END to OrbPagChain.Segment.LISTENER_LAST,
            // status 3/7: showUpdateTTSUI / agentToolStatus load `ai_think_last.pag`, then the finish chain.
            OfficialStatus.ANSWERING to OrbPagChain.Segment.THINK_LAST,
            OfficialStatus.TOOL to OrbPagChain.Segment.THINK_LAST,
            // status 5: showAudioFinishUI's lastFinish() loads `ai_finish_last.pag`.
            OfficialStatus.AUDIO_FINISHED to OrbPagChain.Segment.FINISH_LAST,
        )
        assertEquals("the table must cover every wire status this build knows",
            OfficialStatus.entries.toSet(), expected.keys)
        OfficialStatus.entries.forEach { status ->
            assertEquals("status ${status.wire} entry", expected.getValue(status),
                OrbPagChain.entryFor(status))
        }
    }

    @Test
    fun theIdleStateUsesTheFinishTail() {
        // Official showIdleStatus() calls lastFinish(): the finish tail, then it stays on the last frame.
        assertEquals(OrbPagChain.Segment.FINISH_LAST, OrbPagChain.idleEntry())
    }

    @Test
    fun firstAndLastSegmentsPlayOnceWhileMiddlesLoop() {
        val segments = OrbPagChain.Segment.entries
        assertEquals("the official APK ships exactly nine clips", 9, segments.size)
        assertEquals("each clip has its own asset", 9, segments.map { it.asset }.toSet().size)
        segments.forEach { segment ->
            val loops = segment.asset.contains("_middle.pag")
            assertEquals("${segment.asset} repeat count", if (loops) 0 else 1, segment.repeatCount)
        }
        // libpag 4.5.27: setRepeatCount(1) plays once, 0 or negative loops forever. The official code uses exactly 1
        // for first/last and 0 for middle, so this assertion is the binary's own contract, not a preference.
        assertEquals(0, OrbPagChain.Segment.LISTENER_MIDDLE.repeatCount)
        assertEquals(0, OrbPagChain.Segment.THINK_MIDDLE.repeatCount)
        assertEquals(0, OrbPagChain.Segment.FINISH_MIDDLE.repeatCount)
        assertEquals(1, OrbPagChain.Segment.LISTENER_FIRST.repeatCount)
        assertEquals(1, OrbPagChain.Segment.THINK_LAST.repeatCount)
        assertEquals(1, OrbPagChain.Segment.FINISH_LAST.repeatCount)
    }

    @Test
    fun theAnimationEndCallbacksFollowTheOfficialChain() {
        // The five official PAGViewListener.onAnimationEnd branches (AiAssistantDialog.initView listener).
        assertEquals(OrbPagChain.Segment.LISTENER_MIDDLE, OrbPagChain.nextOnEnd(OrbPagChain.TAG_LISTENER))
        assertEquals(OrbPagChain.Segment.THINK_FIRST, OrbPagChain.nextOnEnd(OrbPagChain.TAG_LISTENER_LAST))
        assertEquals(OrbPagChain.Segment.THINK_MIDDLE, OrbPagChain.nextOnEnd(OrbPagChain.TAG_THINK_FIRST))
        assertEquals(OrbPagChain.Segment.FINISH_FIRST, OrbPagChain.nextOnEnd(OrbPagChain.TAG_THINK_LAST))
        assertEquals(OrbPagChain.Segment.FINISH_MIDDLE, OrbPagChain.nextOnEnd(OrbPagChain.TAG_FINISH_FIRST))
        // No official branch handles these: think/finish middles loop, finish_last holds the last frame, none is blank.
        assertNull(OrbPagChain.nextOnEnd(OrbPagChain.TAG_THINK_MIDDLE))
        assertNull(OrbPagChain.nextOnEnd(OrbPagChain.TAG_FINISH_MIDDLE))
        assertNull(OrbPagChain.nextOnEnd(OrbPagChain.TAG_FINISH_LAST))
        assertNull(OrbPagChain.nextOnEnd(OrbPagChain.TAG_NONE))
    }

    @Test
    fun theTagGatesAreTheOfficialLoadGuards() {
        // loadListeningWaitingGif(): load listener_first only when the view is not already in the listener segment.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("listener_first from $tag",
                tag != OrbPagChain.TAG_LISTENER,
                OrbPagChain.canLoad(OrbPagChain.Segment.LISTENER_FIRST, tag))
        }
        // cycleListeningWaiting(): only the listener tag's end loads the looping middle.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("listener_middle from $tag", tag == OrbPagChain.TAG_LISTENER,
                OrbPagChain.canLoad(OrbPagChain.Segment.LISTENER_MIDDLE, tag))
        }
        // loadingGif(): the ASR-end segment leaves the listener chain only.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("listener_last from $tag", tag == OrbPagChain.TAG_LISTENER,
                OrbPagChain.canLoad(OrbPagChain.Segment.LISTENER_LAST, tag))
        }
        // loadThinkGif(): the thought first clip follows the listener tail only.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("think_first from $tag", tag == OrbPagChain.TAG_LISTENER_LAST,
                OrbPagChain.canLoad(OrbPagChain.Segment.THINK_FIRST, tag))
        }
        // cycleThinking(): the thought middle follows the thought first.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("think_middle from $tag", tag == OrbPagChain.TAG_THINK_FIRST,
                OrbPagChain.canLoad(OrbPagChain.Segment.THINK_MIDDLE, tag))
        }
        // loadSuccessGif(): status 3/7 can leave a warmed thought or the listener tail, never a cold listener.
        val thinkLastFrom = setOf(
            OrbPagChain.TAG_THINK_MIDDLE, OrbPagChain.TAG_THINK_FIRST, OrbPagChain.TAG_LISTENER_LAST)
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("think_last from $tag", tag in thinkLastFrom,
                OrbPagChain.canLoad(OrbPagChain.Segment.THINK_LAST, tag))
        }
        // loadFinish(): the finish first follows the thought tail.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("finish_first from $tag", tag == OrbPagChain.TAG_THINK_LAST,
                OrbPagChain.canLoad(OrbPagChain.Segment.FINISH_FIRST, tag))
        }
        // cycleFinish(): the finish middle follows the finish first.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertEquals("finish_middle from $tag", tag == OrbPagChain.TAG_FINISH_FIRST,
                OrbPagChain.canLoad(OrbPagChain.Segment.FINISH_MIDDLE, tag))
        }
        // lastFinish() is unconditional: status 5 and IDLE stop whatever clip is running.
        OrbPagChain.ALL_TAGS.forEach { tag ->
            assertTrue("finish_last from $tag",
                OrbPagChain.canLoad(OrbPagChain.Segment.FINISH_LAST, tag))
        }
    }

    @Test
    fun aStatusThatArrivesBeforeItsChainIsReadyDoesNotForceASegment() {
        // Spec §3.4: the table is the target chain, not a forced jump. Status 3 while the orb is still listening does
        // not skip the thought: official loadSuccessGif() refuses, so the panel keeps the listener segment.
        assertEquals(OrbPagChain.Segment.THINK_LAST, OrbPagChain.entryFor(OfficialStatus.ANSWERING))
        assertFalse(OrbPagChain.canLoad(OrbPagChain.Segment.THINK_LAST, OrbPagChain.TAG_LISTENER))

        // Status 4 arriving after a previous turn already sits on the finish tail has no listener clip to end.
        assertEquals(OrbPagChain.Segment.LISTENER_LAST, OrbPagChain.entryFor(OfficialStatus.ASR_END))
        assertFalse(OrbPagChain.canLoad(OrbPagChain.Segment.LISTENER_LAST, OrbPagChain.TAG_FINISH_LAST))
    }

    @Test
    fun theNextListeningStatusRestartsTheChainFromTheFinishTail() {
        // Official status 5 plays finish_last and holds it; the next status 1 finds a tag that is not `listener`, so
        // loadListeningWaitingGif() loads listener_first again. This is the "continuous conversation" transition.
        assertTrue(OrbPagChain.canLoad(OrbPagChain.Segment.LISTENER_FIRST, OrbPagChain.TAG_FINISH_LAST))
        assertTrue(OrbPagChain.canLoad(OrbPagChain.Segment.LISTENER_FIRST, OrbPagChain.TAG_NONE))
        assertEquals("listener_first and listener_middle share the official listener tag",
            OrbPagChain.TAG_LISTENER, OrbPagChain.Segment.LISTENER_FIRST.tag)
        assertEquals(OrbPagChain.TAG_LISTENER, OrbPagChain.Segment.LISTENER_MIDDLE.tag)
    }

    @Test
    fun theProbeStateKeepsTheTask16StatusMapping() {
        assertEquals(OrbState.LISTENING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.EMPTY))
        assertEquals(OrbState.LISTENING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.LISTENING))
        assertEquals(OrbState.LISTENING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.ASR))
        assertEquals(OrbState.THINKING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.ASR_END))
        assertEquals(OrbState.ANSWERING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.ANSWERING))
        assertEquals(OrbState.ANSWERING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.AUDIO_FINISHED))
        assertEquals(OrbState.ANSWERING, GlassesPanelSpec.orbStateForStatus(OfficialStatus.TOOL))
    }

    @Test
    fun theNineOfficialPagAssetsAreCopiedByteForByte() {
        val expected = mapOf(
            "ai_finish_first.pag" to (2674 to "F8EE06583AF1CE53B4C99D4FE25BB490C55E773F9CF4525CA33545FD9563DC02"),
            "ai_finish_last.pag" to (3481 to "1FA5C23E50710F73C0D3046F8C2935452975C28BC1AE79CD83CE022822F984DF"),
            "ai_finish_middle.pag" to (1882 to "EDEF8D64FB05CEB9C23318A513B86C2F7864C6AD3707B91F003CB98551C86949"),
            "ai_listener_first.pag" to (2799 to "CA6A72491419CF359E6F6F0303AF85E05D64FF39A7D33188313418DEA751C885"),
            "ai_listener_last.pag" to (3374 to "1C5897A9C7AE078601560E9C8E90C42E5AB998AD1A2DE55211FF929ACF84B8FF"),
            "ai_listener_middle.pag" to (4081 to "6D22419087693FEC1B037F28E5BA938E75C3D194746BFCB9B715AB42C1FA9D61"),
            "ai_think_first.pag" to (5583 to "E9B5E5BC11B2AA6BA0A60982772FE7EC0EF2B7D278F4E8A89325E44EDD40196C"),
            "ai_think_last.pag" to (6677 to "7651A02749E8A54A9C25B11A05A09727DA0AEFD88BF330DC2B64D87317F58043"),
            "ai_think_middle.pag" to (6153 to "C5839025671BADBB7BCE31E8F248421C760FE2BB1BDFCC66A09F2CC057ECC3E5"),
        )
        val dir = assetDir()
        val actual = dir.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
        assertEquals("the asset directory must contain exactly the nine official clips",
            expected.keys, actual.keys)
        var total = 0L
        expected.forEach { (name, spec) ->
            val (size, hash) = spec
            val file = actual.getValue(name)
            assertEquals("$name size", size.toLong(), file.length())
            assertEquals("$name sha256", hash, sha256(file))
            total += file.length()
        }
        assertEquals("the nine official clips are 36 KB in total", 36_704L, total)
    }

    private fun assetDir(): File {
        val candidates = listOf(
            File("src/main/assets/pag"),
            File("glasses-app/app/src/main/assets/pag"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "PAG assets are missing; looked in ${candidates.joinToString { it.absolutePath }}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it.toInt() and 255) }
    }
}
