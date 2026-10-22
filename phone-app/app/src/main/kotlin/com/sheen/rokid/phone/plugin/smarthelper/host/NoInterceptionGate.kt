package com.sheen.rokid.phone.plugin.smarthelper.host

/**
 * 0.3 Phase 4B (`D189`): the live global 「不拦截」 switch.
 *
 * The persisted value lives in [SmartSettings.noInterception]; this object is the *live* half that the policy layer and
 * the shell precheck read on every call, so turning the mode on or off takes effect from the next tool call instead of
 * waiting for the next App assembly. `HostCompositionRoot` seeds it from the stored value and updates it on a successful
 * save. The **provider** is what B3's plugin/policy wiring receives; it must read [enabled] on each invocation, not
 * capture a snapshot, or a save mid-session would not be honoured.
 *
 * Reads are lock-free ([enabled] is `@Volatile`) because policy decisions run on worker threads while the settings
 * screen writes from the UI thread. Writers are serialised with [set].
 *
 * Deliberately free of Android types: the gate is a policy value, not a platform service, and that is what lets it be
 * proven on the JVM. Defaults to **off** - the mode removes confirmations and must never be armed by construction.
 *
 * @param initial the persisted setting the App loaded at assembly time.
 */
class NoInterceptionGate(initial: Boolean = false) {

    /** The current state, safe to read from any thread. */
    @Volatile
    var enabled: Boolean = initial
        private set

    /** Applies [value] immediately; the next [provider] call sees it. */
    @Synchronized
    fun set(value: Boolean) {
        enabled = value
    }

    /**
     * The live read-only provider for the plugin/policy layer.
     *
     * Returns a function rather than the current value on purpose: the policy asks it on **every** `decide()` and shell
     * precheck, which is what makes a saved change apply from the next tool call without restarting the App.
     */
    fun provider(): () -> Boolean = { enabled }
}
