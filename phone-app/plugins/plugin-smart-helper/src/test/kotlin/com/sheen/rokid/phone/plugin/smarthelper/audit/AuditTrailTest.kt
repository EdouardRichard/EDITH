package com.sheen.rokid.phone.plugin.smarthelper.audit
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeClock

import com.sheen.rokid.agent.BoundedAuditLog
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.plugin.StorageHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [`AuditTrail`] is spec §17.5's local encrypted store: the plugin's own namespace holds one bounded JSON document of
 * structured lines. The in-memory ring stays P3.1's [`BoundedAuditLog`]; this test owns the two properties that make
 * the store honest - a line survives a new trail over the same storage, and **nothing but the summary it was handed**
 * is ever written.
 */
class AuditTrailTest {

    private val clock = SmartFakeClock()

    private fun trail(storage: StorageHandle? = SmartFakeStorage(), capacity: Int = 200) =
        AuditTrail(storage, BoundedAuditLog(8, clock), capacity = capacity, wallClock = { 1_700_000_000_000L })

    @Test fun `a line survives a new trail over the same storage`() {
        val storage = SmartFakeStorage()
        trail(storage).record(
            "turn-1", "app.uninstall", "package=edith.phone.a11yprobe", "ok",
            scope = "app", risk = "L3", decision = "require_confirmation",
            confirmationSource = ConfirmationSource.PHONE,
        )

        val reloaded = trail(storage)

        assertEquals(1, reloaded.entries().size)
        val entry = reloaded.entries().single()
        assertEquals("turn-1", entry.turnId)
        assertEquals("app.uninstall", entry.action)
        assertEquals("package=edith.phone.a11yprobe", entry.summary)
        assertEquals("app", entry.scope)
        assertEquals("L3", entry.risk)
        assertEquals("require_confirmation", entry.decision)
        assertEquals(ConfirmationSource.PHONE, entry.confirmationSource)
    }

    @Test fun `the stored document is bounded`() {
        val storage = SmartFakeStorage()
        val trail = trail(storage, capacity = 3)

        (1..5).forEach { trail.record("turn-$it", "action", "s$it", "ok") }

        assertEquals(listOf("turn-3", "turn-4", "turn-5"), trail(storage, capacity = 3).entries().map { it.turnId })
    }

    @Test fun `clear removes the key as well as the memory`() {
        val storage = SmartFakeStorage()
        val trail = trail(storage)
        trail.record("turn-1", "action", "s", "ok")

        trail.clear()

        assertNull(storage.read(AuditTrail.KEY), "clearing the trail must delete the stored document")
        assertTrue(trail.entries().isEmpty())
        assertTrue(trail(storage).entries().isEmpty(), "a fresh trail must not resurrect what clear removed")
    }

    @Test fun `a corrupt document reads as empty instead of throwing`() {
        val storage = SmartFakeStorage()
        storage.write(AuditTrail.KEY, "not json at all".toByteArray())

        assertTrue(trail(storage).entries().isEmpty())
    }

    @Test fun `no raw arguments are written, only the summary it was given`() {
        val storage = SmartFakeStorage()
        trail(storage).record("turn-1", "dial", "", "ok")

        val raw = String(storage.read(AuditTrail.KEY)!!, Charsets.UTF_8)
        assertFalse("tel:" in raw)
        assertFalse("12345678901" in raw)
        assertTrue("dial" in raw)
    }

    @Test fun `without storage the trail is memory only and still records`() {
        val trail = trail(storage = null)

        trail.record("turn-1", "action", "s", "ok")

        assertEquals(1, trail.entries().size)
    }

    @Test fun `attach seeds once and never duplicates an existing line`() {
        val storage = SmartFakeStorage()
        trail(storage).record("turn-1", "action", "s", "ok")
        val trail = trail(storage = null)

        trail.attach(storage)
        trail.attach(storage)

        assertEquals(listOf("turn-1"), trail.entries().map { it.turnId })
    }
}
