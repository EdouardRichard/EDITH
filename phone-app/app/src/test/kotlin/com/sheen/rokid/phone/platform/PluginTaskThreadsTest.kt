package com.sheen.rokid.phone.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.3 review, Minor 4: both threads of the plugin task pool were called `edith-plugin-tasks`, where the plan that built
 * the pool said `edith-plugin-tasks-N`.
 *
 * This is not cosmetics: P3.3's Critical ANR (a confirmation answer deadlocking against the blocked turn) was diagnosed
 * off a thread dump, and two threads sharing one name is exactly what makes "which of these is blocked on the question"
 * unanswerable there.
 */
class PluginTaskThreadsTest {

    @Test
    fun poolThreadsAreNamedApartSoAThreadDumpCanTellThemApart() {
        val factory = pluginTaskThreadFactory()

        val threads = (1..2).map { factory.newThread {} }

        assertEquals(listOf("edith-plugin-tasks-1", "edith-plugin-tasks-2"), threads.map { it.name })
        assertTrue("plugin work must never keep the process alive", threads.all { it.isDaemon })
    }
}
