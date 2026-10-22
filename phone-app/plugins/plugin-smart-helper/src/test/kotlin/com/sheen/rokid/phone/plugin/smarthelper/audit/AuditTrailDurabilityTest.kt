package com.sheen.rokid.phone.plugin.smarthelper.audit
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeClock

import com.sheen.rokid.agent.BoundedAuditLog
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.plugin.StorageHandle
import kotlin.test.*

/**
 * §17.5 promises the audit is 可查**可删**. Two ways that promise was broken, both found by review, both about the fact
 * that the durable document has *two* hands on it: the plugin's [AuditTrail] writes it, and the App's settings page
 * ([com.sheen.rokid.phone.AuditLogView]) reads and deletes it. In production they are one process over one encrypted namespace.
 *
 * 1. **a deleted line came back.** The page deleted the document, but the plugin's ring still held every line, so the
 *    next recorded action re-encoded the whole ring and resurrected what the user had deleted - silently, in the same
 *    App run, and over privacy-relevant identifiers.
 * 2. **every activation duplicated the document.** `attach` runs on each `activate` and production hands a **fresh**
 *    scoped storage wrapper each time; seeding was guarded on the storage *instance*, so the second activation appended
 *    the document to a ring that already contained it, and the growth compounded until the cap filled with copies.
 *
 * The file is the durable record; the ring follows it. These tests pin that direction.
 */
class AuditTrailDurabilityTest {

    /** A second handle over the SAME namespace document: exactly what a new activation's scoped wrapper is. */
    private class SameNamespace(private val backing: SmartFakeStorage) : StorageHandle {
        override fun read(key: String): ByteArray? = backing.values[key]
        override fun write(key: String, value: ByteArray) { backing.values[key] = value }
        override fun delete(key: String) { backing.values.remove(key) }
        override fun clear() = backing.values.clear()
    }

    private fun document(storage: SmartFakeStorage): String =
        String(storage.values.getValue(AuditTrail.KEY), Charsets.UTF_8)

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test fun `a document deleted from under the trail is not resurrected by the next recorded action`() {
        val namespace = SmartFakeStorage()
        val trail = AuditTrail(namespace, BoundedAuditLog(500, SmartFakeClock()))
        trail.record("t1", "open_settings", "", "ok", null, "L0", "allow", null)
        assertTrue(occurrences(document(namespace), "open_settings") == 1, "the first line must be recorded")

        // The settings page's 清空审计记录 deletes the document.
        namespace.delete(AuditTrail.KEY)

        // The next action is recorded into the same namespace.
        trail.record("t2", "dial", "", "CONFIRMATION_DENIED", null, "L2", "require_confirmation",
            ConfirmationSource.PHONE)

        val after = document(namespace)
        assertTrue(occurrences(after, "dial") == 1, "the new action must be recorded")
        assertEquals(0, occurrences(after, "open_settings"),
            "a line the user deleted must not come back on the next recorded action")
    }

    @Test fun `re-attaching with a fresh handle over the same namespace does not duplicate the document`() {
        val namespace = SmartFakeStorage()
        val trail = AuditTrail(namespace, BoundedAuditLog(500, SmartFakeClock()))
        trail.record("t1", "open_settings", "", "ok", null, "L0", "allow", null)
        assertEquals(1, occurrences(document(namespace), "open_settings"))

        // A second activation: a brand-new scoped wrapper over the same namespace, as production builds it.
        trail.attach(SameNamespace(namespace))

        assertEquals(1, occurrences(document(namespace), "open_settings"),
            "re-activation must not append the document to itself")
        assertEquals(1, trail.entries().count { it.action == "open_settings" },
            "the live ring must hold the line once, not twice")
    }
}
