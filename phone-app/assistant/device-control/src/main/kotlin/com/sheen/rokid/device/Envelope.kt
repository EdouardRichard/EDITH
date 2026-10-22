package com.sheen.rokid.device

import com.sheen.rokid.plugin.ImageRef

/**
 * The post-condition the kernel actually checked, and the observation revision that proved it.
 *
 * `kind` is `"screen_change"` when the action was accepted and an independent observation differs
 * from the pre-action snapshot, `"expected"` when a [DeviceKernel.waitFor] condition became true,
 * and `"idle"` when a wait finished because the screen stopped changing. [revision] is the kernel
 * revision of the observation that satisfied it.
 */
data class Verified(val kind: String, val revision: Long)

/** How long one device operation took, in milliseconds measured with the kernel clock. */
data class Timing(val actMs: Long, val observeMs: Long, val totalMs: Long)

/**
 * The one result shape every device operation returns (spec §4.6), rendered into the model-facing
 * JSON by Task 10.
 *
 * **`ok`** means "the operation has no [error]"; it is not the same as "the screen changed".
 * **`accepted`** is the platform's answer to a write (or, for reads, whether the read itself
 * succeeded) and is deliberately kept apart from **`applied`**: `applied = true` is only ever set
 * after an independent observation differs from the pre-action snapshot, `applied = false` means an
 * observation was compared and nothing changed, and `applied = null` means there was no baseline to
 * compare against (or the operation was not a write). A refused action is never `applied = true`;
 * its refusal is the error. **`verified`** names the post-condition the kernel checked: for an
 * `act` it is set only together with `applied = true` (`screen_change`), while [DeviceKernel.waitFor]
 * reports `expected`/`idle` with `applied = null` because a wait applies nothing.
 *
 * **`data` is deliberately null in this task.** The kernel does not render the observation:
 * `observe`/`act`/`wait` return the envelope plus [DeviceKernel.latestObservation], and Task 10
 * projects the rendered/diff JSON (spec §4.6 `data.screen`) into it. Keeping rendering out of the
 * kernel is what lets Task 10 choose full-vs-diff without the kernel owning a second copy of the
 * budget rules. The one exception is a successful shell write that the accessibility channel could
 * not observe: `data` carries a one-line `diagnostic=...` instead of screen data, and Task 10 must
 * keep that diagnostic visible rather than dropping it.
 *
 * **`images` never carries bytes.** A screenshot becomes an [ImageRef] whose `artifactId` is
 * resolved by the artifact store; this task reports the placeholder `pending` (see
 * [DeviceKernel.screenshot]) until Task 13 wires the store.
 */
data class DeviceEnvelope(
    val ok: Boolean,
    val tool: String,
    val action: String,
    val channel: String,
    val accepted: Boolean,
    val applied: Boolean?,
    val verified: Verified?,
    val data: String?,
    val images: List<ImageRef>,
    val timing: Timing,
    val error: DeviceError?,
) {

    companion object {

        /** The only tool name this kernel answers to (spec §4.7). */
        const val TOOL_NAME = "device"

        /** A successful envelope: no error, so [ok] is always true. */
        fun success(
            action: String,
            channel: String,
            accepted: Boolean,
            applied: Boolean?,
            verified: Verified?,
            timing: Timing,
            data: String? = null,
            images: List<ImageRef> = emptyList(),
            tool: String = TOOL_NAME,
        ): DeviceEnvelope = DeviceEnvelope(
            ok = true,
            tool = tool,
            action = action,
            channel = channel,
            accepted = accepted,
            applied = applied,
            verified = verified,
            data = data,
            images = images,
            timing = timing,
            error = null,
        )

        /**
         * A failed envelope: [error] is required and `ok`/`verified` are fixed, because a failure
         * cannot carry a satisfied post-condition.
         */
        fun failure(
            action: String,
            channel: String,
            error: DeviceError,
            timing: Timing,
            accepted: Boolean = false,
            applied: Boolean? = null,
            data: String? = null,
            images: List<ImageRef> = emptyList(),
            tool: String = TOOL_NAME,
        ): DeviceEnvelope = DeviceEnvelope(
            ok = false,
            tool = tool,
            action = action,
            channel = channel,
            accepted = accepted,
            applied = applied,
            verified = null,
            data = data,
            images = images,
            timing = timing,
            error = error,
        )
    }
}
