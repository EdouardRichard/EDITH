package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm
import com.sheen.rokid.storage.ProfileRepository

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
    /**
     * 0.24 S3 fix (C1): whether the glasses panel is visible right now, read by the conversation cycle's already-visible
     * turn-open path. The production `create` passes the composition's accessor; the `{ false }` default keeps a
     * stand-alone wiring (mostly tests) on the hidden-panel path.
     */
    isPanelVisible: () -> Boolean = { false },
    /**
     * 0.3 Phase 3 Task 9d: the composition's readiness-aware `PANEL_OPEN` request (the same one the wake detector
     * uses), or `null` for a stand-alone wiring with no panel to open. The production `create` passes
     * `HostCompositionRoot.requestPanelOpen`, which carries the link's `appStart`/retry/readiness handshake.
     */
    requestPanelOpen: ((AssistantRoute) -> Boolean)? = null,
    /**
     * 0.24 S3 Task 14 fix (C2, spec risk row 17): whether the host can actually speak a hint right now.
     *
     * The production composition builds the speech player only when「语音朗读」is on and the engine is ready, so it
     * passes `{ speechPlayer != null }` through a holder (the player is constructed after this wiring). The default
     * `{ false }` is the honest value for a stand-alone wiring: no player exists, so `both`/`voice` must be settled by
     * the answer's `Displayed` acknowledgement, never by a `SpeechFinished` that cannot arrive.
     */
    speechAvailable: () -> Boolean = { false },
    /**
     * 0.5 (`D213`): whether the session runs the phone-only model. The plugin's closing answer requests speech only
     * there (no HUD to read); glasses mode keeps the 0.4 display-only final answer. The default `{ false }` is glasses
     * mode and keeps the pre-0.5 rigs on their original path.
     */
    phoneOnly: () -> Boolean = { false },
    /**
     * 0.3 Phase 4B (`D189`, spec §5.1/§5.3): the composition's **live** global 「不拦截」 gate.
     *
     * Production passes `NoInterceptionGate.provider()`, the one instance `saveSmartSettings` updates, so the plugin's
     * policy and confirmation wrapper observe a save from the next tool call. The default `{ false }` keeps a
     * stand-alone wiring (tests, harnesses) in normal mode. It must never be a captured boolean.
     */
    noInterception: () -> Boolean = { false },
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

    /**
     * The two routes' session stores (Phase 5 / spec §1.3 C3): one `ConversationStore` per route, and nothing shared
     * between them. The plugin empties them at each activation and deactivation, so they are session state that happens
     * to outlive one activation by living in the wiring — never a session's words replayed under the next one.
     */
    private val conversationStores: Map<ContextRoute, ConversationStore> =
        ContextRoute.entries.associateWith { ConversationStore() }

    val plugin: SmartAssistantPlugin = SmartAssistantPlugin(
        clock = clock,
        scheduler = scheduler,
        profileLookup = profiles::get,
        cancelOutputTurn = cancelOutputTurn,
        cooldownNanos = settings.cooldownNanos,
        // edith-0.2 Phase B (B11): the intervention gate is live. It is built from the host's own decision model at
        // call time rather than injected here, because that model arrives through a capability the plugin only holds
        // once it is activated - and the host is free to take it back.
        gateForm = GateForm.NOUL,
        // Phase 5: the production session owner. Non-null switches every turn onto the store + `RequestFrame` path.
        conversationStores = conversationStores,
        // 0.24 S3 fix (C1): the production composition reads the link's live visibility, so a wake on an already-visible
        // panel starts the official 10 s at once instead of waiting for a `PANEL_SHOWN` that will not be repeated.
        isPanelVisible = isPanelVisible,
        // 0.3 Phase 3 Task 9d: the proactive hint path opens a hidden panel through the same readiness-aware seam the
        // wake detector uses, instead of submitting a hint the wearer cannot see.
        requestPanelOpen = requestPanelOpen,
        // 0.24 S3 (spec §5.5): the persisted「持续对话」switch. Read through the wiring's settings snapshot, which is the
        // same value the settings screen writes and `create` reads once at assembly time.
        continueDialogue = { settings.continueDialogue },
        // 0.5.1 (`C2`, spec §3.2): phone-only mode disables **only** the post-answer automatic continuation. The stored
        // switch still travels above (glasses mode and the plugin's queued-utterance seam read it); this seam is the
        // live `!phoneOnly()` multiplied in, so the phone's next sentence is the user's long-press ball ASR while the
        // initial wake window, the queued utterance and glasses mode are unchanged.
        continueAfterAnswer = { settings.continueDialogue && !phoneOnly() },
        // 0.24 S3 Task 14 fix (C2): the production player's presence. With speech off (the default) no player is built,
        // so a `both`/`voice` answer is display-only and continues on its `Displayed` acknowledgement.
        speechAvailable = speechAvailable,
        // 0.5 (`D213`): the phone-only model's final answer has no HUD, so it is spoken when a player exists.
        phoneOnly = phoneOnly,
        // 0.3 Phase 4B (`D189`): the live 「不拦截」 gate. A provider (not a snapshot) is what lets a settings save take
        // effect from the next tool call; the plugin passes it to both the policy and the command-level precheck wrapper.
        noInterception = noInterception,
    )

    companion object {
        /**
         * 0.4 Task 7 (`D201`, spec §4.3): what the conversation cycle reads as "the panel is visible".
         *
         * Detached mode's surface is the phone's own conversation tab, so the cycle must treat the panel as visible
         * and start its listening window immediately; no `PANEL_SHOWN` will ever arrive for a display that is not on
         * the glasses, and waiting for one would leave every detached turn stuck. Attached mode keeps the link's own
         * fact unchanged.
         *
         * One expression for `create` and the JVM rigs, so the shipped rule is what the tests drive.
         */
        fun cyclePanelVisible(detached: () -> Boolean, glassesVisible: () -> Boolean): () -> Boolean =
            { detached() || glassesVisible() }
    }
}
