package com.sheen.rokid.host.systemone

import com.sheen.rokid.plugin.LayaInferenceEngine
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType

/**
 * Stub engine for JVM unit tests. Answers every question with an even split over its own criteria, so the service's
 * plumbing — serialization, availability, failure translation, latency — can be exercised without provisioning the
 * 679 MB publisher set.
 *
 * It performs no inference at all. It is reachable in production only as [SystemOneServiceImpl]'s default
 * constructor argument, and a production composition must inject the publisher's host instead.
 */
internal class StubLayaEngine : LayaInferenceEngine {
    private var initialized = false

    override fun initialize(): Boolean {
        initialized = true
        return true
    }

    override fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer {
        check(initialized) { "Engine not initialized" }
        return when (question.type) {
            SystemOneQuestionType.CHOICE -> {
                val labels = question.criteria.map { it.label }
                SystemOneAnswer.Choice(
                    choice = labels.first(),
                    probabilities = evenSplit(labels),
                    confidence = 1.0 / labels.size,
                )
            }

            SystemOneQuestionType.SCORE -> {
                val levels = question.criteria.indices.map { it.toString() }
                SystemOneAnswer.Score(
                    level = (levels.size - 1) / 2.0,
                    probabilities = evenSplit(levels),
                    confidence = 1.0 / levels.size,
                )
            }

            SystemOneQuestionType.NOUL -> SystemOneAnswer.Noul(probability = 0.5, confidence = 0.5)
        }
    }

    override fun close() {
        initialized = false
    }

    private fun evenSplit(labels: List<String>): Map<String, Double> {
        val each = 1.0 / labels.size
        return labels.associateWithTo(LinkedHashMap()) { each }
    }
}
