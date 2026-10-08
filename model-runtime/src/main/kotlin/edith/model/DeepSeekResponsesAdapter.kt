package edith.model

import edith.plugin.*
import kotlinx.serialization.json.*
import java.util.Locale

class DeepSeekResponsesAdapter {
    val support = ProtocolSupport("DEEPSEEK_RESPONSES", setOf(InputModality.TEXT), setOf(OutputModality.TEXT))
    fun encode(profile: ModelProfile, budgeted: BudgetedRequest): ByteArray {
        require(profile.protocolId == support.id && budgeted.request.profileId == profile.id && budgeted.request.profileRevision == profile.revision)
        require(InputModality.TEXT in profile.inputs && OutputModality.TEXT in profile.outputs)
        require(budgeted.request.output == OutputModality.TEXT)
        require(budgeted.request.input.all { it is InputPart.Text })
        val body = buildJsonObject {
            put("model", profile.model)
            put("instructions", budgeted.request.instructions)
            put("stream", true)
            put("max_output_tokens", profile.maxOutputTokens)
            put("input", buildJsonArray {
                budgeted.request.history.forEach { add(message(it.role.name.lowercase(Locale.ROOT), it.text)) }
                budgeted.request.context.forEach { add(message("user", it.text)) }
                budgeted.request.input.forEach { add(message("user", (it as InputPart.Text).text)) }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role); put("content", content)
    }
}
