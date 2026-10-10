package edith.phone.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The wire form of one [ShellResult] across the UserService binder (P3.3 Task 8).
 *
 * AIDL carries primitives cheaply and a `Parcelable` would need a second declaration to keep in step, so the reply is a
 * small JSON object built and parsed by this one class. It is a pure function pair on purpose: the part of the
 * privileged path that must not be guessed is exactly "did we understand what came back", and that part stays testable
 * on the JVM while the binder itself is only exercisable on a device.
 *
 * [decode] answers `null` for anything that is not the documented shape. A malformed reply is never a success and never
 * an exception in the caller: it becomes `SHELL_REPLY_INVALID` at the backend.
 */
object ShellReply {

    /** The command field of a decoded reply: the command is known to the caller, not sent back across the binder. */
    const val REMOTE_COMMAND = "<remote>"

    fun encode(exitCode: Int, stdout: String, stderr: String): String = buildJsonObject {
        put("exit", exitCode)
        put("stdout", stdout)
        put("stderr", stderr)
    }.toString()

    fun decode(reply: String?): ShellResult? {
        val root = runCatching { Json.parseToJsonElement(reply ?: return null) as? JsonObject }.getOrNull()
            ?: return null
        val exit = root["exit"]?.jsonPrimitive?.intOrNull ?: return null
        val stdout = root["stdout"]?.jsonPrimitive?.contentOrNull ?: return null
        val stderr = root["stderr"]?.jsonPrimitive?.contentOrNull ?: return null
        return ShellResult(REMOTE_COMMAND, exit, stdout, stderr)
    }
}
