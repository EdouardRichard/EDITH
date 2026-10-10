package edith.phone.actions

import edith.plugin.ActionDescriptor
import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.RiskLevel
import java.io.IOException

/**
 * The root channel's honest half (spec §17.1#1, §17.3).
 *
 * The user decided on 2026-10-08 that root is not something this project verifies; the P0 matrix measured `su` absent
 * on both the K40 and the glasses. So this type **declares no actions**: it only answers whether `su` can run as root,
 * which is what lets the capability panel tell the user "L4 via root is not available here" instead of leaving the
 * impression that it might be.
 *
 * The rule it follows is the same one the whole execution layer follows: never claim a capability the device has not
 * demonstrated. A future root backend would add descriptors at [TIER] and let the policy layer force the question -
 * nothing here pre-approves anything.
 */
class RootProbe(private val runner: (List<String>) -> ShellResult?) {

    /**
     * `su` exists **and** reports uid 0.
     *
     * The stdout check is the point: a `su` that runs but answers as the shell identity (uid 2000) is not root, and its
     * exit code would say "success" either way - the same lesson T3a drew from `pm grant`.
     */
    fun available(): Boolean =
        runCatching { runner(listOf("su", "-c", "id")) }.getOrNull()?.stdout?.contains("uid=0") == true

    /** The reason code the panel and the probe line carry. */
    fun reason(): String = if (available()) REASON_AVAILABLE else REASON_UNAVAILABLE

    /** A wearer-facing line. Informational, never an error: an absent `su` is the device's nature. */
    fun describe(): String = if (available()) "Root：可用" else "Root：不可用（设备无 su）"

    /** Always empty. Root contributes no tool in this phase. */
    fun actions(): List<ActionDescriptor> = emptyList()

    companion object {
        const val REASON_AVAILABLE = "ROOT_AVAILABLE"
        const val REASON_UNAVAILABLE = "ROOT_UNAVAILABLE"

        /** What any future root action would have to declare; there is none in P3.3. */
        val TIER = RiskLevel.L4

        /** The outcome a root action would produce today if one were ever offered: refused, not silently run. */
        val NOTHING_TO_RUN = ActionResult(ActionOutcome.Refused, REASON_UNAVAILABLE)

        /** The real device probe: `su -c id`, with an absent binary reported as "unavailable" rather than thrown. */
        fun onDevice(): RootProbe = RootProbe { command ->
            runCatching {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                ShellResult(command.joinToString(" "), exit, output, "")
            }.getOrElse { if (it is IOException) null else throw it }
        }
    }
}
