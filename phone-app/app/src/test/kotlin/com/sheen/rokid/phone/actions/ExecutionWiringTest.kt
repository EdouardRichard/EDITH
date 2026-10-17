package com.sheen.rokid.phone.actions
import com.sheen.rokid.phone.ui.capabilityReasonLabel
import com.sheen.rokid.phone.actions.executionChannelStatuses
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.phone.actions.shizuku.RootProbe
import com.sheen.rokid.phone.actions.shizuku.RootStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §17.2 rule 1: **a channel that is down is named on the capability panel**, with a stable code
 * (`A11Y_DISABLED` / `SHIZUKU_UNAVAILABLE` / `ROOT_UNAVAILABLE`) - and the Root row is a statement of fact about the
 * device rather than an error.
 *
 * This is the plan's `ExecutionWiringTest`, which T13 never wrote; without it the panel could (and did) advertise
 * 指令执行 as available while every L2-L4 tool had quietly vanished from the model's table, and nothing failed.
 *
 * The projection is pure so the rule is checkable here rather than only by looking at a phone. The composition feeds
 * it the **same probes the `P3_EXEC` lines use**, which is what keeps the panel and the log from disagreeing.
 */
class ExecutionWiringTest {

    @Test fun `every channel that is down is named with its own stable code`() {
        val rows = executionChannelStatuses(
            listOf("intent" to false, "accessibility" to false, "shizuku" to false, "root" to false),
        )
        assertEquals("A11Y_DISABLED", rows.first { it.id == "accessibility" }.reason)
        assertEquals("SHIZUKU_UNAVAILABLE", rows.first { it.id == "shizuku" }.reason)
        assertEquals("ROOT_UNAVAILABLE", rows.first { it.id == "root" }.reason)
        assertNull(
            "the intent channel has no unavailable state, so it must never invent a code",
            rows.first { it.id == "intent" }.reason,
        )
    }

    @Test fun `a channel that is up carries no reason`() {
        val rows = executionChannelStatuses(listOf("accessibility" to true, "shizuku" to true, "root" to true))
        assertTrue("an available channel must not carry a reason: $rows", rows.all { it.available && it.reason == null })
    }

    @Test fun `the root row shows the device's own line, because a missing su is a fact and not an error`() {
        val root = executionChannelStatuses(listOf("root" to false), rootNote = "Root：不可用（设备无 su）").single()
        assertEquals("ROOT_UNAVAILABLE", root.reason)
        assertEquals("Root：不可用（设备无 su）", root.note)
    }

    @Test fun `only the root row carries a note`() {
        val rows = executionChannelStatuses(
            listOf("accessibility" to false, "root" to true),
            rootNote = "Root：可用",
        )
        assertNull(rows.first { it.id == "accessibility" }.note)
        assertEquals("Root：可用", rows.first { it.id == "root" }.note)
    }

    @Test fun `the panel keeps the probe order and ids, so it cannot disagree with the P3_EXEC lines`() {
        val probes = listOf("intent" to true, "accessibility" to false, "shizuku" to true, "root" to false)
        val rows = executionChannelStatuses(probes)
        assertEquals(probes.map { it.first }, rows.map { it.id })
        assertEquals(probes.map { it.second }, rows.map { it.available })
    }

    @Test fun `an unknown channel surfaces without a code rather than borrowing someone else's`() {
        assertNull(executionChannelStatuses(listOf("bluetooth" to false)).single().reason)
    }

    @Test fun `the codes read as the wearer's own words on the panel`() {
        assertEquals("未启用无障碍", capabilityReasonLabel("A11Y_DISABLED"))
        assertEquals("Shizuku 未运行或未授权", capabilityReasonLabel("SHIZUKU_UNAVAILABLE"))
        assertEquals("设备无 su", capabilityReasonLabel("ROOT_UNAVAILABLE"))
    }

    @Test fun `the root row says why root is unavailable, and a silent su is never called absent`() {
        // Leftover-sweep review, Important 2: the row used to print "设备无 su" for *any* negative probe, so a rooted
        // phone whose owner ignored the permission prompt was told it had no `su` - a device fact it never observed.
        assertEquals("Root：不可用（设备无 su）", RootProbe.noteFor(RootStatus.ABSENT))
        assertFalse("a su that only failed to answer is not a missing binary",
            "设备无 su" in RootProbe.noteFor(RootStatus.NO_ANSWER))
        assertFalse("a su that answered as the shell identity is not a missing binary",
            "设备无 su" in RootProbe.noteFor(RootStatus.NOT_ROOT))
        assertFalse("设备无 su" in RootProbe.noteFor(RootStatus.AVAILABLE))
        assertTrue("the timeout line must still be an informational line, not an error: " +
            RootProbe.noteFor(RootStatus.NO_ANSWER),
            "不可用" in RootProbe.noteFor(RootStatus.NO_ANSWER))
    }
}
