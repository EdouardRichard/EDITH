package edith.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import edith.contracts.ContractError
import java.net.URI

object ModelEndpoint {
    fun responses(baseUrl: String, allowLocalHttp: Boolean = false): HttpUrl {
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
        if (builder.build().pathSegments.last() != "responses") builder.addPathSegment("responses")
        return builder.build()
    }
}
