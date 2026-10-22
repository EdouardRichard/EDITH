package com.sheen.rokid.phone.plugin.smarthelper.host.ui

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.audit.AuditTrail
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings page's window onto the audit trail (spec §17.5's "可查可删").
 *
 * The document is the plugin's (`AuditTrail`), so this class is only a reader: it renders the fields that are in there
 * and nothing else. The test pins the two halves of that promise - the key is the plugin's own constant, and the
 * rendered text cannot contain a transcript, a screen line or a phone number, because the document cannot.
 */
class AuditLogViewTest {

    private class FakeStorage : StorageHandle {
        val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private fun document(vararg lines: String): ByteArray = buildJsonObject {
        put("version", 1)
        put("lines", buildJsonArray { lines.forEach { add(kotlinx.serialization.json.Json.parseToJsonElement(it)) } })
    }.toString().toByteArray(Charsets.UTF_8)

    private fun line(
        timestamp: Long = 1_700_000_000_000L,
        turnId: String = "t1",
        action: String = "app.uninstall",
        summary: String = "package=edith.phone.a11yprobe",
        outcome: String = "ok",
        scope: String = "app",
        risk: String = "L3",
        decision: String = "require_confirmation",
        source: String = "PHONE",
    ) = buildJsonObject {
        put("timestampMillis", timestamp)
        put("turnId", turnId)
        put("action", action)
        put("summary", summary)
        put("outcome", outcome)
        put("scope", scope)
        put("risk", risk)
        put("decision", decision)
        put("confirmationSource", source)
    }.toString()

    private fun view(storage: StorageHandle) = AuditLogView(storage)

    @Test
    fun theViewReadsTheVeryKeyThePluginWrites() {
        assertEquals(AuditTrail.KEY, AuditLogView.KEY)
        assertEquals("audit.log", AuditLogView.KEY)
    }

    @Test
    fun linesRenderTheStructuredFieldsAndTheRedactedSummary() {
        val storage = FakeStorage()
        storage.write(AuditLogView.KEY, document(line()))

        val rendered = view(storage).lines().single()

        assertTrue("the action must be visible: $rendered", "app.uninstall" in rendered)
        assertTrue("the risk level must be visible: $rendered", "L3" in rendered)
        assertTrue("the outcome must be visible: $rendered", "ok" in rendered)
        assertTrue("the target the executor declared safe must be visible: $rendered",
            "package=edith.phone.a11yprobe" in rendered)
        assertTrue("the confirming channel must be visible: $rendered", "PHONE" in rendered)
    }

    @Test
    fun linesAreNewestFirstAndCapped() {
        val storage = FakeStorage()
        storage.write(
            AuditLogView.KEY,
            document(
                line(turnId = "t1", action = "first", timestamp = 1_000L),
                line(turnId = "t2", action = "second", timestamp = 2_000L),
                line(turnId = "t3", action = "third", timestamp = 3_000L),
            ),
        )

        val all = view(storage).lines()
        assertEquals(listOf("third", "second", "first"), all.map { it.substringAfter(" | ").substringBefore(" | ") })
        assertEquals(2, view(storage).lines(limit = 2).size)
    }

    @Test
    fun aCorruptDocumentRendersNoLinesRatherThanThrowing() {
        val storage = FakeStorage()
        storage.write(AuditLogView.KEY, "not json".toByteArray())

        assertTrue(view(storage).lines().isEmpty())

        storage.write(AuditLogView.KEY, buildJsonObject { put("version", 99) }.toString().toByteArray())
        assertTrue("a document from another version must not be guessed at", view(storage).lines().isEmpty())
    }

    @Test
    fun clearDeletesTheDocumentAndNothingElse() {
        val storage = FakeStorage()
        storage.write(AuditLogView.KEY, document(line()))
        storage.write("namespace-smart-assistant", "keep me".toByteArray())

        view(storage).clear()

        assertNull(storage.read(AuditLogView.KEY))
        assertTrue("clearing the audit must not touch the plugin's other data",
            storage.read("namespace-smart-assistant") != null)
        assertTrue(view(storage).lines().isEmpty())
    }

    @Test
    fun anUnknownDocumentShapeRendersNothing() {
        val storage = FakeStorage()
        storage.write(AuditLogView.KEY, document("""{"turnId":"t1"}"""))

        assertTrue(view(storage).lines().isEmpty())
    }

    @Test
    fun theRenderedLineCarriesNoTranscriptScreenTextOrNumber() {
        val storage = FakeStorage()
        storage.write(
            AuditLogView.KEY,
            document(line(action = "dial", summary = "", scope = "phone.number", risk = "L2", source = "VOICE")),
        )

        val rendered = view(storage).lines().single()
        assertFalse("a phone number must never reach this page: $rendered", "138" in rendered)
        assertFalse("no transcript text: $rendered", "面试官" in rendered)
        assertFalse("no screen text: $rendered", "WLAN" in rendered)
    }
}
