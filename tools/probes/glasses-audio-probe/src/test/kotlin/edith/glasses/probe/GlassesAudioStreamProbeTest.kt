package edith.glasses.probe

import com.rokid.cxr.CXRServiceBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses-side `startAudioStream` experiment: what it must report, and against which artifact.
 *
 * Two separate risks are pinned here.
 *
 * 1. **The verdict must not promote a guess.** This probe exists because one question is unanswered: does driving
 *    the glasses-side bridge's own audio-stream entry point deliver microphone PCM to the phone? The bridge
 *    returning `0` only means the local call was accepted - the previous route was refuted precisely because
 *    `sendMessage` returned `0` for 499 frames that never arrived. So an accepted call must never be reported as a
 *    working stream, and the reported verdict has to be derived from an explicit rule rather than from inspection.
 * 2. **The call must match the locked artifact.** `CXRServiceBridge.startAudioStream` is not in the public CXR
 *    documentation, and the parameter meanings are not documented anywhere. If the pinned AAR is swapped, a
 *    silently different signature would turn this probe into a test of nothing, so the signature and the
 *    parameter object's shape are asserted against the AAR actually on the compile classpath.
 */
class GlassesAudioStreamProbeTest {

    // ---- the verdict rule -----------------------------------------------------------------------------------

    @Test fun anAcceptedCallThatReportsStreamingIsAStartNotAConfirmation() {
        // The strongest outcome this probe can observe locally: the bridge accepted it *and* the device says it is
        // streaming. It is still not "audio arrived" - only the phone can say that.
        assertEquals(
            AudioStreamVerdict.STREAM_STARTED,
            GlassesAudioStreamProbe.verdict(
                AudioStreamAttempt(refusalCode = 0, confirmations = 1, streaming = true, error = null),
            ),
        )
    }

    @Test fun acceptedButNeverStreamingIsSilentRatherThanSuccess() {
        // This is the exact shape every previous negative had: the call succeeds and nothing follows. Reporting it
        // as anything like success is the mistake the earlier rounds kept making.
        val verdict = GlassesAudioStreamProbe.verdict(
            AudioStreamAttempt(refusalCode = 0, confirmations = 0, streaming = false, error = null),
        )
        assertEquals(AudioStreamVerdict.ACCEPTED_BUT_NEVER_STREAMED, verdict)
        assertFalse("an accepted call must not be reported as a started stream", verdict == AudioStreamVerdict.STREAM_STARTED)
    }

    @Test fun aRefusedCallIsReportedAsRefusedEvenIfSomethingLaterConfirmed() {
        // The refusal code is the first-class fact: a non-zero code means the bridge rejected the request, so a
        // later confirmation would be about a different attempt and must not be able to mask it.
        assertEquals(
            AudioStreamVerdict.REFUSED_BY_BRIDGE,
            GlassesAudioStreamProbe.verdict(
                AudioStreamAttempt(refusalCode = -22, confirmations = 0, streaming = false, error = null),
            ),
        )
        assertEquals(
            AudioStreamVerdict.REFUSED_BY_BRIDGE,
            GlassesAudioStreamProbe.verdict(
                AudioStreamAttempt(refusalCode = -22, confirmations = 2, streaming = true, error = null),
            ),
        )
    }

    @Test fun anExceptionIsReportedRatherThanSwallowedIntoANegative() {
        // "the bridge threw" and "the bridge said no" need different responses: the first can be a signature or
        // loadproblem, the second a genuine refusal.
        val verdict = GlassesAudioStreamProbe.verdict(
            AudioStreamAttempt(refusalCode = -1, confirmations = 0, streaming = false, error = "UnsatisfiedLinkError: startAudioStream"),
        )
        assertEquals(AudioStreamVerdict.BRIDGE_THREW, verdict)
    }

