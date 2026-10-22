package com.sheen.rokid.device

/**
 * What the kernel can prove about its channels right now, derived only from the ports it has
 * (spec §4.3 "通道健康").
 *
 * Task 9's adapter knows more per-capability flags (gesture support, screenshot support, window
 * retrieval, event stream); this version keeps the four facts the kernel can gate on and derives
 * readiness from them so `ensureReady` and `status` cannot disagree with one another.
 *
 * [shellAvailable] is only "a shell port is wired", because [ShellPort] has no readiness probe: the
 * adapter reports a dead Shizuku binder through [ShellOutcome.failure], which the kernel surfaces
 * as an error code instead of pretending the channel is healthy.
 */
data class ChannelHealth(
    val accessibilityBound: Boolean,
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val shellAvailable: Boolean,
) {

    /** A read needs the service bound, the screen on and the device unlocked (spec §4.3). */
    val observeReady: Boolean get() = accessibilityBound && interactive && !keyguardLocked

    /** The accessibility write path has the same requirements as a read. */
    val actReady: Boolean get() = observeReady

    /** `takeScreenshot` lives on the accessibility service, so its gate is the same. */
    val screenshotReady: Boolean get() = observeReady

    /** The shell path needs no accessibility binding, but it is still a foreground interaction. */
    val shellReady: Boolean get() = shellAvailable && interactive && !keyguardLocked
}
