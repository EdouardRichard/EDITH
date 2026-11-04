package com.sheen.rokid.phone.app

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartWiring
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.CatalogProfileRepository
import com.sheen.rokid.phone.storage.KeystoreCatalogCredentialStore
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ConfiguredPlugin
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import com.sheen.rokid.phone.bridge.CxrConnection
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.52 design §5.8 / ruling R13: the catalog projection is what the running host configures the plugin with.
 *
 * These tests drive the **host** half of the runtime switch - `activeCatalogProfile()`, `stopForModelSwitch()` and
 * `onCatalogSelectionChanged()` - against the real `AndroidModelCatalogRepository` over a JVM blob/credential double.
 * A recording `ConfiguredPlugin` under the smart plugin's id is enough to observe the configuration the host really
 * hands over, without activating the full assistant capability set.
 */
class CatalogWiringTest {

    // ---- catalog doubles ---------------------------------------------------------------------------------------

    /** The credential contract: `stage` takes ownership of the caller's buffer and must zero it. */
    private class Credentials : CatalogCredentialStore {
        override fun stage(value: CharArray): SecretHandle {
            val id = "credential-${UUID.randomUUID()}"
            value.fill('\u0000')
            return object : SecretHandle {
                override val id = id
                override fun toString() = "SecretHandle(redacted)"
            }
        }

        override fun revoke(handle: SecretHandle) = Unit
    }

    private fun catalog(): ModelCatalogRepository = AndroidModelCatalogRepository(TestBlobs(), Credentials())

    private fun entry(id: String) = ModelEntry(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        outputs = setOf(OutputModality.TEXT),
        origin = ModelOrigin.MANUAL,
    )

    private fun provider(id: String, vararg models: String) = Provider(
        id = id,
        name = "Provider $id",
        baseUrl = "https://synthetic.example/v1",
        models = models.map(::entry),
    )

    private fun save(catalog: ModelCatalogRepository, provider: Provider): Provider =
        catalog.saveProvider(provider, "synthetic-dummy-secret".toCharArray())

    // ---- the composition-shaped rig ----------------------------------------------------------------------------

    private class RecordingSmart : PluginLifecycle, ConfiguredPlugin {
        val configurations = CopyOnWriteArrayList<PluginConfiguration>()
        val statesAtConfigure = CopyOnWriteArrayList<SessionState?>()
        var stateOfHost: (() -> SessionState?)? = null
        override val descriptor = PluginDescriptor(
            "smart-assistant", "1.0.0", 1, "Smart Assistant",
            listOf(Capability.TextOutput), ConfigSchema(1),
        )

        override fun validate(configuration: PluginConfiguration) {
            require(configuration.schemaVersion == 1)
            require(!configuration.values["profileId"].isNullOrBlank())
        }

