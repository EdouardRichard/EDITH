package com.sheen.rokid.phone.plugin.smarthelper.prompt

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prompt resources are plain UTF-8 classpath resources, not Android resources (spec §4.1); a missing
 * resource must fail loudly so a packaged APK can never silently ship an empty system prompt.
 */
class PromptDocumentTest {

    @Test
    fun `load reads a classpath resource as UTF-8`() {
        val text = PromptDocument.load("prompt-test/fixture.md")

        assertTrue("第一行" in text, "non-ASCII text must survive the UTF-8 decode")
        assertTrue("second line" in text)
        assertFalse('\uFFFD' in text, "a replacement character means the resource was not decoded as UTF-8")
    }

    @Test
    fun `load throws when the resource is missing`() {
        assertFailsWith<IllegalArgumentException> {
            PromptDocument.load("prompt-test/does-not-exist.md")
        }
    }
}
