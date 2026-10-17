package com.sheen.rokid.phone.actions.shizuku
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.phone.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * an exception in the caller: it becomes `SHELL_REPLY_INVALID` at the backend, and that code means exactly one thing -
 * **the carrier delivered something this build could not parse**. It never means "the reply did not arrive", which is
 * the carrier code's job (R-9). A reply that *was* understood but whose accumulation hit [MAX_REPLY_BYTES] decodes
 * normally and carries `truncated = true` (R-9 / D-6) - being large is not being malformed. That flag has not been
 * observed on a device and is kept for the memory bound, not because the channel is expected to reach it.
 */
object ShellReply {

    /** The command field of a decoded reply: the command is known to the caller, not sent back across the binder. */
    const val REMOTE_COMMAND = "<remote>"

    /**
     * The most of one stream's text that a single reply may accumulate: **8 MiB (8 388 608 B)**.
     *
     * **This is a memory bound inside the privileged process, not a limit of the channel.** Nothing here may be read as
     * "replies up to 8 MiB can be sent": the K40 measurement (2026-10-16/17, `evidence/2026-10-16-phase-5-device-verification.md`)
     * shows the transport failing **well below any cap we set** - by exact byte sweep, 325 000 B succeeds and 350 000 B
     * does not, and the failure is a dead binder (`android.os.DeadObjectException`, with the remote logging
     * `Large reply transaction of 750080 bytes … code 2`), not a truncation. `truncated = true` has never been observed
     * on a device, and [ShizukuUserServiceShell][com.sheen.rokid.phone.actions.shizuku.ShizukuUserServiceShell] reports the carrier failure
     * as its own code instead of pretending otherwise.
     *
     * **Decision (R-9 close-out): the value stays 8 MiB, and the reason is not link headroom.** Its job is to bound
     * **memory** in the privileged process: without a cap, one command such as `dumpsys` or `cat /dev/zero` would
     * accumulate one unbounded `String`, then a second copy while [encode] builds the JSON, then a third on the app side,
     * and take the app down with an OOM instead of an honest error. Lowering the value towards the measured ~325 kB
     * ceiling was considered and rejected: a bound chosen to match a transport limit that kills the connection would
     * start truncating replies that today arrive whole, while a bound *above* the failure point is the only kind that
     * keeps the two concerns - "the process may not grow without limit" and "the binder cannot carry this" - separate.
     * The old justification for 8 MiB (6.7x a measured 1 228 800 B success) is **withdrawn**: that success came from a
     * probe that returned a *count* rather than the payload, and 325 000 B is the largest reply since measured to arrive
     * whole through this channel.
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
        // payload we did understand. The cast is `as?` rather than `.jsonPrimitive` because a non-primitive value
        // (`{"truncated":{}}`) would otherwise **throw** out of `decode` at the caller instead of reading as false.
        val truncated = (root["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ShellResult(REMOTE_COMMAND, exit, stdout, stderr, truncated)
    }
}
