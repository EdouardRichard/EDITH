package com.sheen.rokid.phone.plugin.smarthelper.proactive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P3.5 §W3: the admission features, as a pure function.
 *
 * These tests pin what each feature *means*, one behaviour each. The calibration gate (does the rule reach §11.3's
 * numbers on the frozen corpus) lives in `ProactiveAdmissionCorpusTest`, deliberately separate: these say "the feature
 * fires when a person would say it fires", that one says "the rule as a whole is good enough".
 *
 * Assertion messages come last (`kotlin.test`).
 */
class ProactiveAdmissionTest {

    @Test
    fun `a short informational question fires the content and question features`() {
        val features = admissionFeatures("现在几点了", foreground = null)

        assertTrue(AdmissionFeature.CONTENT_NEED in features.fired, "几点 is a content need: ${features.fired}")
        assertTrue(AdmissionFeature.TIME in features.fired, "几点 is also a time term: ${features.fired}")
        assertFalse(AdmissionFeature.CHAT_PARTICLE in features.fired, "a real question is not filler: ${features.fired}")
    }

    @Test
    fun `a chatty echo does not fire the content need`() {
        val features = admissionFeatures("你说呢", foreground = null)

        assertFalse(AdmissionFeature.CONTENT_NEED in features.fired, "nothing is being asked *about*: ${features.fired}")
        assertTrue(AdmissionFeature.QUESTION_FORM in features.fired, "it does look like a question: ${features.fired}")
    }

    @Test
    fun `pure filler is its own feature and fires nothing else`() {
        val features = admissionFeatures("哈哈哈哈哈", foreground = null)

        assertTrue(AdmissionFeature.CHAT_PARTICLE in features.fired, "laughter is filler: ${features.fired}")
        assertFalse(AdmissionFeature.CONTENT_NEED in features.fired, "and carries no need: ${features.fired}")
    }

    @Test
    fun `a request addressed to the assistant fires the imperative`() {
        val features = admissionFeatures("帮我查一下这个", foreground = null)

        assertTrue(AdmissionFeature.IMPERATIVE in features.fired, "帮我查 is a request: ${features.fired}")
    }

    @Test
    fun `the foreground app anchors the sentence in the language it is spoken`() {
        // The transcript is Chinese and the package is Latin, so comparing leaf names would never match on a real
        // phone. The anchor is what connects a short phrase to what is on screen.
        val features = admissionFeatures("设置里怎么改字体", foreground = "com.android.settings")

        assertTrue(
            AdmissionFeature.FOREGROUND_ANCHOR in features.fired,
            "设置 is on screen and named: ${features.fired}",
        )
    }

    @Test
    fun `an app outside the known labels anchors nothing rather than guessing`() {
        val features = admissionFeatures("设置里怎么改字体", foreground = "com.example.unknown")

        assertFalse(
            AdmissionFeature.FOREGROUND_ANCHOR in features.fired,
            "an unrecognised package contributes no label: ${features.fired}",
        )
    }

    @Test
    fun `with no foreground app the anchor never fires and nothing replaces it`() {
        val features = admissionFeatures("这个页面怎么返回", foreground = null)

        assertFalse(
            AdmissionFeature.FOREGROUND_ANCHOR in features.fired,
            "an unread foreground cannot be assumed: ${features.fired}",
        )
    }

    @Test
    fun `length is reported as a feature and never as a refusal`() {
        val short = admissionFeatures("几点了", foreground = null)
        val long = admissionFeatures("这个东西用英文怎么说啊到底应该怎么讲", foreground = null)

        assertFalse(AdmissionFeature.LENGTH in short.fired, "short: ${short.fired}")
        assertTrue(AdmissionFeature.LENGTH in long.fired, "long: ${long.fired}")
        assertEquals(long.normalizedLength > short.normalizedLength, true, "length is measured, not judged")
    }

    /**
     * `PRONOUN_SELF` matches 我 *and* 咱.
     *
     * This test exists because the first version wrote the 我 pattern as a **regex string** and matched it with the
     * literal `String.contains`, so the feature could only ever fire on 咱 - a defect that no other test noticed,
     * because this feature is corroboration rather than a decision on its own. Pinning both halves is the only thing
     * that keeps the two spellings honest.
     */
    @Test
    fun `the first person feature matches both spellings`() {
        assertTrue(
            AdmissionFeature.PRONOUN_SELF in admissionFeatures("我该怎么办", foreground = null).fired,
            "我 is first person",
        )
        assertTrue(
            AdmissionFeature.PRONOUN_SELF in admissionFeatures("咱走吧", foreground = null).fired,
            "咱 is first person",
        )
    }

    /** The negative lookbehind is the whole reason 我 is not a plain substring: "你吃了吗" is not the speaker's need. */
    @Test
    fun `a second person sentence is not first person`() {
        assertFalse(
            AdmissionFeature.PRONOUN_SELF in admissionFeatures("你吃了吗", foreground = null).fired,
            "a question addressed to someone else is not mine",
        )
    }
}