    @Test fun theReportedLineNamesEveryInputThatDecidesTheRun() {
        // The run is only interpretable if the log line carries the parameters that were used. Without them a
        // second run cannot be compared to the first, which is how a parameter-search would stall.
        val params = AudioStreamParams(codecType = 1, secondary = 1, streamType = "edith-probe", denoiseMode = 1, rokidDtlnAEC = true, rokidBF = true)
        val line = GlassesAudioStreamProbe.summary(
            attempt = AudioStreamAttempt(refusalCode = 0, confirmations = 1, streaming = true, error = null),
            params = params,
            attached = true,
            connected = true,
            subscribed = 0,
            seconds = 10,
            millis = 10_012,
        )
        listOf("GLASSES_AUDIOSTREAM", "verdict=STREAM_STARTED", "codecType=1", "secondary=1",
            // The effective stream type, not the base one: the name in the log has to be the name `stopAudioStream`
            // was actually given, otherwise a run's log could not be used to stop a stream that leaked.
            "streamType=${params.streamType}", "denoiseMode=1", "rokidDtlnAEC=true", "rokidBF=true",
            "attached=true", "connected=true", "subscribed=0", "seconds=10", "millis=10012",
            "refusal=0", "confirmations=1", "streaming=true", "error=none")
            .forEach { assertTrue("the summary must contain '$it', was: $line", line.contains(it)) }
    }

    // ---- the parameter sweep ----------------------------------------------------------------------------------

    @Test fun theSweepKeepsEveryOtherParameterFixedSoOneVariableIsTested() {
        // The parameter meanings are undocumented, so the sweep varies `codecType` alone. If a set also changed the
        // stream type or the record params, a positive could not be attributed to any one of them.
        val sets = GlassesAudioStreamProbe.parameterSets(base = AudioStreamParams(
            codecType = 1, secondary = 1, streamType = "edith-probe", denoiseMode = 1, rokidDtlnAEC = true, rokidBF = true,
        ), codecTypes = listOf(0, 1, 2))
        assertEquals(3, sets.size)
        assertEquals(listOf(0, 1, 2), sets.map { it.codecType })
        sets.forEach { set ->
            assertEquals(1, set.secondary)
            // The base name is preserved and only suffixed, so a run's stream types stay recognisable as a family.
            assertTrue("the sweep must keep the base name, was ${set.streamType}", set.streamType.startsWith("edith-probe"))
            assertEquals(1, set.denoiseMode)
            assertTrue(set.rokidDtlnAEC)
            assertTrue(set.rokidBF)
        }
    }

    @Test fun everyParameterSetBuildsADistinctStreamTypeSoEachAttemptIsStoppableOnItsOwn() {
        // `stopAudioStream` takes only the stream type, so two attempts sharing one would make the second
        // unstoppable and would leave the first running. Distinct names are what make the attempts independent.
        val names = run {
            val sets = GlassesAudioStreamProbe.parameterSets(base = AudioStreamParams(
                codecType = 1, secondary = 1, streamType = "edith-probe", denoiseMode = 0, rokidDtlnAEC = false, rokidBF = false,
            ), codecTypes = listOf(0, 1, 2))
            sets.map { it.streamType }
        }
        assertEquals("stream types must be distinct per attempt, were: $names", names.size, names.toSet().size)
        assertEquals(listOf("edith-probe-0", "edith-probe-1", "edith-probe-2"), names)
    }

    @Test fun aRepeatedCodecTypeStillGetsItsOwnStreamType() {
        // The name is derived from the attempt's position, not from the codec value. Otherwise a list repeating a
        // codec would map two attempts onto one stream type and the second attempt could never be stopped.
        val names = run {
            val sets = GlassesAudioStreamProbe.parameterSets(base = AudioStreamParams(
                codecType = 1, secondary = 1, streamType = "probe", denoiseMode = 0, rokidDtlnAEC = false, rokidBF = false,
            ), codecTypes = listOf(1, 1, 1))
            sets.map { it.streamType }
        }
        assertEquals("probe-0,probe-1,probe-2", names.joinToString(","))
    }

