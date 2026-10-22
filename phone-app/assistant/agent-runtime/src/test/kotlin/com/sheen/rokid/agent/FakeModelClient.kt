package com.sheen.rokid.agent

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.testsupport.FakeCancellation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only [ModelClient]: runs the caller's [handler] on the calling thread and records every [ModelRequest] and the
 * [CancellationHandle] it returned, so a test can assert the exact request the adapter assembled and prove that a
 * cancelled turn really cancelled the underlying stream.
 *
 * The handler decides the delivery model: calling the listener inline models a synchronous client, and calling it never
 * (or from another thread) models the transport's asynchronous stream that only the deadline or `stop` can end.
 */
internal class FakeModelClient(
    private val handler: (ModelRequest, (ModelStreamEvent) -> Unit) -> Unit = { _, _ -> },
) : ModelClient {
    val requests = CopyOnWriteArrayList<ModelRequest>()
    val handles = CopyOnWriteArrayList<FakeCancellation>()

    override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        requests += request
        val handle = FakeCancellation()
        handles += handle
        handler(request, listener)
        return handle
    }
}

/** The profile every gateway test looks up; [requestFixture]'s id is the default the turn asks for. */
internal fun gatewayProfile(id: String = "deepseek-default") = ModelProfile(
    id = id,
    revision = 7,
    name = "DeepSeek chat",
    service = "deepseek",
    baseUrl = "https://api.deepseek.com",
    protocolId = "DEEPSEEK_RESPONSES",
    model = "deepseek-chat",
    secret = object : SecretHandle { override val id = "secret-1" },
    usableWindowTokens = 100_000,
    inputBudgetTokens = 60_000,
    maxOutputTokens = 8_000,
    safetyTokens = 1_000,
    timeoutMillis = 30_000,
    inputs = setOf(InputModality.TEXT),
    outputs = setOf(OutputModality.TEXT),
    verifications = emptyList(),
)
