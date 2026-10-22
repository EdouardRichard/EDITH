package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.WakeWordRegistry
import com.sheen.rokid.plugin.wakeWord
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.testsupport.FakeTaskScope
import com.sheen.rokid.host.ScopedHostServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14a item 2: the phone-side `Capability.WakeWord` provider.
 *
 * The provider exists so the smart plugin's declared `WakeWord` capability is **answerable**, which is what keeps the
 * advertised set and the answerable set equal. Its contents are the configured word list, and the plugin declares its
 * own list on activation - so the registry must accept that declaration without ever letting a blank or a duplicate
 * become a word the audio layer would try to match.
 */
class SmartWakeWordRegistryTest {

    @Test
    fun theConfiguredWordsAreCurrentInInsertionOrderWithoutBlanksOrDuplicates() {
        val registry = SmartWakeWordRegistry(listOf(" 伊迪丝 ", "", "edith", "  ", "edith"))

        assertEquals(listOf("伊迪丝", "edith"), registry.current())
    }

    @Test
    fun aDeclaredListReplacesTheCurrentWordsAndStaysDeDuplicated() {
        val registry = SmartWakeWordRegistry(listOf("伊迪丝"))

        assertTrue(registry.declare(listOf("edith", "edith", " ", "小助手")))

        assertEquals(listOf("edith", "小助手"), registry.current())
    }

    @Test
    fun aDeclarationWithNoUsableWordIsRefusedAndLeavesTheCurrentWordsUnchanged() {
        val registry = SmartWakeWordRegistry(listOf("伊迪丝"))

        assertFalse(registry.declare(listOf(" ", "")))
        assertFalse(registry.declare(emptyList()))

        assertEquals(listOf("伊迪丝"), registry.current())
    }

    @Test
    fun currentHandsOutACopySoACallerCannotRewriteTheRegistry() {
        val registry = SmartWakeWordRegistry(listOf("伊迪丝"))
        val first = registry.current()
        val second = registry.current()

        // The contents and order are pinned; a caller receives a fresh list rather than the registry's own storage.
        assertEquals(listOf("伊迪丝"), first)
        assertEquals(first, second)
        assertFalse("each call must produce an independent snapshot", first === second)
        assertEquals("the registry must not be affected by a caller's lifecycle", listOf("伊迪丝"), registry.current())
    }

    @Test
    fun thePluginResolvesTheRegistryThroughTheHostCapabilityGate() {
        val registry = SmartWakeWordRegistry(listOf("伊迪丝", "edith"))
        val services = ScopedHostServices(
            ResourceScope("smart-assistant", SessionId("s-1"), Generation(1)),
            setOf(Capability.WakeWord),
            mapOf(Capability.WakeWord to registry),
            FakeTaskScope(),
        )

        val declared = services.wakeWord()

        assertTrue(declared.declare(listOf("edith", "小助手")))
        assertEquals(listOf("edith", "小助手"), registry.current())
    }

    /** A plugin-facing provider must satisfy the host's own interface, not a look-alike. */
    @Test
    fun theRegistryIsAPluginFacingWakeWordRegistry() {
        val registry: WakeWordRegistry = SmartWakeWordRegistry(listOf("edith"))

        assertEquals(listOf("edith"), registry.current())
    }
}