        override fun configure(configuration: PluginConfiguration) {
            statesAtConfigure += stateOfHost?.invoke()
            configurations += configuration
        }

        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) = Unit
    }

    /**
     * The debug diagnostic text plugin's shape: its own closed key set, no capabilities the rig has to provide.
     *
     * It is registered beside the smart plugin so a test can drive the host's diagnostic branch - the configuration
     * `diagnosticValues` builds - rather than the smart wiring's.
     */
    private class RecordingDiagnostic : PluginLifecycle, ConfiguredPlugin {
        val configurations = CopyOnWriteArrayList<PluginConfiguration>()
        override val descriptor = PluginDescriptor(
            "p2-diagnostic", "1.0.0", 1, "P2 Diagnostic",
            listOf(Capability.TextOutput), ConfigSchema(1),
        )

        override fun validate(configuration: PluginConfiguration) {
            require(configuration.schemaVersion == 1)
            require(configuration.values.keys.all { it in setOf("profileId", "profileRevision", "requestConsent", "inputMode") })
            require(!configuration.values["profileId"].isNullOrBlank())
            require((configuration.values["profileRevision"]?.toLongOrNull() ?: -1) >= 0)
        }

        override fun configure(configuration: PluginConfiguration) {
            configurations += configuration
        }

        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) = Unit
    }

    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class LiveDisplay : HintDevice {
        override fun show(assistance: Assistance, revision: Long, listener: (com.sheen.rokid.contracts.OutputStatus) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }

        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }

    private class FakeConnection : CxrConnection {
        override var ready = true
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? = null
        override fun sendCommand(json: String) = true
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
        override fun inputPortOrNull(): com.sheen.rokid.plugin.InputPort? = null
    }

    private class Rig(catalog: ModelCatalogRepository) : AutoCloseable {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val profiles = CatalogProfileRepository(catalog)
        val plugin = RecordingSmart()
        val diagnostic = RecordingDiagnostic()
        private val environment = CxrHostEnvironment(
            clock = clock,
            input = RecordingInputPort(),
            liveOutput = { LiveDisplay() },
            scheduler = scheduler,
            providers = mapOf(Capability.Model to InertModel()),
        )
        private val host = HostRuntime(1, environment)
        private val wiring = SmartWiring(
            SmartSettings(),
            profiles,
            clock,
            scheduler,
            activeProfile = { runCatching { catalog.catalog() }.getOrNull()?.activeProfile() },
        )
        val root = HostCompositionRoot(
            host, profiles, InertModel(), environment,
            smartWiring = wiring,
            modelCatalog = catalog,
            rokidConnectionFactory = { FakeConnection() },
        ).also { root ->
            plugin.stateOfHost = { root.state.value.session }
            root.registerPlugin(plugin)
            root.registerPlugin(diagnostic)
        }

        /** The production start path: the Activity is attached first, then the session start, then readiness. */
        fun start(pluginId: String = "smart-assistant") {
            root.attachCxrActivity(Any())
            root.start(pluginId)
            root.onSceneReady()
        }

        override fun close() = root.close()
    }

    // ---- tests -------------------------------------------------------------------------------------------------

    @Test
    fun `the vault admits a projected credential only through the catalog authority`() {
        // Ruling R13: `create` calls `resolver.authorize(catalog::isCredentialReferenced)`. This drives the same three
        // production pieces so a missing authority fails here, instead of on device as "every model request rejected
        // before HTTP". The projected handle is not in the vault's own owners manifest (the catalog owns it), so
        // without the authority `withSecret` refuses it.
        val blobs = TestBlobs()
        val vault = KeystoreVault(blobs)
        val catalog = AndroidModelCatalogRepository(blobs, KeystoreCatalogCredentialStore(vault))
        catalog.saveProvider(provider("provider-1", "m-1"), "synthetic-dummy-secret".toCharArray())
        catalog.setActiveProvider("provider-1")
        val secret = requireNotNull(catalog.catalog().activeProfile()).secret

        var readBeforeAuthority = false
        assertThrows(ContractError.ConfigurationRejected::class.java) {
            vault.withSecret(secret) { readBeforeAuthority = true }
        }
        assertFalse("an unreferenced handle must not reach the key", readBeforeAuthority)

        vault.authorize(catalog::isCredentialReferenced)

        var key: CharArray? = null
        vault.withSecret(secret) { key = it.copyOf() }
        assertEquals("synthetic-dummy-secret", String(requireNotNull(key)))
    }

    @Test
    fun `the plugin configuration names the derived provider model id`() {
        val catalog = catalog()
        save(catalog, provider("provider-1", "m-1"))
        catalog.setActiveProvider("provider-1")
        val rig = Rig(catalog)
        try {
            rig.root.onCatalogSelectionChanged(rig.root.activeCatalogProfile())

            val configuration = rig.plugin.configurations.last()
            // The projection's id is `providerId:modelId`, so a provider and a model cannot collide in the plugin's
            // profile lookup.
            assertEquals("provider-1:m-1", configuration.values["profileId"])
            assertEquals("1", configuration.values["profileRevision"])
            // The catalog profile is threaded into the **one** whole configuration, not sent as a second configure call:
            // the settings-derived keys must still be there.
            assertEquals("L1", configuration.values["maxLevel"])
            assertEquals("伊迪丝,edith", configuration.values["wakeWords"])
        } finally {
            rig.close()
        }
    }

    @Test
    fun `changing the active model reconfigures the plugin with the new revision`() {
        val catalog = catalog()
        save(catalog, provider("provider-1", "m-1"))
        catalog.setActiveProvider("provider-1")
        val rig = Rig(catalog)
        try {
            rig.root.onCatalogSelectionChanged(rig.root.activeCatalogProfile())
            assertEquals("provider-1:m-1", rig.plugin.configurations.last().values["profileId"])

            // A model-list change bumps the provider revision (design §5.2) and the selection moves inside it.
            catalog.saveModels(
                providerId = "provider-1",
                models = listOf(entry("m-1"), entry("m-2")),
                selectedModelId = "m-2",
            )
            val changed = requireNotNull(rig.root.activeCatalogProfile())
            assertEquals("provider-1:m-2", changed.id)
            assertEquals("a content change must bump the provider revision", 2L, changed.revision)

            rig.root.onCatalogSelectionChanged(changed)

            assertEquals("provider-1:m-2", rig.plugin.configurations.last().values["profileId"])
            assertEquals("2", rig.plugin.configurations.last().values["profileRevision"])
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a provider switch stops the running session before reconfiguring`() {
        val catalog = catalog()
        save(catalog, provider("provider-1", "m-1"))
        save(catalog, provider("provider-2", "m-3"))
        catalog.setActiveProvider("provider-1")
        val rig = Rig(catalog)
        try {
            rig.start()
            assertEquals("the session must be running for this test to mean anything",
                SessionState.Ready, rig.root.host.currentState)

            // The caller's sequence (Task 13's view model): stop first, then write the selection, then reconfigure.
            rig.root.stopForModelSwitch()
            assertEquals("stopping for a model switch pauses the running session",
                SessionState.Paused, rig.root.state.value.session)

            catalog.setActiveProvider("provider-2")
            rig.root.onCatalogSelectionChanged(rig.root.activeCatalogProfile())

            assertEquals("provider-2:m-3", rig.plugin.configurations.last().values["profileId"])
            assertEquals("the plugin is reconfigured only after the session was stopped",
                SessionState.Paused, rig.plugin.statesAtConfigure.last())
            assertNull("a reconfigure after the stop must be accepted", rig.root.state.value.errorCode)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the diagnostic plugin receives the active catalog model too`() {
        val catalog = catalog()
        save(catalog, provider("provider-1", "m-1"))
        catalog.setActiveProvider("provider-1")
        val rig = Rig(catalog)
        try {
            rig.start("p2-diagnostic")

            val configuration = requireNotNull(rig.diagnostic.configurations.lastOrNull()) {
                "the host must configure the diagnostic plugin from the catalog projection"
            }
            // The catalog is global (design §5.8): the diagnostic plugin's profile lookup must be able to resolve the
            // model by the id the configuration names, instead of keeping the contract-valid `__unselected__` forever.
            assertEquals("provider-1:m-1", configuration.values["profileId"])
            assertEquals("1", configuration.values["profileRevision"])
            assertEquals("the diagnostic plugin's closed key set is unchanged",
                setOf("profileId", "profileRevision", "requestConsent", "inputMode"), configuration.values.keys)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a text-only active model refuses the interview start before a session exists`() {
        val catalog = catalog()
        val textOnly = Provider(
            id = "provider-1",
            name = "Provider provider-1",
            baseUrl = "https://synthetic.example/v1",
            models = listOf(entry("m-text").copy(inputs = setOf(InputModality.TEXT))),
        )
        save(catalog, textOnly)
        catalog.setActiveProvider("provider-1")
        val rig = Rig(catalog)
        try {
            rig.root.attachCxrActivity(Any())
            rig.root.start("interview-assistant")

            // The image gate is a property of the one active model, not of the smart plugin's id: the interview
            // activation path must refuse the same text-only selection instead of starting a session under it.
            assertEquals("PROFILE_MODEL_NOT_MULTIMODAL", rig.root.state.value.errorCode)
            assertEquals("a refused start begins no session", SessionState.Idle, rig.root.host.currentState)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the diagnostic plugin keeps its placeholder with no active model`() {
        val rig = Rig(catalog())
        try {
            rig.start("p2-diagnostic")

            val configuration = requireNotNull(rig.diagnostic.configurations.lastOrNull()) {
                "the host must configure the diagnostic plugin even without a catalog model"
            }
            assertEquals("__unselected__", configuration.values["profileId"])
            assertEquals("0", configuration.values["profileRevision"])
        } finally {
            rig.close()
        }
    }

    @Test
    fun `deleting the active provider leaves the host with no profile`() {
        val catalog = catalog()
        save(catalog, provider("provider-1", "m-1"))
        catalog.setActiveProvider("provider-1")
        val rig = Rig(catalog)
        try {
            rig.root.onCatalogSelectionChanged(rig.root.activeCatalogProfile())
            assertEquals("provider-1:m-1", rig.plugin.configurations.last().values["profileId"])

            rig.root.stopForModelSwitch()
            catalog.deleteProvider("provider-1")

            assertNull("a deleted provider leaves no active catalog projection", rig.root.activeCatalogProfile())
            rig.root.onCatalogSelectionChanged(null)

            assertEquals("__unconfigured__", rig.plugin.configurations.last().values["profileId"])
            assertEquals("0", rig.plugin.configurations.last().values["profileRevision"])
        } finally {
            rig.close()
        }
    }
}
