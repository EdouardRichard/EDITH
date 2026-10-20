package com.sheen.rokid.phone.plugin.smarthelper.host

/**
 * edith-0.3 Phase 2 Task 10: the live 「感知截图」 switch.
 *
 * The persisted value lives in [SmartSettings.visionEnabled]; this object is the *live* half the perception provider
 * and the `device` tool read on every call, so saving the switch takes effect immediately instead of waiting for the
 * next App assembly. The composition creates it from the settings the App started with and updates it through
 * `HostCompositionRoot.saveSmartSettings`.
 *
 * Reads are lock-free ([enabled] is `@Volatile`) because the provider and the tool call them from worker threads while
 * the settings screen writes from the UI thread. Writers are serialised so [onChange] cannot observe a half-applied
 * flip.
 *
 * Deliberately free of Android types: the gate is a policy value, not a platform service, and that is what lets it be
 * proven on the JVM.
 *
 * @param initial the persisted setting the App loaded at assembly time.
 * @param onChange notified only when the value actually changes (a redundant `set` is a no-op). It runs on the
 *   writer's thread and must not throw; the gate does not catch its failures.
 */
class VisionGate(
    initial: Boolean,
    private val onChange: ((Boolean) -> Unit)? = null,
) {

    /** The current state, safe to read from any thread. */
    @Volatile
    var enabled: Boolean = initial
        private set

    /** Applies [value]; a redundant write does not notify [onChange]. */
    @Synchronized
    fun set(value: Boolean) {
        if (enabled == value) return
        enabled = value
        onChange?.invoke(value)
    }
}
