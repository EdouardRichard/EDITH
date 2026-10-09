package edith.phone

import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.DeadlineScheduler
import edith.plugin.ModelProfile
import edith.plugin.MonotonicClock
import edith.plugin.PluginConfiguration
import edith.plugins.smart.SmartAssistantPlugin
import edith.storage.ProfileRepository

/**
 * The smart plugin as the composition builds it (Task 14a item 1).
 *
 * The plugin is a real `ConfiguredPlugin` now, so it needs the composition's own clock, scheduler, profile lookup,
 * output-turn cancellation and configured cooldown - registering the bare constructor would hand the passive loop a
 * plugin that cannot look up its profile and a cooldown nobody configured. Tests use this seam directly
 * (`PassiveLoopWiringTest`, `SmartWiringTest`), and `HostCompositionRoot` builds the same object, so the tested shape
 * and the shipped shape cannot drift.
 */
internal class SmartWiring(
    val settings: SmartSettings,
    val profiles: ProfileRepository,
    clock: MonotonicClock,
    scheduler: DeadlineScheduler,
    cancelOutputTurn: (SessionId, Generation, String) -> Unit = { _, _, _ -> },
) {
    /** The word list the plugin declares on activation; also the `Capability.WakeWord` provider's content. */
    val wakeWords: SmartWakeWordRegistry = SmartWakeWordRegistry(settings.wakeWords)

    /**
     * Resolved per call, because a profile can be deleted between an App start and a session start.
     *
     * A repository that refuses (no keystore, deleted payload) yields null rather than propagating: the configuration
     * still has to be built, and the plugin's own lookup will find nothing until storage answers again.
     */
    fun selectedProfile(): ModelProfile? =
        settings.resolveProfileId(profiles)?.let { id -> runCatching { profiles.get(id) }.getOrNull() }

    /**
     * The plugin's complete configuration: the settings-derived keys with [profile] as the named profile.
     *
     * One configuration for one `configure` call. The settings keys and the user's profile selection used to be
     * applied as two separate configurations, and `HostRuntime.configure` replaces the stored one - so the second
     * call dropped the first call's keys and the plugin fell back to its own defaults (Task 14a-fix item 1). A null
     * [profile] is not an absent configuration: the settings' placeholder keeps the shape the plugin accepts.
     */
    fun configuration(profile: ModelProfile?): PluginConfiguration = settings.pluginConfiguration(profile)

    val plugin: SmartAssistantPlugin = SmartAssistantPlugin(
        clock = clock,
        scheduler = scheduler,
        profileLookup = profiles::get,
        cancelOutputTurn = cancelOutputTurn,
        cooldownNanos = settings.cooldownNanos,
    )
}
