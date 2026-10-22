// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType

/**
 * Maps a contract [SystemOneQuestion] onto the publisher's question schema.
 *
 * The mapping is the whole adapter, and it is a pure function on purpose: it needs no `Context`, no model and no
 * device, so it can be pinned by a JVM unit test while the 679 MB publisher set stays on the phone.
 *
 * It does **not** consult `LayaPromptBuilder.normalize`, and that is deliberate rather than an omission — the
 * publisher's `normalize` only rewrites a *list* of choice labels into a map, and this function emits each form in
 * the shape `normalize` would have produced anyway, which is the shape `renderOptions` and `LayaDecoder.decode`
 * both read. Emitting the already-normal form keeps this file the single place that knows the wire schema.
 *
 * **Per-form rendering, and why each field is what it is:**
 *
 * - `choice` → `criteria` is an ordered object of `label -> description`. The publisher renders an entry with a
 *   null or empty value as the bare label and everything else as `"label: description"`, so a criterion that has
 *   nothing to add is emitted with a null value rather than an empty string.
 * - `score` → `criteria` is an ordered **array** of level descriptions; the publisher numbers them itself
 *   (`"level $index: …"`). A SCORE criterion therefore carries its text in `description`, falling back to `label`
 *   so that a caller who wrote `SystemOneCriterion("很需要")` gets what they meant.
 * - `noul` → `criteria` is `null`, and the publisher substitutes its own true/false option text. Passing the
 *   caller's criteria here would be a silent behaviour change, not an extension.
 *
 * @throws IllegalArgumentException when two CHOICE criteria share a label. The publisher keys its answer
 *   distribution by label, so a duplicate would collapse two criteria into one option while the graph still
 *   expects a marker per criterion — an ambiguous answer rather than a visible error.
 */
internal fun officialQuestion(question: SystemOneQuestion): Map<String, Any?> =
    linkedMapOf(
        "type" to when (question.type) {
            SystemOneQuestionType.CHOICE -> "choice"
            SystemOneQuestionType.SCORE -> "score"
            SystemOneQuestionType.NOUL -> "noul"
        },
        "instructions" to question.instructions,
        "criteria" to when (question.type) {
            SystemOneQuestionType.CHOICE -> choiceCriteria(question)
            SystemOneQuestionType.SCORE -> question.criteria.map { it.description ?: it.label }
            SystemOneQuestionType.NOUL -> null
        },
    )

/** `label -> description`, in the caller's order; `LinkedHashMap` because the publisher iterates it as the contract. */
private fun choiceCriteria(question: SystemOneQuestion): Map<String, Any?> {
    val labels = question.criteria.map { it.label }
    require(labels.toSet().size == labels.size) {
        "choice criterion labels must be unique: a duplicate would silently drop an option"
    }
    return LinkedHashMap<String, Any?>().apply {
        question.criteria.forEach { put(it.label, it.description) }
    }
}
