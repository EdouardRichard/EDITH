package com.sheen.rokid.testsupport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T16 gap #5: the runner must carry the frozen corpus IDs, a local speech interval annotation and a
 * locally judged correctness per turn, and it must prove that a partial arrived *before* the annotated
 * speech end instead of merely "at some point while replaying the file". A turn that never produced a
 * partial stays in the denominator as an incorrect trial; it is never dropped. Missing or extra turns are
 * rejected rather than silently shrinking the sample.
 */
class SemanticTrialRecorderTest {
    private val concepts = listOf("队列", "栈", "取出顺序")

    @Test fun aPartialInsideTheAnnotatedSpeechIsTheProofThePlanRequires() {
        val recorder = SemanticTrialRecorder.forTesting(entry("P2Q002", 1000, 5000))
        recorder.observe(id = "P2Q002", partialSample = 4000, finalText = "二分查找 数组有序")
        val trial = recorder.judge("P2Q002").single()
        assertTrue(trial.correct)
        assertEquals(4000L, recorder.firstPartialSample("P2Q002"))
        assertEquals(true, recorder.partialBeforeSpeechEnd("P2Q002"))
    }

    @Test fun aPartialOutsideTheAnnotatedSpeechIsRejectedRatherThanCounted() {
        val recorder = SemanticTrialRecorder.forTesting(entry("P2Q001", 1000, 5000))
        // After the annotated speech end: the old scaffold would have accepted this as proof.
        assertFailsWith<IllegalArgumentException> {
            recorder.observe(id = "P2Q001", partialSample = 6000, finalText = "队列 栈 取出顺序")
        }
        // Before the annotated speech start is equally meaningless.
        assertFailsWith<IllegalArgumentException> {
            recorder.observe(id = "P2Q001", partialSample = 999, finalText = "队列 栈 取出顺序")
        }
        assertEquals(null, recorder.firstPartialSample("P2Q001"))
        assertEquals(false, recorder.partialBeforeSpeechEnd("P2Q001"))
    }

    @Test fun aTurnThatNeverProducedAPartialStaysInTheDenominatorAndLosesItsTimingProof() {
        val shared = listOf("线程", "共享变量", "加锁")
        val recorder = SemanticTrialRecorder.forTesting(
            entry("P2Q003", 1000, 4000, shared), entry("P2Q004", 5000, 9000, shared))
        // Only the first turn produced a partial; the second is observed with none.
        recorder.observe(id = "P2Q003", partialSample = 2000, finalText = "线程 共享变量 加锁")
        recorder.observe(id = "P2Q004", partialSample = null, finalText = "")
        recorder.judge("P2Q003"); recorder.judge("P2Q004")
        val trials = recorder.toTrials()
        assertEquals(2, trials.size, "a missing partial must not shrink the trial list")
        // Semantics can still be right from the final text; the streaming claim is what fails, and the two
        // verdicts are reported separately so neither can hide behind the other.
        assertEquals(2, trials.count { it.correct })
        assertEquals(listOf("P2Q003", "P2Q004"), trials.map { it.id })
        assertTrue(recorder.partialBeforeSpeechEnd("P2Q003"))
        assertEquals(false, recorder.partialBeforeSpeechEnd("P2Q004"))
        assertTrue(recorder.annotations().single { it.id == "P2Q004" }.firstPartialSample == null)
        assertEquals(false, recorder.allPartialsPrecededSpeechEnd())
    }

    @Test fun aTurnWithoutAJudgmentIsRecordedIncorrectRatherThanOptimistic() {
        val recorder = SemanticTrialRecorder.forTesting(entry("P2Q005", 0, 3000))
        recorder.observe(id = "P2Q005", partialSample = 1000, finalText = "进程 线程 资源管理")
        // Never judged at all: the trial must not pass by default.
        val trials = recorder.toTrials()
        assertEquals(1, trials.size)
        assertEquals(false, trials[0].correct, "an unjudged turn is not a pass")
        assertEquals(0, recorder.annotations().single().conceptsPresent)
    }

    @Test fun aJudgmentThatMissesARequiredConceptIsIncorrect() {
        val recorder = SemanticTrialRecorder.forTesting(
            entry("P2Q007", 0, 3000, listOf("事务失败", "数据回滚")))
        val trials = recorder.judge("P2Q007", concepts = listOf("事务失败"))
        assertEquals(1, trials.size)
        assertEquals(false, trials[0].correct, "every required concept must be present")
    }

    @Test fun theFrozenCorpusIsRequiredInFullAndWithoutDuplicates() {
        assertFailsWith<SemanticTrialRecorder.TrialSetMismatch> { SemanticTrialRecorder.of(emptyList()) }
        assertFailsWith<SemanticTrialRecorder.TrialSetMismatch> {
            SemanticTrialRecorder.of(listOf(entry("P2Q001", 0, 1000), entry("P2Q001", 1000, 2000)))
        }
        // 29 of 30 is not the frozen corpus and must not be measured as if it were.
        assertFailsWith<SemanticTrialRecorder.TrialSetMismatch> {
            SemanticTrialRecorder.of((1..29).map { entry("P2Q%03d".format(it), 0, 1000) })
        }
        // Judging twice for one turn would silently overwrite evidence.
        val recorder = SemanticTrialRecorder.forTesting(entry("P2Q008", 0, 1000))
        recorder.observe(id = "P2Q008", partialSample = 500, finalText = "网络超时 重试 重复请求")
        recorder.judge("P2Q008")
        assertFailsWith<IllegalStateException> { recorder.judge("P2Q008", concepts = listOf("网络超时")) }
    }

    @Test fun aMetricWideJudgmentFillsEveryFrozenTrialAndCarriesTheAnnotations() {
        val entries = (1..30).map { index -> entry("P2Q%03d".format(index), 0, 4000, concepts) }
        val recorder = SemanticTrialRecorder.of(entries)
        entries.forEachIndexed { index, entry ->
            recorder.observe(id = entry.id, partialSample = 2000, finalText = "text-$index")
            recorder.judge(entry.id)
        }
        val trials = recorder.toTrials()
        assertEquals(30, trials.size)
        assertTrue(trials.all { it.correct })
        assertContentEquals((1..30).map { "P2Q%03d".format(it) }, trials.map { it.id })
        val annotations = recorder.annotations()
        assertEquals(30, annotations.size)
        assertTrue(annotations.all { it.partialBeforeSpeechEnd })
        assertTrue(annotations.all { it.conceptsPresent == concepts.size })
        assertTrue(recorder.allPartialsPrecededSpeechEnd())
    }

    @Test fun oneFailedTurnMakesTheWholeRunInsufficient() {
        val entries = (1..30).map { index -> entry("P2Q%03d".format(index), 0, 4000, concepts) }
        val recorder = SemanticTrialRecorder.of(entries)
        entries.forEachIndexed { index, entry ->
            recorder.observe(id = entry.id, partialSample = 2000, finalText = "text-$index")
            val observed = if (index == 7) concepts.drop(1) else null
            recorder.judge(entry.id, observed)
        }
        assertEquals(29, recorder.toTrials().count { it.correct })
        assertTrue(recorder.allPartialsPrecededSpeechEnd())
    }

    private fun entry(id: String, speechStartSample: Long, speechEndSample: Long,
        requiredConcepts: List<String> = concepts) =
        SemanticTrialRecorder.Entry(id, speechStartSample, speechEndSample, requiredConcepts)
}
