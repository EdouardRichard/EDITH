package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.storage.MemorySecretResolver
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import kotlin.test.*

/**
 * 0.52 design §5.4: the gateway `GET /models` client. The tests drive the real blocking `fetch` against
 * MockWebServer; only the credential store is a fake-compatible in-memory implementation (`MemorySecretResolver`).
 */
class ModelListClientTest {
    private lateinit var server: MockWebServer
    private lateinit var resolver: MemorySecretResolver
    private lateinit var secret: SecretHandle

    @BeforeTest fun startServer() {
        // The local-HTTP rule only admits a loopback host; the default MockWebServer URL can resolve to a
        // machine-specific canonical host name on this platform, so the test pins both ends to 127.0.0.1.
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        resolver = MemorySecretResolver()
        secret = resolver.put("sk-test-key".toCharArray())
    }

    @AfterTest fun stopServer() {
        server.shutdown()
        resolver.close()
    }

    @Test fun `data array models parse optional context output modalities and effort`() {
        server.enqueue(jsonResponse("""
            {
              "object": "list",
              "data": [
                {
                  "id": "deepseek-chat",
                  "object": "model",
                  "name": "DeepSeek Chat",
                  "owned_by": "deepseek",
                  "context_window": 65536,
                  "max_output_tokens": 8192,
                  "input_modalities": ["text", "image"],
                  "effort": {"supported_levels": ["low", "high"], "default_level": "low"}
                },
                {"id": "vendor-reasoner", "effort": {"supported_levels": ["high"]}},
                {"id": "plain", "object": "model"}
              ]
            }
        """.trimIndent()))

        val success = client().fetch(baseUrl(), secret).success()

        assertEquals(3, success.models.size)
        assertFalse(success.truncated)
        with(success.models[0]) {
            assertEquals("deepseek-chat", id)
            assertEquals("DeepSeek Chat", displayName)
            assertEquals(65536, contextWindowTokens)
            assertEquals(8192, maxOutputTokens)
            assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), inputs)
            assertEquals("deepseek", ownedBy)
            assertTrue(thinkingSupported)
        }
        with(success.models[1]) {
            // The id does not mention DeepSeek; the effort metadata alone must mark it as thinking-capable.
            assertEquals("vendor-reasoner", id)
            assertNull(displayName)
            assertNull(contextWindowTokens)
            assertNull(maxOutputTokens)
            assertNull(inputs)
            assertNull(ownedBy)
            assertTrue(thinkingSupported)
        }
        with(success.models[2]) {
            assertEquals("plain", id)
            assertNull(displayName)
            assertNull(contextWindowTokens)
            assertNull(maxOutputTokens)
            assertNull(inputs)
            assertNull(ownedBy)
            assertFalse(thinkingSupported)
        }

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals(0L, request.bodySize, "the model list request must carry no body")
    }

    @Test fun `a top level array is accepted and blank ids are dropped`() {
        server.enqueue(jsonResponse("""
            [
              {"id": "alpha", "name": "Alpha"},
              {"id": ""},
              {"id": "   "},
              {"id": "alpha", "name": "Duplicate"},
              {"id": "beta"},
              "not-a-model-object"
            ]
        """.trimIndent()))

        val success = client().fetch(baseUrl(), secret).success()

        assertEquals(listOf("alpha", "beta"), success.models.map { it.id })
        assertEquals("Alpha", success.models[0].displayName, "the first occurrence of an id wins")
        assertFalse(success.truncated)
    }

    @Test fun `gateway variants provide context and modality fallbacks`() {
        server.enqueue(jsonResponse("""
            {
              "data": [
                {
                  "id": "openrouter-model",
                  "context_length": 200000,
                  "top_provider": {"max_completion_tokens": 16384},
                  "architecture": {"input_modalities": ["text", "image"]}
                },
                {"id": "vllm-model", "max_model_len": 32768, "max_completion_tokens": 4096},
                {"id": "litellm-model", "max_tokens": 2048, "supports_vision": true}
              ]
            }
        """.trimIndent()))

        val success = client().fetch(baseUrl(), secret).success()
        val byId = success.models.associateBy { it.id }

        with(requireNotNull(byId["openrouter-model"])) {
            assertEquals(200000, contextWindowTokens)
            assertEquals(16384, maxOutputTokens)
            assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), inputs)
        }
        with(requireNotNull(byId["vllm-model"])) {
            assertEquals(32768, contextWindowTokens)
            assertEquals(4096, maxOutputTokens)
            assertNull(inputs)
        }
        with(requireNotNull(byId["litellm-model"])) {
            assertNull(contextWindowTokens)
            assertEquals(2048, maxOutputTokens)
            assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), inputs)
        }
    }

    @Test fun `a 401 maps to auth and 403 too`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ModelListResult.Failure(ModelListError.AUTH), client().fetch(baseUrl(), secret))

        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ModelListResult.Failure(ModelListError.AUTH), client().fetch(baseUrl(), secret))

        assertEquals(2, server.requestCount)
    }

    @Test fun `a 404 or 405 maps to unsupported`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ModelListResult.Failure(ModelListError.UNSUPPORTED), client().fetch(baseUrl(), secret))

        server.enqueue(MockResponse().setResponseCode(405))
        assertEquals(ModelListResult.Failure(ModelListError.UNSUPPORTED), client().fetch(baseUrl(), secret))
    }

    @Test fun `a timeout maps to timeout and a closed socket to network`() {
        val client = client(timeoutMillis = 250)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ModelListResult.Failure(ModelListError.TIMEOUT), client.fetch(baseUrl(), secret))

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ModelListResult.Failure(ModelListError.NETWORK), client.fetch(baseUrl(), secret))
    }

    @Test fun `a non json body maps to invalid response`() {
        server.enqueue(MockResponse().setResponseCode(200)
            .addHeader("Content-Type", "text/html")
            .setBody("<html><body>gateway login</body></html>"))
        assertEquals(ModelListResult.Failure(ModelListError.INVALID_RESPONSE), client().fetch(baseUrl(), secret))

        server.enqueue(jsonResponse("""{"object":"list"}"""))
        assertEquals(ModelListResult.Failure(ModelListError.INVALID_RESPONSE), client().fetch(baseUrl(), secret))
    }

    @Test fun `an empty list maps to empty`() {
        server.enqueue(jsonResponse("""{"data":[]}"""))
        assertEquals(ModelListResult.Failure(ModelListError.EMPTY), client().fetch(baseUrl(), secret))

        server.enqueue(jsonResponse("[]"))
        assertEquals(ModelListResult.Failure(ModelListError.EMPTY), client().fetch(baseUrl(), secret))
    }

    @Test fun `more than five hundred models truncates and reports it`() {
        val fiveHundred = (1..500).joinToString(",") { """{"id":"model-$it"}""" }
        server.enqueue(jsonResponse("""{"data":[$fiveHundred]}"""))
        val atCap = client().fetch(baseUrl(), secret).success()
        assertEquals(500, atCap.models.size)
        assertFalse(atCap.truncated)

        val fiveHundredAndOne = (1..501).joinToString(",") { """{"id":"model-$it"}""" }
        server.enqueue(jsonResponse("""{"data":[$fiveHundredAndOne]}"""))
        val truncated = client().fetch(baseUrl(), secret).success()
        assertEquals(500, truncated.models.size)
        assertTrue(truncated.truncated)
        assertEquals("model-1", truncated.models.first().id)
        assertEquals("model-500", truncated.models.last().id)
    }

    @Test fun `the endpoint appends models after v1 and rejects a query string`() {
        assertEquals("https://api.example.com/v1/models", ModelEndpoint.models("https://api.example.com/v1").toString())
        assertEquals("https://api.example.com/v1/models", ModelEndpoint.models("https://api.example.com/v1/").toString())
        assertEquals("https://api.example.com/models", ModelEndpoint.models("https://api.example.com").toString())
        assertFailsWith<ContractError.ConfigurationRejected> {
            ModelEndpoint.models("https://api.example.com/v1?key=secret")
        }
        assertFailsWith<ContractError.ConfigurationRejected> {
            ModelEndpoint.models("http://127.0.0.1:8080")
        }
    }

    @Test fun `neither the key nor the base url is written to stdout`() {
        server.enqueue(jsonResponse("""{"data":[{"id":"safe-model"}]}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        val captured = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val url = baseUrl()
        try {
            PrintStream(captured, true, "UTF-8").use { stream ->
                System.setOut(stream)
                System.setErr(stream)
                client().fetch(url, secret)
                client().fetch(url, secret)
            }
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }

        val output = captured.toString("UTF-8")
        assertFalse(output.contains("sk-test-key"), "the key must never be printed")
        assertFalse(output.contains(url), "the base URL must never be printed")
        assertFalse(output.contains("safe-model"), "the response body must never be printed")
    }

    @Test fun `an unusable key is refused before any request`() {
        // The same printable-ASCII rule ControlledModelClient applies before building its bearer header.
        val badSecret = resolver.put(" sk-test-key".toCharArray())

        assertEquals(ModelListResult.Failure(ModelListError.AUTH), client().fetch(baseUrl(), badSecret))
        assertEquals(0, server.requestCount)
    }

    private fun client(timeoutMillis: Long = 5_000) =
        ModelListClient(OkHttpClient(), resolver, allowLocalHttp = true, timeoutMillis = timeoutMillis)

    private fun baseUrl() = server.url("/v1").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun ModelListResult.success(): ModelListResult.Success =
        assertIs<ModelListResult.Success>(this)
}
