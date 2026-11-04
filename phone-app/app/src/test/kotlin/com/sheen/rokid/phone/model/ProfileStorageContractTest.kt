package com.sheen.rokid.phone.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.model.CapabilityGate
import com.sheen.rokid.model.ContextBudgeter
import com.sheen.rokid.model.ControlledModelClient
import com.sheen.rokid.model.ControlledProfileTester
import com.sheen.rokid.model.RequestConsent
import com.sheen.rokid.phone.model.catalog.CatalogCredentialStore
import com.sheen.rokid.phone.model.catalog.ModelCatalogRepository
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.CatalogProfileRepository
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.storage.SecretResolver
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * The storage/probe boundary the settings pages drive, now over the catalog.
 *
 * 0.52 moved the persisted model configuration from the retired profile repository into the encrypted catalog, whose
 * `ModelProfile` is derived. These two contracts still have to hold for the production probe gate and the controlled
 * client: a profile cannot change between the user's confirmation and the probe's admission, and an unsupported
 * protocol refuses before any secret is resolved or any HTTP request is sent.
 */
class ProfileStorageContractTest {

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

    private fun repository(): ModelCatalogRepository =
        AndroidModelCatalogRepository(TestBlobs(), Credentials())

    private fun provider(
        id: String = "provider-1",
        baseUrl: String = "https://synthetic.example/v1",
        protocolId: String = "DEEPSEEK_RESPONSES",
    ) = Provider(
        id = id,
        name = "Synthetic",
        baseUrl = baseUrl,
        protocolId = protocolId,
        models = listOf(
            ModelEntry(
                id = "m-1",
                displayName = "Model m-1",
                contextWindowTokens = 131_072,
                maxOutputTokens = 8_192,
                outputs = setOf(OutputModality.TEXT),
                origin = ModelOrigin.MANUAL,
            ),
        ),
    )

    @Test
    fun profileChangingBetweenProbeConfirmationAndClientAdmissionCannotUpload() {
        val catalog = repository()
        val saved = catalog.saveProvider(provider(), "synthetic-dummy-secret".toCharArray())
        val profiles = CatalogProfileRepository(catalog)
        val profile = requireNotNull(profiles.get("${saved.id}:m-1"))
        val gate = ProfileProbeGate(profiles)

        gate.grant(ProbeConsent(profile.id, profile.revision, "synthetic.example"))
        // A provider edit bumps the revision (and clears evidence); the pending consent must stop matching.
        catalog.saveProvider(saved.copy(baseUrl = "https://changed.example/v1"), null)

        assertFalse(gate.requestConsent().text)
        assertFalse(gate.requestConsent().syntheticProbe)
    }

    @Test
    fun unsupportedSavedProtocolFailsBothProbeAndBusinessBeforeSecretOrTransport() {
        val catalog = repository()
        val saved = catalog.saveProvider(provider(protocolId = "CUSTOM"), "synthetic-dummy-secret".toCharArray())
        val profiles = CatalogProfileRepository(catalog)
        val profileId = "${saved.id}:m-1"
        var resolutions = 0
        var sends = 0
        val resolver = object : SecretResolver {
            override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit) {
                resolutions++
                throw AssertionError("unsupported credential admitted")
            }

            override fun revoke(handle: SecretHandle) = Unit
        }
        val http = OkHttpClient.Builder()
            .addInterceptor { sends++; throw AssertionError("unsupported request sent") }
            .build()
        val clock = FakeClock()
        val client = ControlledModelClient(
            http,
            profiles,
            resolver,
            CapabilityGate(),
            ContextBudgeter(),
            clock,
            ManualScheduler(clock),
            { RequestConsent(true, true, true, true) },
        )
        try {
            val request = ModelRequest(
                EventEnvelope(SessionId("synthetic"), Generation(1), "synthetic-event", 0, 0),
                "synthetic-request",
                "synthetic-turn",
                profileId,
                saved.revision,
                "synthetic instructions",
                listOf(InputPart.Text("synthetic text")),
                emptyList(),
                OutputModality.TEXT,
                Long.MAX_VALUE,
            )
            assertThrows(ContractError.CapabilityMissing::class.java) { client.request(request) {} }
            assertThrows(ContractError.CapabilityMissing::class.java) {
                ControlledProfileTester(client, profiles, clock).testText(profileId) {}
            }
            assertEquals(0, resolutions)
            assertEquals(0, sends)
        } finally {
            client.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
