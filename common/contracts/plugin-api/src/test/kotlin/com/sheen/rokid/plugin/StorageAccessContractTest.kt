package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.ContractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * `Capability.StorageAccess` — the plugin's narrow read path to a directory the user manages outside the app
 * sandbox (the SKILL library at `/sdcard/EDITH/skills/`).
 *
 * The capability is deliberately **not** folded into `Capability.Storage`: that one hands over a key/value namespace
 * inside the app's encrypted storage, and the two carry entirely different privacy and permission stories. A plugin
 * that wants one must not be silently granted the other.
 */
class StorageAccessContractTest {

    private class FakeAccess : StorageAccess {
        override fun listFiles(dirPath: String, suffix: String): List<String> = listOf("$dirPath/one$suffix")
        override fun readFile(path: String): String? = "body of $path"
        override fun deleteFile(path: String): Boolean = true
    }

    /** Host that grants nothing and records which capability the accessor asked for. */
    private class UngrantedServices : HostServices {
        val requested = mutableListOf<Capability>()
        override fun require(capability: Capability): Any {
            requested += capability
            throw ContractError.CapabilityMissing("capability not granted: $capability")
        }
        override val tasks: TaskScope = EmptyHostServices.tasks
    }

    @Test
    fun `storageAccess returns the granted provider`() {
        val provider = FakeAccess()
        val services = object : HostServices {
            override fun require(capability: Capability): Any {
                assertEquals(Capability.StorageAccess, capability)
                return provider
            }
            override val tasks: TaskScope = EmptyHostServices.tasks
        }

        assertSame(provider, services.storageAccess())
    }

    @Test
    fun `a missing StorageAccess is reported as CapabilityMissing and asks for its own capability`() {
        val services = UngrantedServices()

        assertFailsWith<ContractError.CapabilityMissing> { services.storageAccess() }
        // Not `Storage`: the two are separate grants and a plugin must not receive one by asking for the other.
        assertEquals(listOf(Capability.StorageAccess), services.requested)
    }

    @Test
    fun `the three operations pass their arguments through unchanged`() {
        // A contract test, not a behaviour test: what this pins is that the port is exactly these three operations
        // with these parameter names and types, and that it does not rewrite the caller's path. Path joining and
        // permission handling belong to the implementation, and are tested where the implementation lives.
        val calls = mutableListOf<String>()
        val access = object : StorageAccess {
            override fun listFiles(dirPath: String, suffix: String): List<String> {
                calls += "list:$dirPath|$suffix"
                return emptyList()
            }

            override fun readFile(path: String): String? {
                calls += "read:$path"
                return null
            }

            override fun deleteFile(path: String): Boolean {
                calls += "delete:$path"
                return false
            }
        }

        access.listFiles("/sdcard/EDITH/skills", ".md")
        access.readFile("/sdcard/EDITH/skills/a.md")
        access.deleteFile("/sdcard/EDITH/skills/a.md")

        assertEquals(
            listOf(
                "list:/sdcard/EDITH/skills|.md",
                "read:/sdcard/EDITH/skills/a.md",
                "delete:/sdcard/EDITH/skills/a.md",
            ),
            calls,
        )
    }

    @Test
    fun `the capability only appends to the enum`() {
        // The guard is append-only: inserting or reordering would renumber every existing entry for a plugin that
        // compared ordinals. edith-0.2 Phase C appended `ConversationDisplay`, edith-0.3 Phase 1 appended
        // `DeviceState`, and edith-0.3 Phase 2 appended `Perception`. (This is the second such guard, the other being
        // `PluginApiValidationTest.capability enum keeps the declared order`; both list the full order, so both have to
        // be updated by whoever appends.)
        assertEquals(
            listOf(
                "Audio", "Asr", "Speaker", "Model", "Storage", "TextOutput", "SpeechOutput",
                "Actions", "Context", "WakeWord", "Confirmation", "SystemOne", "Embedding", "StorageAccess",
                "ConversationDisplay", "DeviceState", "Perception",
            ),
            Capability.entries.map { it.name },
        )
    }
}
