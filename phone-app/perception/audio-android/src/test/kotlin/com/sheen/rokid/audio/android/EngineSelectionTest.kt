package com.sheen.rokid.audio.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSelectionTest {
    @Test
    fun failedCandidateIsNotSelected() {
        assertFalse(EngineSelection.accept(EngineReport(0.82, false, 0.95)))
        assertFalse(EngineSelection.accept(EngineReport(0.60, true, 0.95)))
        assertFalse(EngineSelection.accept(EngineReport(0.60, false, 0.85)))
    }

    @Test
    fun exactThresholdsAreAccepted() {
        assertTrue(EngineSelection.accept(EngineReport(0.70, false, 0.90)))
    }

    @Test
    fun invalidMetricsAreRejected() {
        assertFalse(EngineSelection.accept(EngineReport(Double.NaN, false, 0.95)))
        assertFalse(EngineSelection.accept(EngineReport(Double.POSITIVE_INFINITY, false, 0.95)))
        assertFalse(EngineSelection.accept(EngineReport(-0.01, false, 0.95)))
        assertFalse(EngineSelection.accept(EngineReport(0.60, false, 1.01)))
        assertFalse(EngineSelection.accept(EngineReport(0.60, false, -0.01)))
    }
}
