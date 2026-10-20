package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ToolSpec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * SHA-256 over the stable prefix of a request (0.3 spec §5.3): system text, the ordered [ToolSpec]s and the ordered
 * history items. The digest is the only form in which the prefix may be logged; this object never logs or renders the
 * text it hashes.
 *
 * Every field is length-prefixed and every list is count-prefixed, and a history item is tagged with its wire type, so
 * two different prefixes cannot collide merely by splitting text across a field boundary (`"ab"+"c"` vs `"a"+"bc"`).
 * [ModelHistoryItem.ToolCall] contributes its call id and arguments; [ModelHistoryItem.ToolOutput] its call id and
 * output. Images are referenced metadata, not part of the text prefix, and are deliberately not folded in.
 */
object PrefixHash {

    /** Lower-case hex SHA-256 of the unambiguous, length-prefixed encoding of the three prefix components. */
    fun of(systemText: String, tools: List<ToolSpec>, history: List<ModelHistoryItem>): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeText(systemText)
            out.writeInt(tools.size)
            for (tool in tools) {
                out.writeText(tool.name)
                out.writeText(tool.description)
                out.writeText(tool.parametersJson)
            }
            out.writeInt(history.size)
            for (item in history) {
                when (item) {
                    is ModelHistoryItem.Text -> {
                        out.writeByte(TAG_TEXT)
                        out.writeText(item.role.name)
                        out.writeText(item.text)
                    }
                    is ModelHistoryItem.ToolCall -> {
                        out.writeByte(TAG_TOOL_CALL)
                        out.writeText(item.callId)
                        out.writeText(item.argsJson)
                    }
                    is ModelHistoryItem.ToolOutput -> {
                        out.writeByte(TAG_TOOL_OUTPUT)
                        out.writeText(item.callId)
                        out.writeText(item.output)
                    }
                }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(buffer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Writes [value] as UTF-8 with its byte length, so adjacent fields cannot be re-split into another prefix. */
    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private const val TAG_TEXT = 1
    private const val TAG_TOOL_CALL = 2
    private const val TAG_TOOL_OUTPUT = 3
}
