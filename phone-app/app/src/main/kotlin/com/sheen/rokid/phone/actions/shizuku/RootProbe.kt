package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What a root probe actually found, one probe's whole answer.
 *
 * The distinction is the point (P3.3 leftover-sweep review, Important 2): a device with no `su` and a device whose `su`
 * never answered are **different facts**, and the panel may only state the one the probe observed. Collapsing them into
 * a boolean is how "设备无 su" gets printed on a rooted phone whose owner ignored the permission prompt.
 */
enum class RootStatus {
    /** `su -c id` ran and reported uid 0. */
    AVAILABLE,

    /** `su` exists and ran, but answered as another identity - the case an exit code cannot tell apart from root. */
    NOT_ROOT,

    /** No `su` to run at all: the binary is absent. This is the device's nature, not a failure of this App. */
    ABSENT,

    /** `su` did not finish inside [RootProbe.PROBE_TIMEOUT_MILLIS] - typically a permission prompt nobody answered. */
    NO_ANSWER,
}

/**
 * The root channel's honest half (spec §17.1#1, §17.3).
 *
 * The user decided on 2026-10-08 that root is not something this project verifies; the P0 matrix measured `su` absent
 * on both the K40 and the glasses. So this type **declares no actions**: it only answers what the probe found, which is
 * what lets the capability panel tell the user the truth about L4-via-root instead of leaving the impression that it
 * might be there.
 *
 * The rule it follows is the same one the whole execution layer follows: never claim a capability the device has not
 * demonstrated - and never claim a *reason* the probe did not observe either.
 */
class RootProbe private constructor(private val inspect: () -> RootStatus) {

    /**
     * Asks the device **once**. The one caller that needs both the row and its note (the composition's panel) calls this
     * and hands the answer to both, so a silent `su` cannot cost two timeouts for a single read.
     */
    fun status(): RootStatus = runCatching { inspect() }.getOrDefault(RootStatus.ABSENT)

    /** `su` exists **and** reports uid 0. */
    fun available(): Boolean = status() == RootStatus.AVAILABLE

    /** The reason code the panel and the probe line carry. */
    fun reason(): String = if (available()) REASON_AVAILABLE else REASON_UNAVAILABLE

    /** A wearer-facing line. Informational, never an error: an absent `su` is the device's nature. */
    fun describe(status: RootStatus = status()): String = noteFor(status)

    /** Always empty. Root contributes no tool in this phase. */
    fun actions(): List<ActionDescriptor> = emptyList()

    companion object {
        const val REASON_AVAILABLE = "ROOT_AVAILABLE"
        const val REASON_UNAVAILABLE = "ROOT_UNAVAILABLE"

        /** What any future root action would have to declare; there is none in P3.3. */
        val TIER = RiskLevel.L4

        /** The outcome a root action would produce today if one were ever offered: refused, not silently run. */
        val NOTHING_TO_RUN = ActionResult(ActionOutcome.Refused, REASON_UNAVAILABLE)

        /**
         * How long [onDevice]'s `su` may take before it is killed and reported as [RootStatus.NO_ANSWER].
         *
         * On a rooted device `su` can wait for a permission prompt that nothing here can see, so an unbounded
         * `readText()` would hang the caller - and the caller is the session page's thread
         * (`HostCompositionRoot.start` -> the probe lines and the panel). Two seconds is far longer than `su -c id`
         * needs and far shorter than a person waits before deciding the App has frozen.
         */
        const val PROBE_TIMEOUT_MILLIS = 2_000L

        private const val UID_ZERO = "uid=0"
        private val SU_COMMAND = listOf("su", "-c", "id")

        /**
         * The Root row's line, **derived from the status the probe observed**.
         *
         * It is a pure function of [status] so the panel can be handed one probe's answer and state exactly that; the
         * "设备无 su" wording is reserved for the one status that actually observed an absent binary.
         */
        fun noteFor(status: RootStatus): String = when (status) {
            RootStatus.AVAILABLE -> "Root：可用"
            RootStatus.ABSENT -> "Root：不可用（设备无 su）"
            RootStatus.NO_ANSWER -> "Root：不可用（su 未在 ${PROBE_TIMEOUT_MILLIS / 1000} 秒内应答）"
            RootStatus.NOT_ROOT -> "Root：不可用（su 未以 root 身份应答）"
        }

        /** The real device probe: `su -c id`, with an absent binary reported as "unavailable" rather than thrown. */
        fun onDevice(): RootProbe = RootProbe { probeStatus(SU_COMMAND, PROBE_TIMEOUT_MILLIS) }

        /**
         * Builds a probe from a plain command runner - the classic seam, for tests and for any caller that only has the
         * old shape.
         *
         * A runner that returns `null` (no output at all) and one that throws both read as [RootStatus.ABSENT]: a runner
         * that cannot say *why* it found nothing can only honestly report "no su".
         */
        fun fromRunner(runner: (List<String>) -> ShellResult?): RootProbe = RootProbe {
            when (val result = runCatching { runner(SU_COMMAND) }.getOrNull()) {
                null -> RootStatus.ABSENT
                else -> if (result.stdout.contains(UID_ZERO)) RootStatus.AVAILABLE else RootStatus.NOT_ROOT
            }
        }
    }
}

/**
 * Runs [command] with a **bounded** wait and reports what it found, including *why* it found nothing.
 *
 * The bound is the point (P3.3 review, Minor 2): a `su` waiting on a permission prompt never exits, and this probe is
 * asked from the session page's thread, so an unbounded wait is a frozen page. A process still alive after the window
 * is killed and answered [RootStatus.NO_ANSWER] - never [RootStatus.AVAILABLE], so a timeout can never be mistaken for a
 * grant. There is deliberately **no** cache: §17.2 wants each channel asked a live question every session, and a root
 * installed while the App runs is not worth a stale "available".
 *
 * `start` exists so the policy above is pinned by a JVM test; production uses the default.
 */
internal fun probeStatus(
    command: List<String>,
    timeoutMillis: Long,
    start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
): RootStatus {
    val process = try {
        start(command)
    } catch (_: IOException) {
        return RootStatus.ABSENT
    } catch (_: InterruptedException) {
        // An interrupting caller (a cancelled session) must not leave a child behind, and must keep its own interrupt.
        Thread.currentThread().interrupt()
        return RootStatus.NO_ANSWER
    }
    try {
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return RootStatus.NO_ANSWER
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return if (output.contains("uid=0")) RootStatus.AVAILABLE else RootStatus.NOT_ROOT
    } catch (_: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        return RootStatus.NO_ANSWER
    } finally {
        // Nothing outlives the probe, including the case where the caller was interrupted mid-wait.
        if (process.isAlive) process.destroyForcibly()
    }
}
