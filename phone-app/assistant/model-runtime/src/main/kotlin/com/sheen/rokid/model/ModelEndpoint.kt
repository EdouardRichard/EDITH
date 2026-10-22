package com.sheen.rokid.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.sheen.rokid.contracts.ContractError
import java.net.URI

object ModelEndpoint {
    fun responses(baseUrl: String, allowLocalHttp: Boolean = false): HttpUrl =
        resolve(baseUrl, "responses", allowLocalHttp)

    /** P5: OpenAI Chat Completions uses /chat/completions instead of /responses. */
    fun chatCompletions(baseUrl: String, allowLocalHttp: Boolean = false): HttpUrl =
        resolve(baseUrl, "chat/completions", allowLocalHttp)

    private fun resolve(baseUrl: String, terminalSegment: String, allowLocalHttp: Boolean): HttpUrl {
        fun reject(): Nothing = throw ContractError.ConfigurationRejected("Model endpoint invalid")
        val uri = try { URI(baseUrl) } catch (_: Exception) { reject() }
        if (uri.rawUserInfo != null) reject()
        val url = baseUrl.toHttpUrlOrNull() ?: reject()
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) reject()
        if (url.scheme != "https" && !(allowLocalHttp && url.scheme == "http" &&
                (url.host == "localhost" || url.host == "::1" || url.host.matches(Regex("127\\.([0-9]{1,3}\\.){2}[0-9]{1,3}"))))) reject()
        val builder = url.newBuilder()
        while (builder.build().pathSegments.last() == "" && builder.build().pathSegments.size > 1)
            builder.removePathSegment(builder.build().pathSegments.lastIndex)
        // For multi-segment terminals like "chat/completions", check if the path already ends with it.
        val segments = terminalSegment.split("/")
        val currentSegments = builder.build().pathSegments.filter { it.isNotEmpty() }
        val alreadyEndsWith = currentSegments.size >= segments.size &&
            currentSegments.takeLast(segments.size) == segments
        if (!alreadyEndsWith) {
            segments.forEach { builder.addPathSegment(it) }
        }
        return builder.build()
    }
}
