package edith.glasses.probe

/**
 * Which `CXRServiceBridge.sendMessage` overload the uplink uses.
 *
 * This exists because the two overloads reach **different native functions** (the pinned AAR's native library exports
 * `NativeCXRBridge::sendMessage(char const*, Caps const&)` and a separate overload taking an extra byte pointer and
 * length), and only the first has ever been measured. That measurement was a clean negative: the sender reported
 * success on 499 frames while the phone accepted zero, with `malformed=0` and no `START` frame seen at all.
 *
 * The one-argument-short hypothesis for that negative is that the payload was being serialised **into** the `Caps`,
 * a container meant for small header fields, rather than travelling as its own buffer. [CapsWithDataArray] hands the
 * frame over as a `byte[]` through the overload the library provides for exactly that, so a run can tell the two
 * apart. Nothing about this claims the second form works - it claims the experiment has not been run.
 */
enum class CxrSendVariant(val label: String) {
    /**
     * `sendMessage(name, Caps{write(frame)})` - the form every earlier uplink run used, and the measured negative.
     * Kept selectable so a re-run can reproduce the baseline in the same session as its challenger.
     */
    CapsOnly("capsOnly"),

    /**
     * `sendMessage(name, Caps, data)` - the payload travels as its own byte array. This is the variant under test.
     */
    CapsWithDataArray("capsWithDataArray"),
    ;

    companion object {
        /** The variant a run uses when none is named: the challenger, never the already-refuted baseline. */
        val DEFAULT = CapsWithDataArray

        /**
         * Resolves a run's `--es` label. Refuses anything unrecognised rather than falling back to [DEFAULT]: a typo
         * would otherwise silently run the baseline and present a known negative as a fresh result.
         */
        fun fromLabel(raw: String?): CxrSendVariant {
            val trimmed = raw?.trim().orEmpty()
            require(trimmed.isNotEmpty()) { "SEND_VARIANT_MUST_NOT_BE_BLANK" }
            return entries.firstOrNull { it.label == trimmed }
                ?: throw IllegalArgumentException("UNKNOWN_SEND_VARIANT: $trimmed")
        }
    }
}
