package com.sheen.rokid.audio.android

data class EngineReport(val rtfP95: Double, val backlogGrowing: Boolean, val keySemanticAccuracy: Double)

object EngineSelection {
    fun accept(report: EngineReport): Boolean =
        report.rtfP95.isFinite() && report.rtfP95 in 0.0..0.7 &&
            !report.backlogGrowing && report.keySemanticAccuracy.isFinite() &&
            report.keySemanticAccuracy in 0.90..1.0
}
