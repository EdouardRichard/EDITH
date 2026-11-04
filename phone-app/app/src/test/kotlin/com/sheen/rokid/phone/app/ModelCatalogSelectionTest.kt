package com.sheen.rokid.phone.app

import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.CatalogProfileRepository
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * 0.52 design §5.9 / ruling R19: the active catalog projection follows a provider switch exactly as the repository
 * decides it - the provider's last selected model is restored when it still exists, and a provider with no history
 * falls back to its first **image-capable** model.
 *
 * The repository's own storage rule is pinned by `AndroidModelCatalogRepositoryTest`. This suite is the host's half:
 * a live `HostCompositionRoot` must answer `activeCatalogProfile()` from the catalog on every call, so a switch made
 * after assembly reaches the next configuration/turn without an App restart.
 */
class ModelCatalogSelectionTest {

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

    private fun entry(id: String, image: Boolean = true) = ModelEntry(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = if (image) setOf(InputModality.TEXT, InputModality.IMAGE) else setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        origin = ModelOrigin.MANUAL,
    )

    private fun provider(id: String, vararg models: ModelEntry) = Provider(
        id = id,
        name = "Provider $id",
        baseUrl = "https://synthetic.example/v1",
        models = models.toList(),
    )

    private fun save(catalog: ModelCatalogRepository, provider: Provider) {
        catalog.saveProvider(provider, "synthetic-dummy-secret".toCharArray())
    }

    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class Root(catalog: ModelCatalogRepository) : AutoCloseable {
        private val profiles = CatalogProfileRepository(catalog)
        val root = HostCompositionRoot(
            HostRuntime(1), profiles, InertModel(), null,
            modelCatalog = catalog,
        )

        override fun close() = root.close()
    }

    @Test
    fun `switching provider restores its last selected model`() {
        val catalog = catalog()
        save(catalog, provider("p-1", entry("m-1")))
        save(catalog, provider("p-2", entry("m-3"), entry("m-4")))
        catalog.setActiveProvider("p-1")
        catalog.setActiveProvider("p-2")
        catalog.selectModel("m-4")
        catalog.setActiveProvider("p-1")

        val rig = Root(catalog)
        try {
            assertEquals("p-1:m-1", rig.root.activeCatalogProfile()?.id)

            // The switch happens while the composition is live; the projection must not be an assembly-time snapshot.
            catalog.setActiveProvider("p-2")

            assertEquals("a provider switch restores that provider's last selected model",
                "p-2:m-4", rig.root.activeCatalogProfile()?.id)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `switching to a provider with no history selects the first image capable model`() {
        val catalog = catalog()
        save(catalog, provider("p-1", entry("m-1")))
        // The text-only row comes first on purpose: the selection rule is "first image capable", not "first model".
        save(catalog, provider(
            "p-2",
            entry("m-text", image = false),
            entry("m-vision", image = true),
        ))
        catalog.setActiveProvider("p-1")

        val rig = Root(catalog)
        try {
            catalog.setActiveProvider("p-2")

            assertEquals("p-2:m-vision", rig.root.activeCatalogProfile()?.id)
            assertEquals("a provider without history must not pick the text-only row",
                "m-vision", rig.root.activeCatalogProfile()?.model)
        } finally {
            rig.close()
        }
    }
}