    // ---- the probe refuses to invent parameters -------------------------------------------------------------

    @Test fun anEmptyCodecListIsRefusedBecauseItWouldProduceNoEvidence() {
        val failure = runCatching {
            GlassesAudioStreamProbe.parameterSets(base = AudioStreamParams(
                codecType = 0, secondary = 0, streamType = "s", denoiseMode = 0, rokidDtlnAEC = false, rokidBF = false,
            ), codecTypes = emptyList())
        }.exceptionOrNull()
        assertTrue("an empty sweep must be refused, was: $failure", failure is IllegalArgumentException)
    }

    @Test fun aBlankStreamTypeIsRefusedBecauseItCannotBeStopped() {
        val failure = runCatching {
            GlassesAudioStreamProbe.parameterSets(base = AudioStreamParams(
                codecType = 0, secondary = 0, streamType = "  ", denoiseMode = 0, rokidDtlnAEC = false, rokidBF = false,
            ), codecTypes = listOf(0))
        }.exceptionOrNull()
        assertTrue("a blank stream type must be refused, was: $failure", failure is IllegalArgumentException)
    }

    @Test fun theProbeHoldsNoAudioSoNothingCanBePersisted() {
        // D33: this probe must not be able to retain samples. It reports counters and state only, and the type it
        // reports through must not have a byte-array field that audio could be parked in.
        val audioShapedFields = AudioStreamAttempt::class.java.declaredFields
            .filter { it.type == ByteArray::class.java || it.type == List::class.java }
        assertTrue("the attempt record must carry no audio, found: $audioShapedFields", audioShapedFields.isEmpty())
    }

    // ---- the artifact contract ------------------------------------------------------------------------------

    @Test fun theLockedBridgeStillExposesTheSignaturesThisProbeCalls() {
        // Asserted against the AAR on the compile classpath, not against memory: this API is absent from the public
        // docs, so a version swap is the realistic way this probe would silently stop testing anything.
        val bridge = CXRServiceBridge::class.java
        val start = bridge.getMethod(
            "startAudioStream", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, CXRServiceBridge.AudioRecordParam::class.java,
        )
        assertEquals("startAudioStream must return the refusal code as an int", Int::class.javaPrimitiveType, start.returnType)
        bridge.getMethod("stopAudioStream", String::class.java)
        bridge.getMethod("subscribe", String::class.java, CXRServiceBridge.MsgCallback::class.java)
    }

    @Test fun theRecordParamKeepsTheThreeProcessingFlagsTheDeviceAdvertises() {
        // Denoise / AEC / beam-forming are the only knobs this object has, and they change what the microphone
        // delivers. If the AAR ever drops one, a run that set it would be testing something else.
        val param = CXRServiceBridge.AudioRecordParam(1, true, true)
        assertEquals(1, param.denoiseMode)
        assertTrue(param.rokidDtlnAEC)
        assertTrue(param.rokidBF)
        // The three-argument form is the only one that can set them, so the probe always uses it. The no-arg form is
        // pinned to what the AAR actually does rather than to an assumption: on this artifact it leaves the flags
        // off but **denoiseMode defaults to 2**, not 0. A probe that used the no-arg form would therefore still
        // request denoising - which is exactly the kind of quiet default that makes a parameter sweep unreadable.
        val blank = CXRServiceBridge.AudioRecordParam()
        assertNotEquals("the no-arg default must not be assumed to be 0", 1, blank.denoiseMode)
        assertFalse(blank.rokidDtlnAEC)
        assertFalse(blank.rokidBF)
        // And the three-argument form must be the thing that decides, whatever the default was.
        assertEquals(1, param.denoiseMode)
        assertTrue(param.rokidDtlnAEC)
        assertTrue(param.rokidBF)
    }
}
