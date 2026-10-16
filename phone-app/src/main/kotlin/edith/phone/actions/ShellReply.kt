package edith.phone.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * an exception in the caller: it becomes `SHELL_REPLY_INVALID` at the backend. A reply that *was* understood but had to
 * be cut short at [MAX_REPLY_BYTES] decodes normally and carries `truncated = true` (R-9 / D-6) - being large is not
 * being malformed.
 */
object ShellReply {

    /** The command field of a decoded reply: the command is known to the caller, not sent back across the binder. */
    const val REMOTE_COMMAND = "<remote>"

    /**
     * The most of one stream's text that a single reply may accumulate: **8 MiB (8 388 608 B)**.
     *
     * **This is a product safety valve, not a measured link limit.** The largest successful single reply ever probed on
     * the K40 is **1 228 800 B**, and the real ceiling was **never found** - so 8 MiB is not "the Binder limit", and
     * nothing in this code may describe it as one. Its job is to bound **memory**: without a cap the privileged process
     * accumulates one unbounded `String`, then a second copy while [encode] builds the JSON, then a third on the app
     * side after the reply crosses the binder, so a command like `dumpsys` or `cat /dev/zero` could take the app down
     * with an OOM rather than with an honest error.
     *
     * The value leaves room against everything measured: 6.7x the largest successful reply and ~35x the largest
     * *normal* one (a `screencap -p` PNG, 175-179 KB, ~233 KB once base64'd). It obstructs no known real scenario,
     * because screenshots, recordings and photos travel as **files** rather than through this string channel.
     */
    const val MAX_REPLY_BYTES: Int = 8 * 1024 * 1024

    /**
     * Builds the reply for one command.
     *
     * `truncated` is written **only when true**, so the wire form of the common case is byte-for-byte what older builds
     * produced and an older decoder - which ignores keys it does not know - stays compatible in both directions.
     */
    fun encode(exitCode: Int, stdout: String, stderr: String, truncated: Boolean = false): String = buildJsonObject {
        put("exit", exitCode)
        put("stdout", stdout)
        put("stderr", stderr)
        if (truncated) put("truncated", true)
    }.toString()

    /** Parses one reply, or answers `null` when it is not the documented shape. A **missing** `truncated` reads `false`. */
    fun decode(reply: String?): ShellResult? {
        val root = runCatching { Json.parseToJsonElement(reply ?: return null) as? JsonObject }.getOrNull()
            ?: return null
        val exit = root["exit"]?.jsonPrimitive?.intOrNull ?: return null
        val stdout = root["stdout"]?.jsonPrimitive?.contentOrNull ?: return null
        val stderr = root["stderr"]?.jsonPrimitive?.contentOrNull ?: return null
        // An unreadable `truncated` value is a false one: the flag is advisory and must never reject a reply whose
        // payload we did understand.
        val truncated = root["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
        return ShellResult(REMOTE_COMMAND, exit, stdout, stderr, truncated)
    }
}
