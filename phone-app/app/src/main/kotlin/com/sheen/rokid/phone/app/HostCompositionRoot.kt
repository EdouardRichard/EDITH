package com.sheen.rokid.phone.app
import com.sheen.rokid.phone.ui.profile.ProbeConsent
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.actions.intent.CallPermission
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartWiring
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.actions.A11Y_CHANNEL
import com.sheen.rokid.phone.actions.ROOT_CHANNEL
import com.sheen.rokid.phone.model.ModelHttpLog
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.AuditLogView
import com.sheen.rokid.phone.bridge.asCxrActivity
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.MemoryLogView
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.actions.INTENT_CHANNEL
import com.sheen.rokid.phone.actions.SHIZUKU_CHANNEL
import com.sheen.rokid.phone.session.confirmation.SummaryDisplayer
import com.sheen.rokid.phone.session.confirmation.ConfirmationGlasses
import com.sheen.rokid.phone.model.ScriptedModelWire
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SkillDirectoryView
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.plugin.smarthelper.host.NoInterceptionGate
import com.sheen.rokid.phone.plugin.smarthelper.host.VisionGate
import com.sheen.rokid.phone.actions.ExecutionChannelStatus
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.StorageAccessSkillView
import com.sheen.rokid.phone.plugin.diagnostic.DiagnosticRegistration
import com.sheen.rokid.phone.platform.pluginTaskThreadFactory
import com.sheen.rokid.phone.storage.AndroidNamespaceStorage
import com.sheen.rokid.phone.model.EngineBundleProvisioning
import com.sheen.rokid.phone.storage.AndroidProfileRepository
import com.sheen.rokid.phone.actions.executionChannelStatuses
import com.sheen.rokid.phone.storage.AndroidEncryptedBlobStore
import com.sheen.rokid.phone.storage.AndroidSpeakerProfileStore
import com.sheen.rokid.phone.context.NotificationCategoryService
import com.sheen.rokid.phone.context.NotificationContentSource
import com.sheen.rokid.phone.context.androidDeviceStateProvider
import com.sheen.rokid.phone.perception.AndroidPerceptionProvider
import com.sheen.rokid.phone.perception.ScreenReadUnavailable
import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.phone.bridge.CxrHintLink
import com.sheen.rokid.phone.bridge.HintIdentity
import com.sheen.rokid.phone.bridge.UiState
import com.sheen.rokid.phone.bridge.UplinkReport
import com.sheen.rokid.phone.session.PlaybackGate
import com.sheen.rokid.phone.bridge.CxrConnection
import com.sheen.rokid.phone.session.SessionUiState
import com.sheen.rokid.phone.session.confirmation.ConfirmationUi
import com.sheen.rokid.phone.session.confirmation.ConfirmationPanel
import com.sheen.rokid.phone.session.SessionService
import com.sheen.rokid.phone.bridge.RokidConnection
import com.sheen.rokid.phone.audio.AudioIngressLoop
import com.sheen.rokid.phone.audio.SherpaAsrSession
import com.sheen.rokid.phone.bridge.CxrGlassesTarget
import com.sheen.rokid.phone.bridge.UnwiredInputPort
import com.sheen.rokid.phone.bridge.ExecutorTaskScope
import com.sheen.rokid.phone.bridge.CxrContextSignals
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.audio.AudioSegmentBridge
import com.sheen.rokid.phone.audio.AndroidSpeechPlayer
import com.sheen.rokid.phone.session.confirmation.NotificationSurface
import com.sheen.rokid.phone.session.PendingConfirmation
import com.sheen.rokid.phone.audio.AudioSegmentCapture
import com.sheen.rokid.phone.session.confirmation.ConfirmationNotifier
import com.sheen.rokid.phone.session.confirmation.ConfirmationPresenter
import com.sheen.rokid.phone.bridge.ConversationWireDisplay
import com.sheen.rokid.phone.session.ToneWakeChimeSink
import com.sheen.rokid.phone.session.WakeChime
import com.sheen.rokid.phone.session.confirmation.AndroidNotificationSurface
import com.sheen.rokid.phone.session.confirmation.ConfirmationChannelKind
import com.sheen.rokid.phone.session.confirmation.ConfirmationPhoneSurface
import com.sheen.rokid.phone.session.confirmation.confirmationPresentationChannel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.sheen.rokid.audio.AcousticLabeler
import com.sheen.rokid.audio.LocalSpeakerService
import com.sheen.rokid.audio.KeywordSpotterWakeWordEngine
import com.sheen.rokid.audio.PcmFramer
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.SpeakerPolicy
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.audio.android.EngineManifest
import com.sheen.rokid.audio.android.EngineManifestLoader
import com.sheen.rokid.audio.android.EngineModelStore
import com.sheen.rokid.audio.android.KeywordAssetBundle
import com.sheen.rokid.audio.android.SherpaEngineFactory
import com.sheen.rokid.audio.android.SherpaKeywordSpotterFactory
import com.sheen.rokid.audio.android.SherpaVadFactory
import com.sheen.rokid.audio.android.ValidatedModelBundle
import com.sheen.rokid.contracts.*
import com.sheen.rokid.host.HostEnvironment
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.host.storage.StorageAccessServiceImpl
import com.sheen.rokid.host.systemone.SystemOneServiceImpl
import com.sheen.rokid.host.embedding.EmbeddingServiceImpl
import com.sheen.rokid.phone.inference.laya.LayaInferenceEngineImpl
import com.sheen.rokid.phone.inference.laya.LayaOfficialHost
import com.sheen.rokid.phone.inference.litert.EmbeddingGemmaLiteRTEngine
import com.sheen.rokid.model.*
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.PerceptionToolObservation
import com.sheen.rokid.output.TraceRecorder
import com.sheen.rokid.plugin.*
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import com.sheen.rokid.phone.plugin.interview.InterviewAssistantPlugin
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.transport.GlassesEvents
import com.sheen.rokid.transport.PcmFormatEvidence
import com.sheen.rokid.storage.*
import com.sheen.rokid.phone.storage.*
import com.sheen.rokid.phone.actions.shizuku.RootProbe
import com.sheen.rokid.phone.actions.shizuku.RootStatus
import com.sheen.rokid.phone.actions.shizuku.ShizukuExecutionBackend
import com.sheen.rokid.phone.actions.shizuku.ShizukuTargetState
import com.sheen.rokid.phone.actions.shizuku.ShizukuUserServiceShell
import com.sheen.rokid.phone.actions.AtomicActionExecutor
import com.sheen.rokid.phone.actions.ToolSurface
import com.sheen.rokid.phone.actions.UnlockGatedActions
import com.sheen.rokid.phone.actions.a11y.AccessibilityExecutionBackend
import com.sheen.rokid.phone.actions.a11y.AccessibilityUi
import com.sheen.rokid.phone.actions.a11y.ScreenFilter as A11yScreenFilter
import com.sheen.rokid.phone.actions.a11y.ScreenFingerprint
import com.sheen.rokid.phone.actions.a11y.ScreenSnapshot
import com.sheen.rokid.phone.actions.a11y.ScrollDirection
import com.sheen.rokid.phone.actions.a11y.EdithAccessibilityService
import com.sheen.rokid.phone.actions.intent.IntentAtomicExecutor
import com.sheen.rokid.phone.actions.intent.LaunchAppExecutor
import com.sheen.rokid.phone.actions.intent.SystemActionExecutor
import com.sheen.rokid.phone.actions.device.DeviceKernelOperations
import com.sheen.rokid.phone.actions.device.DeviceTool
import com.sheen.rokid.phone.actions.device.DialTool
import com.sheen.rokid.phone.actions.device.OpenTool
import com.sheen.rokid.phone.actions.device.PrivilegedShellPort
import com.sheen.rokid.phone.actions.device.ScreenProjector
import com.sheen.rokid.phone.actions.device.ShellTool
import com.sheen.rokid.phone.actions.device.ToolCatalog
import com.sheen.rokid.phone.lock.KeystoreLockPinStore
import com.sheen.rokid.phone.lock.LockPinStore
import com.sheen.rokid.phone.lock.LockPinValidator
import com.sheen.rokid.phone.lock.LockShell
import com.sheen.rokid.phone.lock.LockState
import com.sheen.rokid.phone.lock.ScreenKeepAlive
import com.sheen.rokid.phone.lock.UnlockCoordinator
import com.sheen.rokid.phone.lock.UnlockGate
import com.sheen.rokid.phone.lock.UnlockOutcome
import com.sheen.rokid.device.AccessibilityPort
import com.sheen.rokid.device.DeviceKernel
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.ObservationRenderer
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.ScreenFilter as DeviceScreenFilter
import com.sheen.rokid.phone.actions.intent.AppLaunchResolver
import com.sheen.rokid.phone.actions.intent.ContextIntentLauncher
import com.sheen.rokid.phone.actions.intent.IntentActionExecutor
import com.sheen.rokid.phone.actions.intent.PackageManagerAppCatalog
import com.sheen.rokid.phone.context.AcousticSource
import com.sheen.rokid.phone.context.CalendarSource
import com.sheen.rokid.phone.context.ClipboardSource
import com.sheen.rokid.phone.context.ForegroundAppSource
import com.sheen.rokid.phone.context.GlassesStateSource
import com.sheen.rokid.phone.context.MediaSessionSource
import com.sheen.rokid.phone.context.NotificationCategorySource
import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.phone.model.ProfileProbeGate
import com.sheen.rokid.phone.model.profileStartupError
import com.sheen.rokid.phone.artifacts.FileArtifactStore
import com.sheen.rokid.phone.model.ArtifactMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 0.25 Task 14 (R76): the fixed synthetic image the `testImage` probe sends — a 32×32 solid-red PNG.
 *
 * One constant, never a file read and never a user image: the image probe proves the IMAGE operation end to end
 * (encode → provider → terminal), and its content is deliberately trivial so the probe carries nothing to protect.
 */
private const val PROBE_IMAGE_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAJ0lEQVR42u3NsQkAAAjAsP7/tF7hIASyp6lTCQQCgUAgEAgEgi/BAjLD/C5w/SM9AAAAAElFTkSuQmCC"
private const val PROBE_IMAGE_WIDTH = 32
private const val PROBE_IMAGE_HEIGHT = 32
private val PROBE_IMAGE_PNG: ByteArray = Base64.getDecoder().decode(PROBE_IMAGE_BASE64)

/**
 * edith-0.3 Phase 2.2 Task 6: the reason a screen tool's production observation reports when the connected
 * accessibility read itself fails (as opposed to the channel being off, which has its own reason).
 */
private const val TOOL_OBSERVATION_FAILED = "OBSERVE_FAILED"

/**
 * Whether a STOP's validated identity still names the session the host is running.
 *
 * 0.23 P5 review: the link validates a STOP against the identity it knows, then calls the composition. A **new
 * session** (stop/start) has a new id and must not accept the old STOP; a **pause/resume** keeps the id but bumps
 * `HostRuntime.generation` without re-introducing the session to the glasses, so comparing the generation here would
 * reject every legitimate STOP after a resume. The link has already checked the generation against the glasses'
 * session; the composition only needs the session id to rule out a different session.
 */
internal fun stopSessionMatches(
    current: Pair<com.sheen.rokid.contracts.SessionId, com.sheen.rokid.contracts.Generation>?,
    sessionId: String,
): Boolean = current != null && current.first.value == sessionId

class HostCompositionRoot internal constructor(
    val host: HostRuntime,
    val profiles: ProfileRepository,
    val model: ModelClient,
    private val environment: HostEnvironment?,
    resources: List<AutoCloseable> = emptyList(),
    val trace: TraceRecorder = TraceRecorder(),
    val profileTester: ProfileTester? = null,
    private val profileConsent: (ProbeConsent?) -> Unit = {},
    private val namespaceFactory: ((String) -> StorageHandle)? = null,
    val speakerProfiles: SpeakerProfileStore? = null,
    /** Production App wiring for the CXR transport; null keeps the audio capability honestly missing. */
    val rokidConnectionFactory: ((Any) -> CxrConnection)? = null,
    /** Initial RAM-only tee of the live CXR PCM; see [installRawPcmObserver]. */
    internal val initialRawPcmObserver: (ByteArray) -> Unit = {},
    /**
     * The second route an activity result can take into the transport: the connection instance this composition keeps
     * in its own holder (in production the same object `activeConnection` points at). It must report the **same
     * verdict** as [CxrConnection.onAuthorizationResult], so the page state below stays a fact the transport asserted
     * rather than one this class inferred (review I-3, Minor 6); `null` means "not this transport's answer".
     */
    private val authorizeResult: (Int, Intent?) -> CxrAuthorizationOutcome? = { _, _ -> null },
    /** T18 display path for a live CXR connection; null keeps the assembly without one (tests, unwired builds). */
    private val hintLink: CxrHintLink? = null,
    /**
     * Phase C (spec §6.7 助手页签): the conversation both screens read.
     *
     * One object, filled by [ConversationWireDisplay] as the plugin reports what was said, and read by the assistant
     * tab and by the glasses - so the two cannot disagree about content, order or route. It is a constructor parameter
     * rather than a field because the `Capability.ConversationDisplay` provider is built in `create` before this
     * instance exists, and the provider and the tab must hold the **same** object. Bounded in `ConversationLog`, so a
     * long session cannot grow either view without limit.
     */
    internal val conversationLog: ConversationLog = ConversationLog(),
    /**
     * The production [ConversationWireDisplay], built once in [create] and passed here so tests can assert the
     * **actual** wire it forwards to.
     *
     * Task 6 review gate: the provider must not keep using the silent 3-arg constructor while the official commands
     * look wired. `create` builds this with `wire = hintLink`; a composition built by hand may pass its own display.
     */
    internal val conversationDisplay: ConversationWireDisplay? = null,
    /**
     * Marshals a vendor-thread callback onto a host-owned thread.
     *
     * `IGlassesEventListener`/inbound custom-command callbacks arrive on the vendor's own thread; host policy
     * (`HostRuntime.onEvent`, the 10 s timer owner) must not run there. Production injects the composition's deadline
     * scheduler, whose executor is already part of the composition; tests default to running the task inline.
     */
    private val dispatchHostEvent: (() -> Unit) -> Unit = { it() },
    /**
     * Phase C (spec §6.7 技能页签): the SKILL directory as the page sees it.
     *
     * A seam rather than a direct call, so the page can be tested without a composition and so the composition decides
     * what "the directory" means (it is the same `Capability.StorageAccess` provider a turn reads through).
     */
    internal var skillDirectory: SkillDirectoryView? = null,    /**
     * The smart plugin and the settings-built configuration it must be given before activation (Task 14a item 1).
     *
     * Null keeps a composition that has no smart plugin registered (several T17/T18 harnesses build one), so the
     * configuration/activation steps below simply do not run. The `Capability.WakeWord` provider is
     * [SmartWiring.wakeWords] - the same instance the plugin declares its configured words to - so the assembly
     * holds exactly one word set (Task 14a-fix item 5).
     */
    private val smartWiring: SmartWiring? = null,
    /**
     * The store the settings screen writes through (Task 14b).
     *
     * Null for the harnesses that build a root by hand: they have no blob store to hand, and the settings screen is
     * the only caller of the two accessors below, so a root without one refuses those calls instead of pretending to
     * save. Production passes the same instance `create` loaded the composition's own settings from, so a save and
     * the next load read one blob.
     */
    private val smartSettingsStore: SmartSettingsStore? = null,
    /**
     * edith-0.3 Phase 2 Task 10: the **live** 「感知截图」 switch.
     *
     * [create] builds it from the persisted [SmartSettings.visionEnabled] and hands the same instance to the perception
     * provider and the `device` tool, so a save on the settings screen takes effect on their very next call. A
     * hand-built composition (tests, harnesses) keeps the default on, which is the shipped setting.
     */
    private val visionGate: VisionGate = VisionGate(true),
    /**
     * 0.3 Phase 4B (`D189`): the **live** global 「不拦截」 gate.
     *
     * [create] seeds it from the persisted [SmartSettings.noInterception] (default off); [saveSmartSettings] updates
     * the same instance, and the settings page's banner reads its current value. B3 hands
     * [NoInterceptionGate.provider] to `SmartWiring`, which passes it to the plugin's policy and shell-precheck wrapper,
     * so the gate is consulted live by every tool call (spec §5.1). A hand-built composition (tests, harnesses) keeps
     * the safe default (off).
     */
    internal val noInterceptionGate: NoInterceptionGate = NoInterceptionGate(false),
    /**
     * `D53`② (2026-10-11): the **persisted, global** 「主动辅助」 switch - it replaced P3.2's session-scoped one.
     *
     * A stored settings value (`SmartSettings.proactiveEnabled`), **on by default**, written through the same blob as
     * the cooldown and sensitivity. It survives a stop, a transition back to `Idle`, a reconnect, an app restart and a
     * phone restart, and **no path re-arms it** except the user's own call to [ProactiveArming.set]. It is still the
     * host's gate on producing perception events - see [armedAcousticSink] - so `create`'s chain emits no
     * `AcousticEvent` while it is off; that is what makes turning it off a real off switch on the device rather than a
     * UI state. (The old KDoc here promised the opposite - session-scoped, off by default, returned to off by [stop] -
     * which `D53`② repealed; the code had already moved on, and a claim that can no longer happen must not stay
     * wordable.)
     */
    internal val proactiveArming: ProactiveArming = ProactiveArming(),
    /** P5 (spec §S4): shared gate between speech player and wake/acoustic suppression. */
    val playbackGate: PlaybackGate = PlaybackGate(),
) : AutoCloseable {
    /**
     * P5 (spec §S5): the 「语音朗读」 switch state, persisted globally and **off by default**.
     * Loaded from [SmartSettings.speechEnabled] at assembly time; written back through the same store.
     */
    private val speechOn = MutableStateFlow(false)
    internal val speechEnabled: StateFlow<Boolean> = speechOn.asStateFlow()

    /**
     * The lock-screen PIN store. Production replaces the no-op in [create] with the Keystore-backed store; a test
     * composition keeps the no-op, so no test can accidentally write a real credential.
     */
    internal var lockPinStore: LockPinStore = NoLockPinStore
        private set

    internal val lockPinConfigured: Boolean get() = lockPinStore.hasPin()

    /**
     * Debug-only seam (2026-10-20): the same production coordinator the tool catalog calls, exposed so a host-started
     * Activity can measure the lock-screen path without arming a model turn. Production never reads it.
     */
    internal var unlockCoordinatorForProbe: UnlockGate? = null

    internal fun ensureUnlockedForProbe(): UnlockOutcome =
        unlockCoordinatorForProbe?.ensureUnlocked(requireAccessibility = true) ?: UnlockOutcome.STILL_LOCKED

    /** Debug-only seam: the production catalog, so an Activity can exercise the real gate + tool body. */
    internal var toolCatalogForProbe: com.sheen.rokid.phone.actions.device.ToolCatalog? = null

    /**
     * edith-0.3 Phase 4A Task 4: the one `Capability.Actions` provider the composition hands the host, exposed for the
     * JVM gate only. Production never reads it; it lets `ToolFaceCompositionTest` assert the **wired** 22-name face
     * rather than a hand-built look-alike.
     */
    internal var toolSurfaceForProbe: com.sheen.rokid.phone.actions.ToolSurface? = null

    /**
     * edith-0.3 Phase 2.2 Task 6: the production `open` tool, exposed only so the JVM gate can prove that the
     * composition wires the **same** `AndroidPerceptionProvider`-backed observation source into `OpenTool(observe = ...)`
     * as into the catalog. Production never reads it; a composition built by hand leaves it `null`.
     */
    internal var openToolForProbe: com.sheen.rokid.phone.actions.device.OpenTool? = null

    /**
     * Debug-only seam: runs one production tool call and returns its raw [com.sheen.rokid.plugin.ActionResult].
     *
     * The result is **not** a rendered envelope: `observation` carries the screen, `execution` the stable facts, and
     * `detail` is tool-specific data only. A host-started probe must read those members (the debug probes use their own
     * `ProbeResultProjection`); parsing `detail` for an envelope silently yields no screen (Task 8 review).
     */
    internal fun executeToolForProbe(name: String, argsJson: String) =
        toolCatalogForProbe?.execute(name, argsJson)

    /**
     * Validates and stores the PIN. The return value is the only place a UI learns whether the write succeeded; the
     * PIN itself is never logged. An invalid value is refused before it reaches the Keystore.
     */
    internal fun saveLockPin(pin: String): Boolean {
        val normalized = LockPinValidator.normalize(pin) ?: return false
        return runCatching {
            lockPinStore.save(normalized)
            P3Log.line("P3_LOCKPIN configured=true")
            true
        }.getOrElse {
            P3Log.line("P3_LOCKPIN configured=false reason=STORE_FAILED")
            false
        }
    }

    internal fun clearLockPin() {
        runCatching { lockPinStore.clear() }
        P3Log.line("P3_LOCKPIN configured=false")
    }

    /**
     * P5 (spec §S5): the user's switch for 「语音朗读」. Writes through [smartSettingsStore] so the choice persists.
     * A failed read aborts the write (same pattern as [ProactiveArming.set]): the UI still moves, but the blob is not
     * silently overwritten with defaults.
     */
    internal fun setSpeechEnabled(enabled: Boolean) {
        speechOn.value = enabled
        val target = smartSettingsStore ?: return
        runCatching { target.load() }.onFailure {
            android.util.Log.i("EdithHost", "speech toggle: settings unreadable, not persisting this change")
        }.getOrNull()?.let { stored -> runCatching { target.save(stored.copy(speechEnabled = enabled)) } }
    }
    /**
     * RAM-only tee of the live CXR PCM used by the T17 controlled format bring-up. Defaulted to a no-op
     * so normal app behaviour is unchanged; the observer must never persist or export the bytes. It stays
     * assignable because the bring-up harness arms it after the App composition exists but before the lazy
     * connection is built.
     */
    internal var rawPcmObserver: (ByteArray) -> Unit = initialRawPcmObserver
        private set

    /**
     * Shares the observer slot with the lazy connection factory, which is created before [installRawPcmObserver] can
     * be called. Null only for compositions built outside [create] (tests that supply their own factory).
     */
    private var observerHolder: Array<(ByteArray) -> Unit>? = null

    /**
     * Keeps the public field and the factory's shared slot in step.
     *
     * Both paths must agree: the field is what callers and tests read, the holder is what the lazy connection calls.
     * Updating only one of them is exactly the defect that made every capture read zero bytes.
     */
    private fun armObserver(observer: (ByteArray) -> Unit) {
        rawPcmObserver = observer
        observerHolder?.set(0, observer)
    }

    /** Resources that must be released with this composition; later additions close first. */
    private val resources: MutableList<AutoCloseable> =
        resources.toMutableList().also { list -> hintLink?.let(list::add) }
    /** Set when an activation was refused for missing capabilities, so a later display can retry it once. */
    @Volatile private var awaitingDisplayForActivation = false

    /**
     * The mapping the display retry was last attempted against, so one exchange is not retried twice.
     *
     * Both display signals can arrive for the same `PONG` (the transition **and** "a mapping is live"), and each
     * exchange mints a fresh `syncId`, so the identifier is what separates "the same sync, reported twice" from "a
     * later renewal" - which is a fresh chance for the missing capability to have appeared and must be retried.
     */
    private var retriedMappingSyncId: String? = null

    /**
     * Debug-only: submits the next fixed hint through the diagnostic probe plugin, or null when no probe is
     * registered (release builds). The UI offers a button only when this is non-null.
     */
    internal var diagnosticHintSender: (() -> Boolean)? = null

    /**
     * Debug-only: submits a hint of a **given** text through the diagnostic probe plugin, or null when no probe is
     * registered. Unlike [diagnosticHintSender] this is driven from a host shell (P3.2 Stage 0's capacity probe) and
     * its text must reach the display unchanged, so the caller owns the length. Never present in a release build.
     */
    internal var diagnosticHintTextSender: ((String) -> Boolean)? = null

    /**
     * The Stage B foreground-app source (Task 11), or null for a root built outside `create` (tests, harnesses).
     *
     * Exposed because the settings screen has to tell the user whether the special permission is granted - the source
     * knows, and a second app-op check in the UI would be a second answer to the same question.
     */
    internal var foregroundAppSource: ForegroundAppSource? = null

    /** The Stage B notification-category source (Task 12), or null outside `create`. */
    internal var notificationCategorySource: NotificationCategorySource? = null

    /** The Stage B calendar source (Task 13), or null outside `create`. */
    internal var calendarSource: CalendarSource? = null

    init {
        // Activation runs when the scene becomes ready, which is before the first clock mapping exists, so a plugin
        // whose only missing capability is the display is refused at that moment. Two signals retry it, because the
        // mapping that fixes the refusal is not always a fresh transition: the display becoming real on a link that
        // was not live before, and **every** successful exchange - the link's steady state is "live and re-syncing
        // in place", which fires no transition at all (D10, measured on the K40 2026-10-09, where a refused
        // activation stayed unretryable for a whole session while `mapping live` recurred every ~20 s).
        //
        // D7: the same two signals are also the only moments the answerable set can grow, so the capability panel is
        // recomputed here as well - **outside** that retry's `awaitingDisplayForActivation` gate, which is closed
        // whenever nothing was refused and would otherwise leave the panel stale for a perfectly healthy session.
        hintLink?.onDisplayBecameLive = { onDisplaySignal() }
        hintLink?.onMappingLive = { onDisplaySignal() }
    }

    /** One display signal: the panel follows what is wired now, then an activation refused for the display is retried. */
    private fun onDisplaySignal() {
        mutableState.update { it.copy(capabilityReasons = capabilityReasons(), executionChannels = executionChannels()) }
        retryActivationForDisplay()
    }
    /**
     * What each capability the environment cannot answer is reported as, **recomputed on every read** (D7).
     *
     * This used to be a `val` computed once at construction and stored in the UI state. The environment's
     * `availableCapabilities` is a live derivation - `TextOutput` appears only once a clock mapping makes the display
     * real - so the stored copy was a start-up snapshot: measured on the K40 on 2026-10-09, the 会话 page still read
     * `眼镜显示/尚不可用` after the display had become real and a plugin had activated on the strength of it. Nothing
     * gates on this map; it is the panel's, which is exactly why it must not be able to lie about the present.
     */
    private fun capabilityReasons(): Map<Capability, String> =
        Capability.entries.filter { it !in (environment?.availableCapabilities ?: emptySet()) }
            .associateWith { reasonFor(it) }

    private fun reasonFor(capability: Capability): String = when (capability) {
        Capability.Audio -> "TRANSPORT_MISSING"
        Capability.Asr, Capability.Speaker -> "ENGINE_MISSING"
        Capability.Model -> "MODEL_OPERATION_UNAVAILABLE"
        Capability.TextOutput -> "DISPLAY_MISSING"
        Capability.SpeechOutput -> "SPEECH_OUTPUT_MISSING"
        Capability.Storage -> "HOST_STORAGE_PROVIDER_MISSING"
        Capability.Actions -> "ACTION_PROVIDER_MISSING"
        Capability.Context -> "CONTEXT_PROVIDER_MISSING"
        Capability.WakeWord -> "WAKE_PROVIDER_MISSING"
        // P3.3: the row the confirmation channel reports when its presenter is not wired (until Task 13 provides it).
        Capability.Confirmation -> "CONFIRMATION_PROVIDER_MISSING"
        // edith-0.2 Phase A: local model services
        Capability.SystemOne -> "SYSTEM_ONE_MODEL_MISSING"
        Capability.Embedding -> "EMBEDDING_MODEL_MISSING"
        // edith-0.2 Phase B: the user's SKILL directory on shared storage
        Capability.StorageAccess -> "STORAGE_ACCESS_PROVIDER_MISSING"
        // edith-0.2 Phase C: the conversation view needs somewhere to be shown, so it is refused for the same reason
        // the text output is.
        Capability.ConversationDisplay -> "DISPLAY_MISSING"
        // edith-0.3 Phase 1: the device profile/state channel; the Android provider is wired by Task 2.
        Capability.DeviceState -> "DEVICE_STATE_PROVIDER_MISSING"
        // edith-0.3 Phase 2 Task 7 wires the perception port; this row remains for a composition built without one
        // (tests/harnesses). The plugin still treats absence as "no perception block", never a failed turn.
        Capability.Perception -> "PERCEPTION_PROVIDER_MISSING"
    }
    private val mutableState =
        MutableStateFlow(
            SessionUiState(
                SessionState.Idle, null, capabilityReasons(), null,
                // Empty rather than computed here: the probes are installed by `create` after this flow exists, and
                // at Idle the panel has no session whose channels it could be describing.
                executionChannels = emptyList(),
            ),
        )
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    private val observation = host.observeState { session ->
        // `D53`② (2026-10-11): the 「主动辅助」 switch is a **persisted global setting**, so nothing here resets it -
        // a session ending must not disarm a feature the user asked to have on, and it must not re-arm one they turned
        // off either. There is deliberately no `deactivate()` call on this path any more.
        mutableState.update { it.copy(session = session, capabilityReasons = capabilityReasons(), executionChannels = executionChannels()) }
        // 0.24 S5 Task 20: `HostRuntime.interrupt()` is what the transport's `onInterrupted` does for a CXR close
        // (`onSessionClosed`/session-terminating/audio failure). The link state must not outlive the session it names.
        if (session == SessionState.Interrupted) onCxrLinkInterrupted()
    }
    private var closed = false
    private var activatedPlugin: String? = null
    private var activeConnection: CxrConnection? = null

    /** P3.3: the settings page's reader over the plugin's audit document; null when storage is not wired. */
    internal val auditLog: AuditLogView? by lazy {
        namespaceFactory?.let { factory -> AuditLogView(factory("smart-assistant")) }
    }

    /**
     * P3.4: the session page's reader over the plugin's long-term-memory document (the same namespace, a different
     * key); null when storage is not wired, which is exactly when the plugin itself has no memory to show.
     */
    internal val memoryLogView: MemoryLogView? by lazy {
        namespaceFactory?.let { factory -> MemoryLogView(factory("smart-assistant")) }
    }

    /**
     * Phase 7 F4 (§3.3): the exact storage handle behind the smart plugin's memory document, for the **debug-only**
     * memory backup/seed/restore receiver.
     *
     * `MemoryLog.KEY` names a key in the plugin's own `smart-assistant` namespace; this accessor reaches that same
     * namespace, so a backup saves the document the plugin actually writes and a restore writes back into it. It is
     * `internal` and has **no production caller**: only `src/debug` receivers invoke it, and a release build contains
     * neither them nor any other call site. `null` means this build has no storage wired, which the receiver reports as
     * `refused reason=NO_STORE` rather than treating as an empty document.
     */
    internal fun memoryStoreForProbe(): StorageHandle? = namespaceFactory?.invoke("smart-assistant")

    /**
     * Phase 7 F4 (§3.2): the smart plugin this composition registered, for the **debug-only** measurement receivers.
     *
     * The receiver must reach the same plugin instance the host activates, or setting the form would change a
     * different object's state; [smartWiring] is the composition's own holder and this accessor exposes it read-only.
     * Null for the harnesses that build a composition with no smart plugin. `internal`, and no production code reads
     * it: only `src/debug` receivers call it, which is what keeps the measurement switch out of a release build.
     */
    internal val smartPluginForProbe: SmartAssistantPlugin? get() = smartWiring?.plugin

    /**
     * P3.3 (spec §17.2): one probe per execution channel, logged once per session as `P3_EXEC channel=… available=…`.
     *
     * The pair is id to a **live** question, not a cached answer: the accessibility service is switched off in system
     * settings and Shizuku's binder dies, both while the App runs. The line is what a device stage reads to tell "the
     * channel is down" from "the channel is up but refused the call".
     */
    internal var channelProbes: List<Pair<String, () -> Boolean>> = emptyList()

    /**
     * R-9 device probe (debug builds only): the privileged shell this composition's execution layer runs commands
     * through, so a debug-only instrument can drive **this** instance against a real Binder reply.
     *
     * A field rather than an accessor on the executors for two reasons: a second `ShizukuUserServiceShell` would open a
     * second Shizuku user-service binding and disturb the app's real channel, and the execution router keeps its
     * executors private. It is assigned in [create] — a companion function, so it reaches this `internal` member — and
     * only when `BuildConfig.DEBUG` is true; `BuildConfig.DEBUG` is a compile-time constant, so the assignment is dead
     * code in a release build and R8 removes it with the whole branch. Nothing in production reads this field, so it
     * changes no production behaviour. `null` means "this build was not wired for the probe", never "the channel is
     * down": the channel's own answer is [ShizukuUserServiceShell.ready].
     */
    internal var privilegedShellForProbe: ShizukuUserServiceShell? = null

    /**
     * The Root row's informational line's **source**: the probe, asked once per read.
     *
     * The panel states the device's own answer (`Root：不可用（设备无 su）`) rather than a bare code, because a missing
     * `su` is a fact about the phone and not a fault of this App - spec §17.2 asks for a 告知行 here. It is the probe
     * rather than a pre-rendered line because the line must say *why*: a `su` that never answered is a different fact
     * from an absent binary, and the row may only state what the probe observed (leftover-sweep review, Important 1+2).
     */
    internal var rootStatusProbe: (() -> RootStatus)? = null

    /**
     * P3.3 (spec §17.4): the notification shade, or null when this composition has none (several test rigs build one).
     *
     * The question reaches it only while the session page is **not** visible - that is the entire point of the
     * notification (user decision 2026-10-10), and a second copy of a question the user is already looking at is noise.
     */
    internal var notificationSurface: NotificationSurface? = null

    /** Whether the session page is on screen; `MainActivity.onStart`/`onStop` keeps this current. Volatile because the
     * turn thread reads it when a question goes up while the UI thread writes it. */
    @Volatile
    private var uiIsVisible = true

    private fun notifier(): ConfirmationNotifier? = notificationSurface?.let { ConfirmationNotifier(it) }

    /**
     * P3.3 (§17.4): raises the session page - which carries the question's card - to the front.
     *
     * Owner directive 2026-10-10: a question the user cannot see has to reach them like a system popup, not as a shade
     * entry they must go looking for. Returns false when this build cannot raise anything (no overlay permission, or a
     * platform that refuses the background start), in which case the notification is the surface. Injectable so the rule
     * below is pinned on the JVM instead of only on a phone.
     */
    internal var raiseConfirmationUi: (() -> Boolean)? = null

    /**
     * 0.3 Phase 4B (B5, spec §5.4): whether this build **may** raise the page, without raising it.
     *
     * [raiseConfirmationUi] has a side effect, so it cannot answer "would raising work?" for the availability check.
     * Production points this at the overlay permission (`Settings.canDrawOverlays`); null (a hand-built composition or
     * a JVM rig) means "no overlay", which is the honest answer rather than assuming the permission.
     */
    internal var canRaiseConfirmationUi: (() -> Boolean)? = null

    /** One root probe for one read, so the row and its note cannot cost two timeouts. Null when no probe is wired. */
    private fun rootStatus(): RootStatus? = rootStatusProbe?.let { probe -> runCatching { probe() }.getOrNull() }

    /**
     * The panel's channel rows, from the **same** probes the `P3_EXEC` lines use.
     *
     * `status` is a parameter so a caller that already probed root for its log line can hand that one answer to both.
     * The Root row appears whenever a root probe is wired - a composition built without one (several test rigs) has no
     * Root row and no `P3_EXEC channel=root` line, exactly as before this change.
     */
    private fun executionChannels(status: RootStatus? = rootStatus()): List<ExecutionChannelStatus> {
        val rootRow = if (rootStatusProbe == null) emptyList() else
            listOf(ROOT_CHANNEL to (status == RootStatus.AVAILABLE))
        return executionChannelStatuses(
            channelProbes.map { (id, up) -> id to runCatching { up() }.getOrDefault(false) } + rootRow,
            status?.let(RootProbe::noteFor),
        )
    }

    /**
     * Republishes the channel rows when a channel changes state **without** the session changing.
     *
     * The privileged channel binds asynchronously, so a session's first probe honestly reads "not yet" and the channel
     * becomes usable a moment later; nothing else would refresh the panel, and a panel that keeps saying a channel is
     * down after it came up is lying about the present - the one thing this map must never do.
     */
    internal fun refreshExecutionChannels() {
        mutableState.update { it.copy(executionChannels = executionChannels()) }
    }

    /** P3.3: brings the privileged channel up (bind, and ask for its permission when it is not granted yet). */
    private var executionChannelBringUp: (() -> Unit)? = null

    /**
     * P3.3: the question-asker the plugin blocks on, kept so `stop` can withdraw anything still up.
     *
     * `internal` rather than private for the same reason [channelProbes] and [notificationSurface] are: the answer path
     * is a seam a JVM test has to be able to build, and the alternative is proving "a stale tap settles nothing" only
     * on a device.
     */
    internal var confirmationPresenter: ConfirmationPresenter? = null

    /**
     * The debug-only scripted model wire (see [ScriptedModelWire]), or null before `create` has installed it.
     *
     * It is reachable only from the debug source set's broadcast receiver, and [armScriptedModelAnswer] refuses outside
     * a debug build, so a release APK carries an inert interceptor and no way to arm it.
     */
    private var scriptedModelWire: ScriptedModelWire? = null

    /**
     * Debug-only: answers the model request from a script rather than the network.
     *
     * Spec §17.8's Stage C needs an L3/L4 call to travel the real policy, the real confirmation gate, the real
     * privileged executor and the real audit; the configured model would not emit one on the K40 (four attempts, all
     * `steps=0`). Scripting the transport removes only the model's willingness from the question - consent, the
     * capability gate, the budget, the policy, the gate and the executor all still run. Returns `false` in a release
     * build, where it can never arm anything.
     *
     * B8 (§6.2/§6.3) uses the same instrument for the same reason: the configured model never calls `notify_user`, so
     * [calls]/[fail] let a device turn carry zero, one or two notifications, or end in a provider failure, while the
     * per-turn ledger, the mid-step downgrade and the fallback all stay real.
     */
    fun armScriptedModelAnswer(
        action: String,
        argsJson: String,
        text: String,
        calls: Int = 1,
        fail: Boolean = false,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        return scriptedModelWire?.arm(action, argsJson, text, calls, fail) ?: false
    }

    /** Debug-only counterpart of [armScriptedModelAnswer]: hands the transport back to the real network. */
    fun disarmScriptedModelAnswer() {
        if (!BuildConfig.DEBUG) return
        scriptedModelWire?.disarm()
    }

    /**
     * The self-audio gate the wake detector consults (Task 14b), and the **only** gate this composition has.
     *
     * A composition-owned `val` exposed to the UI so P3.2's speech output can bracket a real playback with
     * [PlaybackGate.begin]/[PlaybackGate.end]. It is the same instance the chain's detector reads: `create` hands it
     * to the chain through [playbackSuppression], so `begin()` on this exposed handle suppresses the detector this
     * composition's own chain built (Task 14b-fix item 1 - a second, local gate inside `create` left this handle
     * inert).
     *
     * In P3.1 it is always open and inert: hints carry no speech and no provider answers `Capability.SpeechOutput`,
     * so there is nothing to suppress yet. P5 drives it from the speech player's begin/end callbacks.
     * Moved to constructor parameter for P5 so the speech player and the composition share one instance.
     */

    /**
     * The settings the composition was assembled with, read back from the same store the screen writes through.
     *
     * Read fresh rather than returned from the assembled snapshot because the screen's job is to edit the stored
     * value, and showing the snapshot after a save would be misleading. The composition's own configuration is
     * deliberately unaffected until the next assembly - which is what the screen's copy says.
     */
    fun storedSettings(): SmartSettings =
        requireNotNull(smartSettingsStore) { "SETTINGS_STORE_UNAVAILABLE" }.load(profiles)

    /**
     * Persists one settings edit; the next App start's composition picks it up (this one never re-reads).
     *
     * edith-0.3 Phase 2 Task 10: the 「感知截图」 switch is the exception to "next App start" - it is a live privacy
     * control, so a successful save also applies it to [visionGate] immediately. A save that turns vision **off** is
     * applied before the blob write and stays applied if the write fails: persistence is the durability half, while a
     * failed save must never leave capture enabled after the user asked for off. A save that turns vision on applies
     * only after the blob accepted it, so a failed write cannot arm capture.
     *
     * 0.3 Phase 4B (`D189`): the 「不拦截」 gate follows the same half-live rule for a stronger reason. Turning it
     * **on** applies only after the blob accepted it (a failed write must not leave the process running unconfirmed
     * by a mode the next restart will not remember); turning it **off** applies before the write and stays applied if
     * the write fails (a failed save must never keep unconfirmed execution alive after the user disabled it).
     */
    fun saveSmartSettings(settings: SmartSettings) {
        val store = requireNotNull(smartSettingsStore) { "SETTINGS_STORE_UNAVAILABLE" }
        if (!settings.visionEnabled) visionGate.set(false)
        if (!settings.noInterception) noInterceptionGate.set(false)
        store.save(settings)
        visionGate.set(settings.visionEnabled)
        noInterceptionGate.set(settings.noInterception)
    }

    /**
     * Descriptors of the plugins this composition registers, keyed by plugin id.
     *
     * The composition owns the plugins it registers, so it can answer "does the plugin I just activated declare
     * X?" itself. The alternative - a public `HostRuntime.activeDescriptor()` - was one accessor past the granted
     * read-only identity surface (Task 14a-fix item 4), and the descriptor of the plugin *this* composition just
     * registered is exactly what a side effect like arming the ears must be gated on. A plugin registered straight
     * on the host by some harness is simply not known here, and "declares nothing" is then the honest answer.
     */
    private val descriptors = java.util.concurrent.ConcurrentHashMap<String, PluginDescriptor>()

    /**
     * Registers [plugin] with the host and remembers what it declares.
     *
     * Every registration the composition performs goes through here, so [declares] covers the smart plugin, the
     * interview shell and the debug-only plugins alike; `create` uses it for all of them.
     */
    internal fun registerPlugin(plugin: PluginLifecycle) {
        host.register(plugin)
        descriptors[plugin.descriptor.pluginId] = plugin.descriptor
    }

    /** True when the plugin this composition knows under [pluginId] declares [capability]. */
    private fun declares(pluginId: String, capability: Capability): Boolean =
        descriptors[pluginId]?.capabilities?.contains(capability) == true

    private data class StartGrant(val token: String, val pluginId: String, val expires: Long)
    private var pendingStart: StartGrant? = null
    private val clock = environment?.clock ?: object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
    fun profileViewModel(): ProfileViewModel? {
        val repository = profiles as? AndroidProfileRepository ?: return null
        return ProfileViewModel(repository, profileTester ?: return null, profileConsent, ::applyProfileSelection)
    }
    fun namespace(pluginId: String): StorageHandle {
        require(pluginId in setOf("smart-assistant", "interview-assistant") || (BuildConfig.DEBUG && pluginId == DIAGNOSTIC_PLUGIN_ID))
        return namespaceFactory?.invoke(pluginId) ?: throw ContractError.CapabilityMissing("Persistent storage unavailable")
    }
    /**
     * Applies a profile selection the user made to the plugin it belongs to.
     *
     * The configuration is rebuilt whole ([configurationFor]) rather than patched with the profile fields:
     * `HostRuntime.configure` replaces the stored configuration, so sending only the profile selection would drop the
     * settings-derived keys the plugin runs with (Task 14a-fix item 1). A plugin that is already active must be
     * paused first, because the host refuses to configure an active plugin.
     */
    @Synchronized private fun applyProfileSelection(pluginId: String, profile: ModelProfile?) {
        if (closed) return
        if (activatedPlugin == pluginId) { host.pause(); activatedPlugin = null }
        val configuration = configurationFor(pluginId, profile) ?: return
        check(host.configure(pluginId, configuration)) { "Profile selection unavailable" }
    }

    /**
     * The complete configuration this composition owns for [pluginId], or null when it owns none.
     *
     * **One** configuration per activation. The smart plugin's settings keys and the user's profile selection used to
     * be applied as two successive `configure` calls, and `HostRuntime.configure` replaces the stored configuration -
     * so the second call silently dropped the first one's keys and the plugin fell back to its own defaults for the
     * risk ceiling, the wake words and the whitelist the user had configured (Task 14a-fix item 1). It is also built
     * only for the plugin it belongs to: the diagnostic text plugin's key set is closed and different, so sending it
     * the smart keys refuses the whole activation (item 2). A plugin this composition does not register (and so knows
     * no keys for) is left untouched.
     */
    private fun configurationFor(pluginId: String, profile: ModelProfile?): PluginConfiguration? {
        if (!descriptors.containsKey(pluginId)) return null
        smartWiring?.let { wiring ->
            if (pluginId == wiring.plugin.descriptor.pluginId) return wiring.configuration(profile)
        }
        if (pluginId == DIAGNOSTIC_PLUGIN_ID) return PluginConfiguration(1, diagnosticValues(profile))
        return null
    }

    /**
     * The profile that applies to [pluginId].
     *
     * The App stores one selection per plugin, so that selection wins where this composition has such a store (the
     * Android repository). Without one - a JVM harness, or a plugin the user never selected for - the smart plugin
     * falls back to the settings' own resolved choice, because that is the profile the settings name; any other
     * plugin gets no profile, and its own shape fills in the contract-valid placeholder it needs. A repository that
     * refuses (locked keystore, deleted payload) yields null rather than propagating: the configuration still has to
     * be built, and the plugin's own lookup finds nothing until storage answers again.
     */
    private fun selectedProfile(pluginId: String): ModelProfile? = resolveTurnProfile(
        selection = { (profiles as? AndroidProfileRepository)?.selections()?.get(pluginId) },
        load = profiles::get,
        fallback = {
            if (pluginId == smartWiring?.plugin?.descriptor?.pluginId) smartWiring?.selectedProfile() else null
        },
    )

    /** The diagnostic text plugin's own configuration shape; it refuses every key that is not one of these. */
    private fun diagnosticValues(profile: ModelProfile?): Map<String, String> = mapOf(
        "profileId" to (profile?.id ?: "__unselected__"),
        "profileRevision" to (profile?.revision ?: 0).toString(),
        "requestConsent" to "false",
        "inputMode" to "transcript")

    @Synchronized fun start(pluginId: String) {
        if (closed) return
        pendingStart = null
        mutableState.update { it.copy(selectedPluginId = pluginId, errorCode = null) }
        // 0.25 Task 14 (R74): the assistant is TEXT+IMAGE. A profile that does not declare IMAGE refuses the session
        // **before** anything is started, with a UI-visible code; it is never silently downgraded to a text-only run.
        val startupError = profileStartupError(selectedProfile(pluginId))
        if (startupError != null) {
            mutableState.update { it.copy(errorCode = startupError) }
            return
        }
        val session = SessionId(UUID.randomUUID().toString())
        host.startSession(session)
        activeSessionId = session.value
        activatedPlugin = null
        // W1 (F17/F18), D53-③: **开始 ＝ 确保连接（必要时重连）**. Establishing the link used to hang off one UI
        // combination's composition, so a cold start could reach `BuildingScene` and never even *attempt* a CXR
        // connection - and the ears, gated on `linkReady`, then never armed. This only adjusts **when we call** the
        // vendor transport; the call shape is unchanged (official demo).
        ensureCxrLink()
        // A new session re-arms the clock exchange (`sessionEstablished` drops the old mapping), so the next live
        // mapping is a fresh one and must be retried against even if it somehow carried the previous identifier.
        retriedMappingSyncId = null
        // A connection that became ready **before** the session existed must still establish the hint link: the
        // transport reports readiness once, and dropping that signal on the floor would leave the glasses without a
        // `HELLO` for the whole session, so every hint would be refused as an unknown session.
        maybeMarkSceneReady()
        if (environment == null) { host.pause(); error("CAPABILITY_MISSING") }
        // P3.3: bring the privileged channel up **before** probing, so the probe line describes the state this session
        // is starting in rather than the state it inherited. A channel that is down is reported here *and* is absent
        // from the advertised tool set (the router filters on the same question).
        runCatching { executionChannelBringUp?.invoke() }
        channelProbes.forEach { (id, up) ->
            P3Log.line("P3_EXEC channel=$id available=${runCatching { up() }.getOrDefault(false)}")
        }
        // The root channel is asked **once**: that one answer is both the fourth probe line and the panel's Root row, so
        // a `su` waiting on a permission prompt costs this session start one timeout instead of two (P3.3
        // leftover-sweep review, Important 1). Nothing is logged or published when no root probe is wired, so a
        // composition built without one keeps producing exactly the lines and rows it did before.
        val rootStatus = rootStatus()
        if (rootStatusProbe != null) {
            P3Log.line("P3_EXEC channel=$ROOT_CHANNEL available=${rootStatus == RootStatus.AVAILABLE}")
            mutableState.update { it.copy(executionChannels = executionChannels(rootStatus)) }
        }
    }

    /**
     * P3.3 (§17.4): the **notification's** answer, which names the question it answers.
     *
     * The card can answer "whatever is up", because it is drawn from that same state. A notification cannot: it outlives
     * the moment it was posted for (it sits in the shade, and a tap can come late), so a tap naming a different question
     * settles nothing. The alternative - answering whatever happens to be pending now - would answer a question the user
     * never saw.
     */
    @Synchronized fun answerConfirmation(requestId: String, approved: Boolean): Boolean {
        if (state.value.pendingConfirmation?.requestId != requestId) {
            P3Log.line(
                "P3_CONFIRM_HOST answer=${if (approved) "allow" else "deny"} request=$requestId " +
                    "settled=false reason=STALE",
            )
            return false
        }
        return answerConfirmation(approved)
    }

    /**
     * P3.3 (spec §17.4): the session page's answer to the question that is up.
     *
     * `false` when nothing is pending - a tap that arrives after the question was withdrawn answers nothing, rather than
     * being delivered under a request id no gate is waiting on.
     */
    @Synchronized fun answerConfirmation(approved: Boolean): Boolean {
        val pending = state.value.pendingConfirmation ?: return false
        val presenter = confirmationPresenter ?: run {
            P3Log.line("P3_CONFIRM_HOST answer=none request=${pending.requestId} reason=NO_PRESENTER")
            return false
        }
        val answered = presenter.answer(pending.requestId, approved)
        if (answered) mutableState.update { it.copy(pendingConfirmation = null) }
        // The host half of §17.4's hard criterion, and the only line that says whether a tap actually settled the
        // question the plugin is blocked on: `settled=false` means the card was answered but nothing was waiting for
        // that id, which is indistinguishable from a dropped tap without this line. It carries the request id (a tool
        // call id) and never the summary or the action's arguments.
        P3Log.line(
            "P3_CONFIRM_HOST answer=${if (approved) "allow" else "deny"} request=${pending.requestId} " +
                "settled=$answered",
        )
        return answered
    }

    /** P3.3: puts the question the host was asked to present on the session page - and, when nobody can see that page,
     * runs the B5 channel ladder (glasses -> notification -> Edith page).
     *
     * 0.3 Phase 4B (B7): returns the rung that actually took the question (or `null` for none), so the presenter's
     * count-only `P4_CONFIRM` line names the real channel instead of guessing between notification and dialog. */
    internal fun showConfirmation(requestId: String, summary: String): ConfirmationChannelKind? {
        mutableState.update {
            it.copy(
                pendingConfirmation = PendingConfirmation(
                    requestId,
                    summary,
                    // The countdown the UI ticks: the same window the plugin's gate enforces, on the wall clock the
                    // phone can read (owner directive 2026-10-10).
                    System.currentTimeMillis() + ConfirmationNotifier.WINDOW_MILLIS,
                ),
            )
        }
        return deliverToPhone(requestId, summary)
    }

    /**
     * 0.3 Phase 4B (B5, spec §5.4/D191): the phone-side rung of the channel ladder.
     *
     * The order is the owner's: glasses (already announced by the presenter; read live because a refused SHOW must not
     * suppress a fallback) -> notification action -> the Edith page as the **last** fallback. Voice is deliberately not
     * here: it only settles a question that has already been presented, so it can never make one visible. Each rung is
     * attempted only when its surface is live, and the first success stops the ladder - the old code posted the
     * notification **and** raised the page on every background question, which is the "duplicate surfaces" the spec
     * rules out.
     */
    private fun deliverToPhone(requestId: String, summary: String): ConfirmationChannelKind? {
        if (uiIsVisible) return ConfirmationChannelKind.DIALOG
        val channel = confirmationPresentationChannel(
            glassesServing = confirmationPresenter?.isServingOnGlasses(requestId) == true,
            notificationAvailable = notificationsCanPost(),
            dialogAvailable = canRaiseConfirmationPage(),
        )
        return when (channel) {
            ConfirmationChannelKind.GLASSES -> ConfirmationChannelKind.GLASSES  // the glasses already carry it
            ConfirmationChannelKind.NOTIFICATION -> {
                if (notifier()?.post(requestId, summary) == true) return ConfirmationChannelKind.NOTIFICATION
                // A shade that became unavailable between the probe and the post degrades, it does not drop the
                // question: the page is the last fallback.
                raiseConfirmationPage()
                ConfirmationChannelKind.DIALOG
            }
            // The notification was skipped because it cannot post; record the stable reason before degrading (§5.4:
            // a missing channel must say why, never silently become "Edith only").
            ConfirmationChannelKind.DIALOG -> {
                notificationSurface?.let { notifier()?.reportUnavailable(requestId) }
                raiseConfirmationPage()
                ConfirmationChannelKind.DIALOG
            }
            ConfirmationChannelKind.VOICE, null -> null
        }
    }

    /** Raises the session page over another App; a refused raise is a degradation, never a failed question. */
    private fun raiseConfirmationPage() {
        runCatching { raiseConfirmationUi?.invoke() }
    }

    /** Whether the page can be raised right now, from the side-effect-free probe (never by raising it to find out). */
    private fun canRaiseConfirmationPage(): Boolean =
        canRaiseConfirmationUi?.let { runCatching(it).getOrDefault(false) } ?: false

    /** Whether the shade can post the question right now; a throwing probe is unavailable. */
    private fun notificationsCanPost(): Boolean =
        notificationSurface?.let { runCatching { it.available() }.getOrDefault(false) } ?: false

    /**
     * 0.3 Phase 4B (B5, spec §5.4): whether the **phone** can put a confirmation in front of the wearer right now.
     *
     * The B4 answer was "this composition exists", true for the whole session - so the presenter never reported
     * unavailable and AgentLoop's early `CONFIRMATION_UNAVAILABLE` refusal was unreachable in production. This is the
     * live three-way question ([ConfirmationPhoneSurface]): page visible, page raisable, or shade postable.
     */
    internal fun canPresentOnPhone(): Boolean = ConfirmationPhoneSurface.available(
        pageVisible = uiIsVisible,
        canRaisePage = canRaiseConfirmationPage(),
        notificationsAvailable = notificationsCanPost(),
    )

    /** P3.3: takes a question off the session page - on an answer, a timeout, 急停 or session teardown - and out of
     * the shade with it. */
    internal fun hideConfirmation(requestId: String) {
        if (mutableState.value.pendingConfirmation?.requestId == requestId) {
            mutableState.update { it.copy(pendingConfirmation = null) }
            // Only a hide that **actually cleared this question** may take the notification down. The shade holds one
            // notification for every question, so a late hide(c1) arriving after c2 went up would otherwise remove the
            // live question's only surface - and the user, being away from the page, would never see c2 at all
            // (review of this round, Important 1).
            notifier()?.cancel(requestId)
        }
    }

    /**
     * P3.3 (§17.4): whether the session page is on screen.
     *
     * `MainActivity` reports **visibility** (`onStart`/`onStop`), not focus (`onResume`/`onPause`): a Shizuku grant
     * dialog or the MIUI install prompt pauses the Activity without hiding it, and posting "answer this on your phone"
     * while the user is looking at the question on the phone is exactly the noise this trigger exists to avoid.
     */
    @Synchronized fun uiVisible(visible: Boolean) {
        uiIsVisible = visible
        val pending = state.value.pendingConfirmation ?: return
        // 0.3 Phase 4B (B5): the same ladder as `showConfirmation` - coming back to the page takes the duplicate out of
        // the shade, leaving again chooses the highest available channel rather than always firing both.
        if (visible) notifier()?.cancel(pending.requestId) else deliverToPhone(pending.requestId, pending.summary)
    }

    /** P3.3: 急停 - withdraws every open question and cancels what is in flight. */
    @Synchronized fun emergencyStop() {
        confirmationPresenter?.withdrawAll()
        notifier()?.cancelAll()
        mutableState.update { it.copy(pendingConfirmation = null) }
        stop()
    }
    @Synchronized fun onSceneReady() {
        if (closed) return
        // Remembered rather than only acted on: readiness can arrive before a session is started, and the signal is
        // delivered exactly once by the transport.
        linkReady = true
        // The page needs the same fact to word itself honestly (F4). It is a property of this process rather than a
        // live heartbeat, so it is only ever set here and never re-read.
        mutableState.update { it.copy(cxrLinkReady = true) }
        maybeMarkSceneReady()
    }

    /** True once the transport reported a live session; kept so a later session start can still establish the link. */
    @Volatile private var linkReady = false
    private var activeSessionId: String? = null

    /**
     * The audio epoch this composition started, or null while the ears are not armed.
     *
     * Kept so `stop`/`pause` stop the epoch that is actually running: `AudioIngressLoop.stop` matches on the epoch
     * value, so a freshly built epoch would stop nothing and the poll loop would outlive its session.
     */
    @Volatile private var runningAudioEpoch: InputEpoch? = null

    /**
     * The transport port [armAudioEpoch] started alongside the loop, so `disarm` stops the same port even if the
     * active connection has been replaced meanwhile (Task 15c D1).
     */
    private var runningAudioTransport: InputPort? = null
    private var inputSerial = 0L

    /** The epoch currently polling the ingress, or null. Read by the App's diagnostics and by tests. */
    fun currentAudioEpoch(): InputEpoch? = runningAudioEpoch

    /**
     * Starts the single audio epoch the passive loop listens on (Task 14a item 4).
     *
     * The smart plugin does **not** declare `Capability.Audio` - advertising that would make the host start the
     * microphone path for any audio-requiring plugin, which is not what the wake word needs - so the host will never
     * start `environment.input` for it. The composition therefore arms the epoch itself, and only while a plugin is
     * actually active: a second call is a no-op, because `AudioIngressLoop` would double-poll every chunk if a second
     * loop were created.
     *
     * **Both ports, one epoch (Task 15c D1).** The chain's loop only *polls* the ingress; opening it and asking the
     * glasses for the stream happens solely inside `RokidSessionTransport.start` (`ingress.open` + `startAudioStream`).
     * Arming only the loop therefore left the ingress unopened for the whole of a device run - `PcmIngress.format`
     * stayed null (so any datagram would be refused `UNKNOWN_FORMAT`) and the glasses, never asked, logged
     * `audio data 0 bytes`. The transport's own [InputPort] is started first, so the loop built after it polls an open
     * ingress; both are handed the **same** epoch. With no CXR connection there is no port to open the ingress, so
     * nothing is started at all here and the display-live retry re-enters this method once the connection exists.
     */
    @Synchronized fun armAudioEpoch() {
        if (closed || activatedPlugin == null) {
            P3Log.line("P3_AUDIO state=disarmed generation=${host.currentGeneration.value} " +
                "transport=false loop=false reason=NOT_ACTIVE")
            return
        }
        // A second arm of a still-running epoch changes nothing, so it is not an observable event and gets no line.
        if (runningAudioEpoch != null) return
        val session = activeSessionId ?: run {
            P3Log.line("P3_AUDIO state=disarmed generation=${host.currentGeneration.value} " +
                "transport=false loop=false reason=SESSION_MISSING")
            return
        }
        val transport = activeConnection?.inputPortOrNull()
        if (transport == null) {
            // Nothing has opened (or will open) the ingress, so a loop started here would poll a closed one. Left
            // unarmed on purpose rather than half-armed: the refusal path that precedes a connection leaves the
            // activation retryable, and that retry runs this method again with the port present.
            android.util.Log.i("EdithHost", "AUDIO_EPOCH_TRANSPORT_MISSING: no CXR connection, the audio epoch " +
                "stays disarmed (session=$session generation=${host.currentGeneration} state=${host.currentState})")
            P3Log.line("P3_AUDIO state=disarmed generation=${host.currentGeneration.value} " +
                "transport=false loop=false reason=TRANSPORT_MISSING")
            return
        }
        // The generation is read **here**, at the moment the epoch is built, and is never passed in by the caller.
        // `activateSelected` resolves its own copy before `HostRuntime.activate`/`switchTo` runs, and `switchTo`
        // increments the generation internally, so an epoch armed from that copy would name a generation the host has
        // already replaced - every frame it carries would build an envelope the host's identity gate silently drops
        // (Task 14a-fix2). This method runs under the composition lock, which every activation path holds, so the
        // read cannot observe a generation belonging to an activation other than the one that just succeeded.
        val epoch = InputEpoch(SessionId(session), host.currentGeneration, Math.incrementExact(inputSerial).also { inputSerial = it })
        runningAudioEpoch = epoch
        runningAudioTransport = transport
        // Either both halves run or neither does: a transport that opened the ingress with nothing polling it leaks a
        // live stream, and a loop over an ingress the transport refused to open is the defect this fix removes.
        val failure = runCatching {
            transport.start(epoch)
            environment?.input?.start(epoch)
        }.exceptionOrNull()
        if (failure != null) {
            runCatching { environment?.input?.stop(epoch) }
            runCatching { transport.stop(epoch) }
            // Nothing is polling, so the composition must not claim the ears are armed.
            runningAudioEpoch = null
            runningAudioTransport = null
            android.util.Log.i("EdithHost", "audio epoch could not start: ${failure.javaClass.name}: ${failure.message}")
            P3Log.line("P3_AUDIO state=disarmed generation=${epoch.generation.value} " +
                "transport=false loop=false reason=START_FAILED")
        } else {
            // Both ports are up and polling the same ingress under the same epoch (Task 15c D1), which is what the
            // two booleans let a device run check.
            P3Log.line("P3_AUDIO state=armed generation=${epoch.generation.value} transport=true loop=true")
        }
    }

    /** Stops the epoch [armAudioEpoch] started, if any. Idempotent, and safe before anything was armed. */
    @Synchronized fun disarmAudioEpoch() {
        val epoch = runningAudioEpoch ?: return
        runningAudioEpoch = null
        val transport = runningAudioTransport
        runningAudioTransport = null
        // The reverse of the arm order: stop the poll loop first, so it cannot poll an ingress the transport is about
        // to close, then the transport, which closes the ingress and stops the glasses' stream (Task 15c D1).
        runCatching { environment?.input?.stop(epoch) }
        runCatching { transport?.stop(epoch) }
        P3Log.line("P3_AUDIO state=disarmed generation=${epoch.generation.value} " +
            "transport=false loop=false reason=DISARMED")
    }

    /**
     * The spec's explicit, non-wake entry point: raise `UserAction.Help` at the host's current identity (Task 15c D2).
     *
     * The plugin's help path already existed (`SmartAssistantPlugin.handleHelp`, which runs the most recent final
     * transcript of **this** session) but nothing in production raised the action - only tests did - so the spec's
     * non-wake path could not be exercised on a device. The identity is read from the host at call time, exactly like
     * the wake emitter's: `HostRuntime.onEvent` accepts nothing else. A request that arrives after a pause, a stop or
     * a session switch is therefore dropped by the host rather than delivered under a stale generation, and a
     * composition with nothing active answers false instead of throwing.
     *
     * @return true when the host accepted the event for delivery to the active plugin; false when there is no live
     *   session to help (or this composition is closed).
     */
    @Synchronized fun requestHelp(): Boolean {
        if (closed) return false
        // A host closed underneath this composition has no identity to name; "nothing to help with" is a state, not
        // an error, so it is answered rather than thrown.
        val identity = runCatching { host.currentIdentity() }.getOrNull() ?: return false
        val now = clock.nowNanos()
        return host.onEvent(UserAction(
            EventEnvelope(identity.first, identity.second, "help-${UUID.randomUUID()}", now, now),
            UserAction.Action.Help,
        ))
    }

    /**
     * 0.24 S2 Task 11: the link the production assembly owns, for composition-level tests.
     *
     * Exposed `internal` only so [com.sheen.rokid.phone.bridge.CxrLinkWiringTest] can prove the display's wire is this
     * very object rather than the silent fallback. No production code reads it.
     */
    internal val hintLinkForProbe: CxrHintLink? get() = hintLink

    /**
     * The panel's first layout, delivered on a host thread (Task 11 exposes it; Task 12 owns the 10 s timer).
     *
     * A named seam rather than a plugin event: the timer belongs to the conversation state machine, and the
     * composition's own `init` subscribes it here ([SmartAssistantPlugin.onPanelShown]). It is deliberately not a wire
     * command (the glasses never send `PANEL_SHOWN` to the plugin) and not a `HostEvent` the plugin capability gate
     * would filter. The [HintIdentity] is the pair the link validated, so the timer owner can reject a queued event
     * that belongs to a session that has moved on.
     */
    internal var onPanelShown: ((identity: HintIdentity, route: AssistantRoute) -> Unit)? = null

    /**
     * The panel went hidden (`UI_CLOSED`), delivered on a host thread for the turn owner (0.24 spec §5.4).
     *
     * The conversation cycle needs it to cancel a listening window whose panel closed; like [onPanelShown], this is a
     * named seam rather than a plugin event, the event has already been checked against the host's current identity,
     * and the composition's own `init` subscribes the smart plugin to it.
     */
    internal var onUiClosed: ((route: AssistantRoute) -> Unit)? = null

    init {
        // 0.24 S3 fix (C1): a composition that owns the smart wiring **is** the production consumer of the two panel
        // lifecycle seams. They used to be assigned only by tests, so a real wake waited for a `PANEL_SHOWN` that
        // nobody delivered (no STATE 1, no 10 s) and a silent wake stuck in AwaitingTranscript. Wiring them in the
        // composition (rather than one `create` call site) is what keeps the tested root and the shipped root the same
        // object: `PassiveLoopWiringTest` builds this class directly.
        smartWiring?.plugin?.let { plugin ->
            onPanelShown = { _, route -> plugin.onPanelShown(route) }
            onUiClosed = { route -> plugin.onPanelClosed(route) }
        }
    }

    /**
     * Whether the resident panel is visible right now (Task 12's already-visible turn-open path, spec §5.3).
     *
     * A turn that opens while the panel is already shown must start its 10 s window immediately rather than wait for
     * a `PANEL_SHOWN` that will not be sent again. Read-only: the link owns the state.
     */
    internal fun isPanelVisible(): Boolean = hintLink?.isPanelVisible() ?: false

    /**
     * The last wear-sensor value the glasses reported, or `null` before any wearing event (0.3 Phase 2, Task 2).
     *
     * A tri-state on purpose: the host must not turn "no event yet" into "not wearing". Set by [onWearingChanged] and
     * cleared when the link is lost or the session ends, because the fact belongs to the session that reported it.
     * Read-only from the outside; written only on the host-event path.
     */
    @Volatile
    private var wearingState: Boolean? = null

    /** The device-state provider's wearing fact; `null` until the glasses report one. */
    internal fun glassesWearing(): Boolean? = wearingState

    /**
     * True when [identity] is the pair [HostRuntime] currently accepts events for.
     *
     * The link validated an inbound event against the identity it introduced; a queued event can outlive that
     * session. Checking the pair again here is what keeps a stale `PANEL_SHOWN`/`UI_CLOSED` from touching the new
     * conversation.
     */
    private fun identityIsCurrent(identity: HintIdentity): Boolean {
        val current = runCatching { host.currentIdentity() }.getOrNull() ?: return false
        return current.first.value == identity.sessionId && current.second.value == identity.generation
    }

    /**
     * Receives `PIN` from the link, off the vendor callback thread, as the official `PinChanged` host event.
     *
     * The link has already validated the session/generation against the identity it introduced, and the event carries
     * that **validated** pair. The envelope is built from it rather than from `currentIdentity()` at delivery time:
     * this is the opposite of the earlier comment here, and the difference matters - a host that has moved on refuses
     * the old pair, while a delivery-time stamp would have relabelled the old pin as the new session's pin.
     */
    internal fun onPinFromLink(identity: HintIdentity, route: AssistantRoute) {
        dispatchHostEvent { deliverPin(identity, route) }
    }

    private fun deliverPin(identity: HintIdentity, route: AssistantRoute) {
        if (closed) return
        val now = clock.nowNanos()
        val accepted = runCatching {
            host.onEvent(PinChanged(
                EventEnvelope(
                    SessionId(identity.sessionId), Generation(identity.generation),
                    "pin-${UUID.randomUUID()}", now, now,
                ),
                route,
            ))
        }.getOrDefault(false)
        P3Log.line("P3_CXR pin route=${route.name} session=${identity.sessionId} " +
            "generation=${identity.generation} accepted=$accepted")
    }

    /**
     * Receives `PANEL_SHOWN` from the link and hands it to [onPanelShown] on a host thread.
     *
     * The validated identity is checked against the host's current one at delivery, so a queued event from a previous
     * session cannot start the new conversation's timer. 0.3 Phase 4B (B4, spec §5.4): a live confirmation question is
     * announced to the newly laid-out panel here as well - the panel may have been created *after* `present` sent its
     * first `SHOW`/`CONFIRM_STATE`, and that repeat must not count as settling the question.
     */
    internal fun panelShownArrived(identity: HintIdentity, route: AssistantRoute) {
        dispatchHostEvent {
            if (closed) return@dispatchHostEvent
            if (!identityIsCurrent(identity)) {
                P3Log.line("P3_PANEL shown route=${route.name} session=${identity.sessionId} result=stale")
                return@dispatchHostEvent
            }
            P3Log.line("P3_PANEL shown route=${route.name}")
            // Re-announce before the turn owner starts its window: the question is on the panel by the time the
            // wearer's listening/answer window opens.
            confirmationPresenter?.panelShownArrived()
            onPanelShown?.invoke(identity, route)
        }
    }

    /**
     * Resident-service state from the link, recorded where a device log can separate the two UI states.
     *
     * `UI_CLOSED` also reaches [onUiClosed] after the same identity check: a queued close from a previous session
     * must not cancel the new session's listening window. `UI_READY` is informational here; the link itself owns the
     * readiness decision, and only a live clock mapping counts as liveness.
     */
    internal fun onUiStateFromLink(identity: HintIdentity, state: UiState) {
        dispatchHostEvent {
            if (closed) return@dispatchHostEvent
            if (!identityIsCurrent(identity)) {
                P3Log.line("P3_UI_STATE stale session=${identity.sessionId} generation=${identity.generation}")
                return@dispatchHostEvent
            }
            when (state) {
                UiState.Ready -> P3Log.line("P3_UI_STATE ready")
                is UiState.Closed -> {
                    P3Log.line("P3_UI_STATE closed route=${state.route.name}")
                    onUiClosed?.invoke(state.route)
                }
            }
        }
    }

    /**
     * 0.25 Task 11 (R51): one ENTER/BACK decision from the glasses.
     *
     * The link validated the pair against the identity it introduced; this checks it again against the host's current
     * one at delivery time, exactly like [panelShownArrived], so a press queued from a previous session answers
     * nothing. The presenter is the authority on whether that question is still live: its [ConfirmationPresenter]
     * answer path removes one pending entry and delivers one `ConfirmationResult`, which is also what makes a phone
     * tap and a glasses press settle the question at most once in total.
     */
    internal fun onConfirmationDecisionFromLink(identity: HintIdentity, requestId: String, approved: Boolean) {
        dispatchHostEvent {
            if (closed) return@dispatchHostEvent
            if (requestId.isBlank()) {
                P3Log.line("P3_CONFIRM_HOST source=glasses result=ignored reason=MISSING_REQUEST")
                return@dispatchHostEvent
            }
            if (!identityIsCurrent(identity)) {
                P3Log.line(
                    "P3_CONFIRM_HOST source=glasses result=stale session=${identity.sessionId} " +
                        "request=$requestId",
                )
                return@dispatchHostEvent
            }
            val settled = confirmationPresenter?.answerFromGlasses(requestId, approved) ?: false
            // Same shape as the phone's line: the request id (a UUID) and the result, never the summary.
            P3Log.line(
                "P3_CONFIRM_HOST answer=${if (approved) "allow" else "deny"} request=$requestId " +
                    "settled=$settled source=glasses",
            )
        }
    }

    /**
     * One diagnostic `IGlassesEventListener` fact, recorded off the vendor thread.
     *
     * 0.24 S5 Task 20: the screen-on/off and app-paused/resumed callbacks are **diagnostics only** - they are recorded
     * where a device log can separate them, and deliberately do not change any state. Wake-on-screen-off is the
     * glasses overlay's platform wake lock (`GlassesOverlayService`), not a phone-side reaction. Wear-off is the one
     * scene event with behaviour; it goes through [onWearingChanged].
     */
    internal fun onGlassesSceneEvent(name: String) {
        dispatchHostEvent {
            if (closed) return@dispatchHostEvent
            P3Log.line("P3_CXR glasses_event=$name")
        }
    }

    /**
     * 0.24 S5 Task 20: the wearer took the glasses off (spec §5.7).
     *
     * The vendor reports it as `IGlassesEventListener.onWearingStatusChanged(false)`; there is no wire command for the
     * sensor fact itself. The EDITH behaviour is the same two facts the spec names: cancel the current turn (the same
     * `UserAction.Stop` a glasses double-click raises, so the active plugin settles its timers and output) and tell the
     * resident panel to close (the official ALL form, because the wearer does not tell us which route is visible).
     * Putting the glasses back on changes nothing.
     *
     * Runs through [dispatchHostEvent] like every other vendor callback: host policy must not execute on the SDK's
     * callback thread.
     */
    internal fun onWearingChanged(wearing: Boolean) {
        dispatchHostEvent {
            if (closed) return@dispatchHostEvent
            // 0.3 Phase 2 Task 2: the wear sensor fact is recorded for the device state. `true` and `false` are both
            // facts; a session that has reported none stays `null` until its first event.
            wearingState = wearing
            P3Log.line("P3_CXR glasses_event=wearing=$wearing")
            if (wearing) return@dispatchHostEvent
            val identity = runCatching { host.currentIdentity() }.getOrNull()
            val cancelled = if (identity == null) false else {
                val now = clock.nowNanos()
                host.onEvent(UserAction(
                    EventEnvelope(identity.first, identity.second, "wear-off-${UUID.randomUUID()}", now, now),
                    UserAction.Action.Stop,
                ))
            }
            // Send the close even when no plugin turn was active: the panel may still be visible with a closed turn.
            val closed = hintLink?.closeAll() ?: false
            P3Log.line("P3_CXR wear=off cancel=${if (cancelled) "accepted" else "none"} close=$closed")
        }
    }

    /**
     * 0.24 S5 Task 20: the CXR session died underneath this composition.
     *
     * `HostRuntime.interrupt()` is reached from the transport's `onInterrupted` (`onSessionClosed`, session-terminating
     * or a failed audio stream), so this observer is the one place the composition learns that the link is gone. A
     * closed link's `establishedSession`/`UI_READY`/`panelVisible`/clock mapping must not survive it: while the mapping
     * remains valid, the next wake would take the "already ready" path and send `PANEL_OPEN` into a dead scene.
     * `linkLost()` clears all of them. `linkReady` is cleared as well, so a following 开始 waits for the transport's own
     * live-session signal again instead of trusting the dead link's readiness; the first wake on the next live session
     * then takes the `appStart` recovery path (spec §5.7). The dead transport is marked unbuilt so that 开始 really asks
     * it to connect again (`D53`③: 开始 = 确保连接，必要时重连); the transport is idempotent for an already-live session.
     */
    private fun onCxrLinkInterrupted() {
        if (closed) return
        hintLink?.linkLost()
        // 0.3 Phase 2 Task 2: a dead link's last wearing fact must not outlive the session it described.
        wearingState = null
        linkReady = false
        linkBuilt = false
        retriedMappingSyncId = null
        mutableState.update { it.copy(cxrLinkReady = false) }
        P3Log.line("P3_CXR disconnect link=lost panel_reset=true recovery=appStart")
    }

    /**
     * 0.24 S2 Task 11: the wake/turn-open panel request.
     *
     * Exposed on the composition because the wake detector owns the moment, while the link owns the resident
     * handshake. Returns whether the request was accepted for delivery (the panel may open asynchronously after an
     * `appStart`); the 10 s timer still waits for [onPanelShown].
     */
    internal fun requestPanelOpen(route: AssistantRoute): Boolean = hintLink?.requestPanelOpen(route) ?: false

    /**
     * One wake detector emission, as `create` delivers it (0.24 S5 final review Important #1).
     *
     * The chime, the panel request and the host event used to be three statements in the detector's lambda. The panel
     * request is now a decision of this composition - it has both halves the decision needs, the host's live identity
     * and the smart plugin's own scene admission - so it lives here rather than in a lambda a test cannot drive.
     *
     * @param chime the wearer-facing acknowledgement, played only for a wake that has a live identity.
     */
    internal fun emitWake(host: HostRuntime, word: String, confidence: Double, chime: () -> Unit = {}) {
        val identity = runCatching { host.currentIdentity() }.getOrNull()
        if (identity != null) {
            chime()
            // 0.24 S5 final review Important #1: a live identity is necessary but not sufficient. The plugin can still
            // refuse the wake (scene cooldown), and no turn is then installed, so the panel would have nothing to
            // close it. Ask the plugin's own admission before asking for the window.
            if (pluginAdmitsWake()) requestPanelOpen(AssistantRoute.WAKE)
        }
        emitWakeDetected(host, clock, word, confidence)
    }

    /**
     * The plugin half of the wake-panel decision (0.24 S5 final review Important #1).
     *
     * A composition built without the smart wiring (a harness) has no scene policy to ask and keeps the identity-only
     * behaviour; production always has it. False means the wake will be refused, so no panel may be requested for it.
     */
    private fun pluginAdmitsWake(): Boolean = smartWiring?.plugin?.canOpenWake() ?: true

    /**
     * 0.23 P5 (spec §5.3.2, U11): the wearer's double-click on the glasses, raised as `UserAction.Stop`.
     *
     * The caller passes the session/generation the STOP was validated against (not just "a stop happened"), and the
     * pair is compared with the host's identity **again** here. A stop/start between the link's validation and this
     * method would otherwise let an old STOP cancel the new session's work; a mismatch is answered `stale` and the
     * event is not delivered.
     *
     * @return true when the host accepted the event for the active plugin; false for a stale or absent identity.
     */
    @Synchronized fun requestStop(sessionId: String, generation: Long): Boolean {
        if (closed) return false
        val identity = runCatching { host.currentIdentity() }.getOrNull() ?: run {
            P3Log.line("P3_CXR stop result=no_session")
            return false
        }
        if (!stopSessionMatches(identity, sessionId)) {
            P3Log.line("P3_CXR stop result=stale")
            return false
        }
        val now = clock.nowNanos()
        val accepted = host.onEvent(UserAction(
            EventEnvelope(identity.first, identity.second, "stop-${UUID.randomUUID()}", now, now),
            UserAction.Action.Stop,
        ))
        P3Log.line("P3_CXR stop result=${if (accepted) "accepted" else "refused"}")
        return accepted
    }

    /**
     * Forwards one receiver offer outcome to the chain (Task 14a item 5).
     *
     * `PcmIngress.offer`'s result used to be discarded inside the transport, so `INGRESS_OVERFLOW` and
     * `UNKNOWN_FORMAT` could never be reported. Arrives on the transport's worker thread; the chain makes the
     * "is there a loop yet" decision under its own lock, so a fault that races the first `start` is buffered, not lost.
     */
    internal fun onPcmOffer(offer: PcmOffer) {
        audioChain?.onOffer(offer)
    }

    /** The chain, once provisioned; the offer seam above has no other way to reach it. */
    internal var audioChain: CxrAudioChain? = null

    /**
     * Marks the scene ready once both halves exist - a live transport and a started session - and then establishes
     * the hint link. Order matters: `HELLO` and the clock exchange must precede any hint the plugins can produce.
     */
    @Synchronized private fun maybeMarkSceneReady() {
        if (closed || !linkReady || host.currentState != SessionState.BuildingScene) return
        host.markSceneReady()
        activeSessionId?.let { session -> hintLink?.sessionEstablished(session, host.currentGeneration.value) }
        // This runs on the transport's callback thread, so a failure while activating (unavailable storage, a refused
        // configuration) must become the App's error state rather than an exception thrown into the SDK's thread.
        runCatching { activateSelected() }.onFailure {
            host.pause()
            error("CAPABILITY_MISSING")
        }
    }

    /**
     * The single point that establishes the CXR link, and the one the session start path calls.
     *
     * Split out of [connectCxr] because the session start runs inside `SessionService`, which has no `Activity`: this
     * method takes whatever Activity [attachCxrActivity] last remembered, and the actual `connect` waits for the one
     * thing the vendor SDK needs. Idempotent - a connection already built is reused, never rebuilt.
     *
     * **Building needs the Activity too** (`activity.asCxrActivity()`), so with none remembered yet the attempt is
     * only **said out loud** (`result=no_activity`) and reported false. Passing a placeholder would hand the vendor
     * adapter something it cannot cast - a crash on the device that no JVM test would see.
     *
     * Every call really asks the transport to connect; the transport itself is idempotent for an impatient second
     * tap and renews a session that died (`TransportConnectIdempotencyTest`). That is what lets `开始` mean "make sure
     * it is connected, and reconnect if it is not" without a second button.
     *
     * **Idempotent within one armed link**: both `MainActivity.onCreate` and `start()` reach this method, and asking
     * the SDK to connect twice per session start is exactly the duplicate the transport had to be hardened against.
     * [stop] clears that state, so the next session connects again.
     *
     * @return true when this assembly has a connection to use; false when it has none yet (no transport, or no
     *   Activity to build it with).
     */
    @Synchronized internal fun ensureCxrLink(): Boolean {
        // "This build has no CXR transport" is the more fundamental fact and wins the line: with no assembly, a missing
        // Activity is not the reason nothing can connect, and reporting it would hide the real gap (the very confusion
        // F17/F18 produced).
        if (rokidConnectionFactory == null) {
            mutableState.update { it.copy(cxrAuthorization = null, cxrTransportMissing = true) }
            if (closed) {
                P3Log.line("P3_CXR connect attempt=start result=closed")
                return false
            }
            P3Log.line("P3_CXR connect attempt=start result=transport_missing")
            return false
        }
        // The assembly is real, so any earlier "unassembled" claim is stale (D53-③: this is a fact about the build).
        mutableState.update { it.copy(cxrTransportMissing = false) }
        val activity = pendingActivity?.get() ?: run {
            // A new attempt invalidates the previous answer (review I-2) even when it cannot proceed: a cancelled
            // prompt must not sit on the page next to a link that came up afterwards, and `null` is what the render
            // treats as "the SDK has not asked".
            mutableState.update { it.copy(cxrAuthorization = null) }
            if (closed) {
                P3Log.line("P3_CXR connect attempt=start result=closed")
                return false
            }
            P3Log.line("P3_CXR connect attempt=start result=no_activity")
            return false
        }
        if (linkBuilt && !closed) return true
        return connectCxr(activity)
    }

    /**
     * Explicit CXR entry point. Returns false when this assembly has no glasses transport (the audio
     * capability stays missing) and true when the connection was requested; the transport reports
     * readiness asynchronously through [onSceneReady], never through this call.
     *
     * **When this actually runs (corrected 2026-10-11, review).** It used to be reachable only from a 会话-page
     * button, so the lazy build meant "no CXR worker thread unless the user connects". Since `D53`③ that button is
     * gone: [attachCxrActivity] is called from `MainActivity.onCreate`, so **a connect attempt now happens at App
     * launch**, before 开始 - which is the point (F17/F18: the link may not depend on a UI combination being rendered).
     * The device log in the W1 evidence shows exactly that order (connect `12:26:38`, 开始 `12:26:44`).
     *
     * Every call really asks the transport to connect (the transport itself is idempotent for an impatient second
     * tap and renews a session that died - `TransportConnectIdempotencyTest`), so `开始` can mean "make sure it is
     * connected, and reconnect if it is not" without a second button.
     */
    @Synchronized fun connectCxr(activity: Any): Boolean {
        pendingActivity = java.lang.ref.WeakReference(activity)
        // A new attempt invalidates the previous answer (review I-2): a cancelled prompt must not sit on the page next
        // to a link that came up afterwards, and `null` is what the render treats as "the SDK has not asked".
        mutableState.update { it.copy(cxrAuthorization = null) }
        if (closed) {
            P3Log.line("P3_CXR connect attempt=start result=closed")
            return false
        }
        val factory = rokidConnectionFactory ?: run {
            P3Log.line("P3_CXR connect attempt=start result=transport_missing")
            return false
        }
        val reuse = linkBuilt
        val connection = activeConnection ?: factory(activity).also { built ->
            activeConnection = built
            resources.add(built)
        }
        // Route the shared reply channel into the hint link: acks are matched by sequence on the phone side, and the
        // clock exchange is answered there. The device owns neither the channel nor the routing decision.
        connection.onInboundCommand { json -> hintLink?.onInbound(json) }
        connection.connect(activity)
        linkBuilt = true
        P3Log.line("P3_CXR connect attempt=${if (reuse) "reuse" else "start"} result=ok")
        return true
    }

    /**
     * Takes the Activity the vendor SDK needs, and connects if that has not happened yet.
     *
     * `MainActivity` is the only party that can supply it, and it may appear **after** a session start (the start runs
     * in a Service) or **before** one. Held weakly and deliberately: this composition is a process-lifetime singleton,
     * so a strong Activity reference here would leak it.
     */
    @Synchronized fun attachCxrActivity(activity: Any) {
        pendingActivity = java.lang.ref.WeakReference(activity)
        if (!linkBuilt) ensureCxrLink()
    }

    /** True once the live connection has been asked to connect. Cleared by [stop] (D53-③). */
    private var linkBuilt = false

    /** The Activity the SDK needs, held weakly; null until a UI exists (the start path can run before one does). */
    private var pendingActivity: java.lang.ref.WeakReference<Any>? = null

    /**
     * Routes the AI-App authorization result back into the transport, and records it as **page state** only when the
     * transport says the result was the answer to a prompt it is waiting on.
     *
     * Two owner-reported defects meet here. The connect button used to announce "请在 AI App 中确认授权" on every tap,
     * so the page now reports the SDK's own answer instead (2026-10-10). And that answer used to be read off any
     * activity result at all - `onActivityResult` also carries the App's own permission dialogs (review I-3) - so the
     * page could announce "眼镜授权被取消" for a result the SDK never asked for. The claim comes from the transport,
     * which is the only party that knows whether a prompt is pending.
     *
     * What is written is the **transport's verdict** - derived by the transport from the vendor SDK's parsed
     * `AuthResult`, not from the bare result code (review Minor 6). This class never reads [resultCode] itself: a
     * `RESULT_OK` result the SDK's parser refused would otherwise reach the page as "眼镜授权确认已完成", which is a
     * fact about the activity result rather than about the authorization.
     */
    fun onCxrAuthorizationResult(resultCode: Int, data: Intent?) {
        val verdict = activeConnection?.onAuthorizationResult(resultCode, data) ?: authorizeResult(resultCode, data)
        if (verdict == null) return
        mutableState.update { it.copy(cxrAuthorization = verdict) }
    }

    /** The live CXR transport, for callers that must drive an audio epoch (T17/T18 device bring-up). */
    fun cxrInputPort(): com.sheen.rokid.plugin.InputPort? = activeConnection?.inputPortOrNull()

    /** True only once the live CXR transport reports a session; there is no ready transport without one. */
    fun cxrReady(): Boolean = activeConnection?.ready == true

    /**
     * The uplink receiver for the live connection, so a device test can compare what the glasses sent against what
     * the phone actually accepted.
     *
     * Deliberately narrow: it returns only the counters and the declared format, not the audio. A test needs to know
     * whether frames arrived and whether any were lost; it does not need the samples, and exposing them would make it
     * easy to persist audio that D33 says must stay in RAM.
     */
    fun cxrUplinkReport(): UplinkReport? {
        val receiver = (activeConnection as? RokidConnection)?.uplinkReceiver ?: return null
        val stats = receiver.stats
        return UplinkReport(
            framesAccepted = stats.framesAccepted,
            framesLost = stats.framesLost,
            duplicates = stats.duplicates,
            malformed = stats.malformed,
            gaps = stats.gaps,
            overflowed = stats.overflowed,
            declaredSampleRateHz = receiver.declaredFormat?.sampleRateHz,
            declaredChannelCount = receiver.declaredFormat?.selectedChannels?.size,
            endedWith = receiver.endedWith?.name,
        )
    }

    /**
     * Debug-only bring-up seam for the T17 controlled format check: captures the RAM-only tee the lazy
     * CXR connection passes to the transport. Refuses once a connection exists, because that connection
     * would already hold the previous observer and silently drop the capture.
     */
    internal fun installRawPcmObserver(observer: (ByteArray) -> Unit) {
        check(activeConnection == null) { "CXR_CONNECTION_ALREADY_BUILT" }
        // Both the field and the factory's slot, so a connection built later actually calls this observer. Updating
        // only the field left the transport on the no-op captured at construction.
        armObserver(observer)
    }

    private fun activateSelected() {
        val selected = state.value.selectedPluginId ?: return
        // 0.25 Task 14 (R74): the same startup gate on every activation path (resume included), so a profile edited
        // back to text-only between sessions is refused visibly instead of starting a request the gate would deny.
        val chosenProfile = selectedProfile(selected)
        profileStartupError(chosenProfile)?.let { code ->
            disarmAudioEpoch()
            host.pause()
            error(code)
            return
        }
        // One configuration, built once - settings keys and profile selection together - and applied only to the
        // plugin it belongs to. `HostRuntime.configure` replaces the stored configuration, so a second call carrying
        // just the profile selection silently dropped the smart keys the user configured (Task 14a-fix item 1), and
        // sending the smart keys to a plugin whose key set is different refuses the whole activation (item 2).
        configurationFor(selected, chosenProfile)?.let { configuration ->
            if (!host.configure(selected, configuration)) {
                android.util.Log.i("EdithHost", "configuration refused for $selected")
                // Mirrors the activation-refusal branch below: no refusal may leave the previously active plugin
                // running with the ears armed while the App shows an error.
                disarmAudioEpoch()
                host.pause()
                error("CAPABILITY_MISSING")
                return
            }
        }
        val result = if (activatedPlugin == null) host.activate(selected) else host.switchTo(selected)
        if (result == com.sheen.rokid.host.ActivationResult.Activated) {
            activatedPlugin = selected
            awaitingDisplayForActivation = false
            // Clearing the earlier refusal is part of succeeding: a retry that worked must not leave the App showing
            // the error it was retried from.
            mutableState.update { it.copy(errorCode = null) }
            // The smart plugin does not declare `Capability.Audio`, so the host never starts this epoch: the
            // composition arms it as soon as a plugin that declares the wake word is genuinely active. Gating on the
            // *capability* rather than "something activated" keeps a display-only plugin from putting a poll loop on
            // the CXR ingress - the microphone-start side effect `Audio` is deliberately unadvertised for. The other
            // half matters just as much: a successful switch **away** from the wake-word plugin must stop the epoch,
            // or the ears stay armed while the App says a plugin with no wake word owns the session (item 3).
            //
            // Stop the previous activation's epoch **before** arming, rather than only when the new plugin has no
            // wake word: a switch increments the host generation, so the epoch the previous plugin was polling under
            // can never be the one the pipeline should keep tagging frames with. Leaving it running also made
            // `armAudioEpoch` a no-op (it refuses while an epoch is running) on a switch between two wake-word
            // plugins, which left the ears on a dead generation (Task 14a-fix2). Disarming first also makes the arm
            // read the generation that is current *after* the switch, which is the only one the host accepts.
            disarmAudioEpoch()
            if (declares(selected, Capability.WakeWord)) armAudioEpoch()
        } else {
            disarmAudioEpoch()
            host.pause()
            // Recorded with the real result and the advertised set: the App's label collapses every failure into
            // "capability missing", so without this a device run cannot tell an OwnerBusy from a genuine gap.
            android.util.Log.i("EdithHost", "activation refused result=$result selected=$selected " +
                "available=${environment?.availableCapabilities} state=${host.currentState} " +
                "cause=${host.lastActivationCause}")
            error(if (result == com.sheen.rokid.host.ActivationResult.UnknownPlugin) "UNKNOWN_PLUGIN" else "CAPABILITY_MISSING")
            // A capability refusal is worth one retry when the display becomes real; an unknown plugin is not.
            awaitingDisplayForActivation = result == com.sheen.rokid.host.ActivationResult.Failed
        }
    }

    /**
     * Retries an activation that was refused for missing capabilities, once a clock mapping has made the display real.
     *
     * Reached from both display signals, and deliberately narrow:
     *
     * - **Once per mapping.** The transition callback and [CxrHintLink.onMappingLive] both arrive for a `PONG` that
     *   makes the display live, so the mapping's own `syncId` is remembered and a repeat for the same exchange is
     *   dropped. A later renewal carries a fresh `syncId`, so a capability that appears later is still picked up,
     *   and a capability that stays missing costs exactly one attempt per signal - not a loop.
     * - **Nothing once it is settled.** No attempt while a plugin is already active or this composition is closed,
     *   and the refusal flag is what `stop()` clears, so a stopped session is never resurrected by a re-sync.
     * - **The session is put back where activation is legal.** The refusal path's `host.pause()` leaves the session
     *   paused, and `markSceneReady()` is the same call the start path makes after `startSession` - without a ready
     *   scene, `HostRuntime.activate` refuses with `SessionNotReady` (which is what an `interrupt()` that cleared
     *   the scene would otherwise leave behind). It deliberately starts **no** session: no `startSession`, no new
     *   generation and no second `SessionService` grant, because the live clock mapping, the identity and the
     *   generation the armed epoch must name all belong to the session that is already running.
     */
    @Synchronized private fun retryActivationForDisplay() {
        if (closed || !awaitingDisplayForActivation || activatedPlugin != null) return
        val link = hintLink ?: return
        // A signal is only useful while a mapping is really live; the syncId is also the "same exchange" token.
        val syncId = link.liveSyncId() ?: return
        if (syncId == retriedMappingSyncId) return
        retriedMappingSyncId = syncId
        android.util.Log.i("EdithHost", "display retry: syncId=$syncId state=${host.currentState} " +
            "activated=$activatedPlugin available=${environment?.availableCapabilities}")
        // Refuses (throws) when there is no session at all - the stopped case - which is why the result is checked.
        if (runCatching { host.markSceneReady() }.isFailure) return
        runCatching { activateSelected() }
    }

    @Synchronized fun pause() { if (!closed) { pendingStart = null; disarmAudioEpoch(); host.pause() } }
    @Synchronized fun resume() {
        if (closed || host.currentState != SessionState.Paused) return
        if (environment == null) { error("CAPABILITY_MISSING"); return }
        mutableState.update { it.copy(errorCode = null) }
        activateSelected()
    }
    @Synchronized fun stop() {
        if (closed) return
        pendingStart = null
        // `D53`② (2026-10-11): stopping does **not** touch the 「主动辅助」 switch. It is a persisted global setting -
        // the user configures it once and it holds across sessions, reconnects, App restarts and phone restarts - so
        // there is no reset here and no `deactivate()` to call. (`D53`① removed the upload consent that used to be
        // reset in the same breath; both are pinned by tests.)
        // D15: a stopped session must not keep repeating its opening handshake to the glasses.
        //
        // 0.23 P5 review: `host.stop()` **before** `sessionEnded()`. Deactivation publishes an `idle` for the route it
        // was working on; clearing the link's session first made that statement unsendable and left the glasses
        // spinning until their local 60 s bound. `sessionEnded()` then states both levels idle while it still has an
        // identity to address, so even an already-idle plugin leaves the glasses at rest.
        // Before `host.stop()`: the epoch named by `runningAudioEpoch` is the one the loop is polling under, and
        // after teardown the input port may no longer be able to answer for it.
        disarmAudioEpoch()
        try {
            host.stop()
        } finally {
            hintLink?.sessionEnded()
            // 0.3 Phase 2 Task 2: the wearing fact belongs to the session that reported it; 停止 is that session ending.
            wearingState = null
        }
        activatedPlugin = null
        awaitingDisplayForActivation = false
        retriedMappingSyncId = null
        // D53-③: **停止 收起连接**. The built connection object is kept (the transport renews a session that died, so
        // rebuilding is not needed), but the link is marked armed-again-needed: the next 开始 reconnects. Ownership
        // stays with the composition, so `close()` still releases it exactly once.
        if (linkBuilt) P3Log.line("P3_CXR connect after=stop result=cleared")
        linkBuilt = false
        // P3.3: a question does not outlive the session it was asked for. Withdrawing it here means the next session's
        // tap cannot answer this session's action - and the shade is cleared with it, so a dead question cannot keep
        // offering buttons.
        confirmationPresenter?.withdrawAll()
        notifier()?.cancelAll()
        mutableState.update { it.copy(errorCode = null, pendingConfirmation = null) }
    }
    @Synchronized fun selectPlugin(pluginId: String) {
        if (closed || state.value.selectedPluginId == pluginId) return
        mutableState.update { it.copy(selectedPluginId = pluginId, errorCode = null) }
        when (host.currentState) {
            SessionState.Ready, SessionState.Listening -> activateSelected()
            else -> Unit
        }
    }
    @Synchronized fun authorizeStart(pluginId: String): String {
        check(!closed) { "COMPOSITION_CLOSED" }
        val token = UUID.randomUUID().toString()
        pendingStart = StartGrant(token, pluginId, clock.nowNanos() + TimeUnit.SECONDS.toNanos(5))
        return token
    }
    @Synchronized fun dispatch(action: String?, token: String? = null, redelivered: Boolean = false, permissionGranted: Boolean = true,
        foregroundActive: Boolean = true, beforeStart: () -> Unit = {}): Boolean {
        if (closed) return false
        if (action == "edith.START") {
            val grant = pendingStart
            pendingStart = null
            if (redelivered || grant == null || token != grant.token || clock.nowNanos() >= grant.expires) return false
            if (!permissionGranted) { host.stop(); error("BLUETOOTH_PERMISSION_DENIED"); return false }
            beforeStart()
            start(grant.pluginId)
            return true
        }
        if (redelivered || action == null) return false
        return when (action) {
            "edith.STOP" -> { stop(); true }
            "edith.PAUSE" -> { pause(); true }
            "edith.RESUME" -> {
                if (!foregroundActive) { error("FOREGROUND_UI_REQUIRED"); false }
                else if (!permissionGranted) { pause(); error("BLUETOOTH_PERMISSION_DENIED"); false }
                else { resume(); true }
            }
            else -> false
        }
    }
    internal fun error(code: String) { mutableState.update { it.copy(errorCode = code) } }
    @Synchronized override fun close() {
        if (closed) return
        stop()
        closed = true
        profileConsent(null)
        observation.cancel()
        try { host.close() } finally {
            try { (model as? AutoCloseable)?.close() } finally {
                resources.asReversed().forEach { runCatching { it.close() } }
            }
        }
    }
    companion object {
        /** The debug-only diagnostic text plugin's id; its configuration shape is not the smart plugin's. */
        private const val DIAGNOSTIC_PLUGIN_ID = "p2-diagnostic"

        fun create(context: Context, initialObserver: (ByteArray) -> Unit = {}): HostCompositionRoot {
            // Transport, audio engines and glasses output remain separate device gates.
            val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
            val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "edith-phone-deadlines").apply { isDaemon = true } }
            val scheduler = object : DeadlineScheduler {
                override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                    val future = timer.schedule(task, (deadlineNanos - clock.nowNanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    return object : CancellationHandle { override fun cancel() { future.cancel(false) } }
                }
            }
            val blobs = AndroidEncryptedBlobStore(context)
            val resolver = KeystoreVault(blobs)
            val profiles = AndroidProfileRepository(blobs, resolver)
            val probeGate = ProfileProbeGate(profiles)
            // Task 14a/14b: the one settings store, built here rather than further down because the arming switch
            // below persists through it. A save and the next load therefore read the same blob.
            val smartSettingsStore = SmartSettingsStore(blobs)
            // P3.2 / `D53`②: the arming switch and its gate. One instance for the UI, the chain's acoustic sink and the
            // settings store. It is **persisted** (default on) and loaded once here, at assembly time.
            val proactiveArming = ProactiveArming(smartSettingsStore).also { it.load() }
            // The model request had no production line before the P3.1 wrap-up: the client was built with no
            // interceptor, so a successful request was silent and that hop rested on its effects. This is the one line
            // that closes the gap, and it carries nothing but method, status and duration (`ModelHttpLog`).
            val scriptedWire = ScriptedModelWire()
            val http = OkHttpClient.Builder()
                // Debug-only scripted wire, first so a canned answer still passes through `ModelHttpLog` below and the
                // `P3_HTTP` line keeps telling the truth about what the client saw. Inert until armed.
                .addInterceptor(scriptedWire)
                .addInterceptor(ModelHttpLog(clock::nowNanos)).build()
            // 0.25 Task 13 (R67/R71/R72): the one artifact store of this composition. The kernel writes a screenshot
            // into it; both model adapters read the same instance back through the one `MediaSource` the controlled
            // client holds. App-cache scratch, bounded and LRU — never the repository, never a log line.
            val artifactStore = FileArtifactStore(File(context.cacheDir, "edith-artifacts"))
            val artifactMediaSource = ArtifactMediaSource(artifactStore)
            // 0.25 Task 14 (R76): the fixed synthetic image `testImage` sends. It is registered lazily, on the first
            // image probe, into the same artifact store the screenshots use, so the adapters resolve it through the one
            // `MediaSource` (no inline bytes in a request builder, and the probe cannot silently send a text-only
            // request). Registering it at assembly time would write a file for every composition, even one that never
            // probes an image.
            val model = ControlledModelClient(http, profiles, resolver, CapabilityGate(), ContextBudgeter(), clock, scheduler,
                consentSource(probeGate::requestConsent), mediaSource = artifactMediaSource)
            val controlledTester = ControlledProfileTester(model, profiles, clock, probeImageRef = {
                artifactStore.put(PROBE_IMAGE_PNG, "image/png", PROBE_IMAGE_WIDTH, PROBE_IMAGE_HEIGHT).artifactId
            })
            val tester = object : ProfileTester {
                override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                    return probeGate.test(controlledTester, profileId, listener)
                }
                override fun testImage(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                    return probeGate.testImage(controlledTester, profileId, listener)
                }
            }
            val connection = arrayOfNulls<RokidConnection>(1)
            // T18 display path. Both pieces are built before the host because `HostRuntime` reads the environment at
            // construction, while the connection they depend on is created lazily on first connect - so the sender
            // reads the holder rather than capturing a connection that does not exist yet.
            val hintLink = CxrHintLink(clock, scheduler,
                sendJson = { json -> connection[0]?.sendCommand(json) ?: false },
                diagnostics = { message -> android.util.Log.i("EdithHint", message) },
                // Task 11: the production app-start path. The connection is built lazily, so the link reads it through
                // the holder; the backend still decides whether a CXR client is reachable at all.
                startGlassesApp = { onResult ->
                    connection[0]?.input?.openGlassesApp(onResult) ?: false
                })
            // Plugin background work gets its own pool: the deadline executor must not be delayed by it, **and** a turn
            // blocked on a confirmation question (P3.3) must not stall perception or ACK handling behind it. The factory
            // names each thread apart, because P3.3's ANR was read off a thread dump and one repeated name there hides
            // which thread is blocked (P3.3 review, Minor 4).
            val pluginTasks = Executors.newFixedThreadPool(2, pluginTaskThreadFactory())
            val allowedNamespaces = setOf("smart-assistant", "interview-assistant") +
                (if (BuildConfig.DEBUG) setOf("p2-diagnostic") else emptySet())
            val namespaceFactory: (String) -> StorageHandle = { pluginId ->
                require(pluginId in allowedNamespaces) { "UNKNOWN_PLUGIN_NAMESPACE" }
                AndroidNamespaceStorage(pluginId, blobs)
            }
            // Task 12: the L0/L1 action provider. The catalog resolves a spoken app name through the package manager
            // (the manifest's QUERY_ALL_PACKAGES is what makes other apps visible), the launcher starts the platform
            // intent from this application context, and notes go into the App's own encrypted `smart-assistant`
            // namespace. Nothing here reaches an L2+ capability: no message, no dial, no payment, no deletion.
            // Built eagerly but touching no device API at construction, so a composition without a real Context still
            // assembles (the catalog and the launcher refuse at call time instead).
            val actions = IntentActionExecutor(
                resolver = AppLaunchResolver(PackageManagerAppCatalog(context)),
                launcher = ContextIntentLauncher(context),
                noteStorage = namespaceFactory("smart-assistant"),
                // P3.3 Task 9: the L2 dial action reads the user's own grant. Without it `dial` refuses; the app never
                // falls back to a dialer screen, and it never asks for the permission itself.
                callPermission = CallPermission {
                    context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                },
            )
            // The environment advertises exactly the capabilities it can answer: the composition's own gated model
            // client, the per-plugin namespace, the display once a clock mapping makes it real, and - since Task 11 -
            // the ASR provider over the CXR audio chain when a validated engine bundle is present. `Audio` stays
            // unadvertised even then: there is no `AudioSubscription` in the delivery path (transcripts reach a
            // plugin through `AsrSession`), and the host starts `input` for audio-requiring plugins, so advertising
            // it would turn the CXR ingress into a microphone-start path. Speaker and speech output stay unwired, so
            // the host still refuses plugins that need them (`interview-assistant` needs Speaker - deliberately).
            //
            // The chain is built only from a validated bundle and the verified uplink format. When either is absent
            // the input keeps refusing and Asr stays unadvertised: a provider that cannot transcribe must not be
            // advertised, or a plugin would start and then fail confusingly later.
            //
            // Task 14a: the settings are read **once**, at assembly time, and produce both the plugin's configuration
            // and the wake-word provider's content. The store's failure modes are already defaults, so a missing or
            // corrupt blob leaves a configure-able, arm-able app rather than an un-activatable plugin.
            //
            // Task 14b: this is also the store the settings screen writes through, so a save and the next load read
            // the same blob. The composition's own settings stay the ones read here; the screen's copy says that only
            // a full App restart re-reads them (this root is a process-lifetime singleton), with the single exception
            // of the live vision gate built just below.
            val smartSettings = smartSettingsStore.load(profiles)
            // Phase 2 Task 10: one live gate for the perception provider and the device tool, seeded from the stored
            // switch. A save updates this same instance through `saveSmartSettings`; the old `{ true }` hardcodes on
            // both consumers are gone, so the switch really is the only source of truth.
            val visionGate = VisionGate(smartSettings.visionEnabled)
            // 4B (`D189`): the live 「不拦截」 gate, seeded from the same persisted snapshot. B3 passes its provider to
            // the smart plugin's policy and shell-precheck wrapper, so a save on the settings screen is consulted from
            // the next tool call. Default off, including for a pre-4B blob and for a hand-built composition.
            val noInterceptionGate = NoInterceptionGate(smartSettings.noInterception)
            // Task 14b: the self-audio seam. P3.1 emits no audio at all, so the gate stays open and this wiring
            // changes nothing today; it is what P3.2 hands to the speech output path. See `PlaybackGate`.
            //
            // Task 14b-fix item 1: there is exactly **one** gate - the composition's own `playbackGate` field - and
            // the detector reads it through this holder. `create` provisions the chain before the composition exists,
            // so the holder is how the same instance reaches both the detector and the exposed accessor; a second,
            // local gate here is what made `root.playbackGate.begin()` suppress nothing.
            val rootHolder = arrayOfNulls<HostCompositionRoot>(1)
            val hostHolder = arrayOfNulls<HostRuntime>(1)
            // 0.3 Phase 4B (B5): one monotonic revision source for the confirmation hint. The glasses update a row only
            // for a strictly newer revision of the same hint id, so the PANEL_SHOWN re-announcement must never repeat
            // the first send's number (two sends can share a coarse clock tick).
            val summaryRevision = java.util.concurrent.atomic.AtomicLong(0)
            // 0.24 S2 Task 11: the production `IGlassesEventListener`. The backend registers it on every newly created
            // session **before** `connect` (official CXR-L 1.1.2 order). The listener never runs host policy on the
            // vendor thread. Task 20: screen/app events are diagnostics; wear-off cancels the turn and closes the panel.
            val glassesEvents = object : GlassesEvents {
                override fun appResumed() { rootHolder[0]?.onGlassesSceneEvent("app_resumed") }
                override fun appPaused() { rootHolder[0]?.onGlassesSceneEvent("app_paused") }
                override fun wearingChanged(wearing: Boolean) {
                    rootHolder[0]?.onWearingChanged(wearing)
                }
                override fun screenOn() { rootHolder[0]?.onGlassesSceneEvent("screen_on") }
                override fun screenOff() { rootHolder[0]?.onGlassesSceneEvent("screen_off") }
            }
            // Phase C: the speech player is built further down (it depends on the user's 「语音朗读」 setting). 0.23 P5
            // (D135) replaced the fixed TTS phrase with a short chime, so the wake path no longer needs a holder here:
            // the chime sink is independent of the TTS engine and works even when the engine is missing.
            val wakeChime = WakeChime()
            val wakeChimeSink = ToneWakeChimeSink(context)
            // P3.2 Task 6: the context provider. Stage A exposes the zero-permission time keys; Stage B appends its
            // three permissioned sources to the same list. The listener is the production arming gate, so a disarmed
            // session produces no `ContextChanged` at all - the plugin's only context signal is the acoustic event.
            // The arming state itself is published as a source, because the plugin has to be able to read the switch's
            // **current** answer at request time (local transcription continues while it is off).
            val foregroundApp = ForegroundAppSource.android(context)
            val notificationCategory = NotificationCategorySource(
                connected = NotificationCategoryService::isConnected,
                latest = NotificationCategoryService::latest,
            )
            // 0.3 Phase 2 Task 3: the same listener's in-memory content. Task 7 hands it to the perception provider;
            // it is never logged or persisted, and a missing grant answers an honest `unavailable`.
            val notificationContent = NotificationContentSource()
            // 0.3 Phase 2 Task 2: the media fact. `getActiveSessions` is gated on the app's notification-listener
            // grant, so the component the user already enabled for the category signal is the one named here; no new
            // permission is requested or needed. The source answers `null` (never a fake state) while the grant is
            // missing, the binder is dead, or no session reports a playback state.
            val mediaSession = MediaSessionSource(
                context,
                ComponentName(context, NotificationCategoryService::class.java),
            )
            val calendar = CalendarSource.android(context)
            // 0.3 Phase 2 Task 4: the remaining perception sources. Each reads its fact at frame time through a late
            // lambda, because the objects that own those facts (the host for the acoustic label, the composition for
            // the wearing/panel state) do not exist yet when this block runs.
            //
            // Task 7: one foreground lambda is shared by the perception provider, the device tool's ref gate and the
            // screen projector, so those three cannot disagree about the app in front. R7: the clipboard source gets a
            // foreground predicate, so a known-background host never attempts a read the platform would refuse.
            val foreground = { EdithAccessibilityService.port()?.foreground() }
            val clipboard = ClipboardSource.android(
                context,
                foreground = { runCatching { foreground()?.packageName }.getOrNull()?.let { it == context.packageName } },
            )
            val acoustic = AcousticSource { hostHolder[0]?.lastAcousticLabel() }
            val glassesState = GlassesStateSource(
                connected = { hintLink.isEstablished() },
                wearing = { rootHolder[0]?.glassesWearing() },
                panelVisible = { rootHolder[0]?.isPanelVisible() },
            )
            // edith-0.2 Phase B (B4): the SKILL directory grant. The implementation reads through `java.io.File`,
            // so it is stateless and cheap; the plugin points it at `/sdcard/EDITH/skills/` itself, which keeps the
            // on-device path in the module that owns the SKILL format (`StorageAccessSkillDirectory`).
            val storageAccess = StorageAccessServiceImpl()
            // edith-0.2 Phase B: the Laya graph the publisher's host resolves from `filesDir`. Pointing the service
            // at the main graph file is what makes "the weights are provisioned" a fact this process can check before
            // handing the engine over; the host re-validates the whole set at construction.
            val systemOne = SystemOneServiceImpl(
                modelPath = File(context.filesDir, LAYA_MAIN_GRAPH).absolutePath,
                engine = LayaInferenceEngineImpl(context),
            )
            // edith-0.2 Phase B (C4/B5): the embedding service gets the LiteRT engine and the directory the
            // artifacts are actually provisioned to. Both halves were wrong before: no engine was passed, so the
            // service silently kept its JVM `StubEmbeddingEngine` (vectors built from `text.hashCode()`), and the
            // path was `filesDir/engine-models`, which nothing provisions — so `isAvailable` was false and B9's hard
            // dependency refused every turn. `EmbeddingGemmaLiteRTEngine.embed` is the real tokenizer + forward
            // pass; the service still owns the 768 → 256 truncation and the L2 re-normalization that goes with it.
            val embedding = EmbeddingServiceImpl(
                modelPath = EmbeddingModelDirectory.locate(
                    context.getExternalFilesDir(null),
                    context.filesDir,
                ).absolutePath,
                engine = EmbeddingGemmaLiteRTEngine(),
            )
            val contextSignals = CxrContextSignals(clock, listOf(
                CxrContextSignals.armingSource(proactiveArming),
                foregroundApp::values,
                notificationCategory::values,
                calendar::values,
            ))
            contextSignals.listen(armedContextSink({ hostHolder[0] }, clock, proactiveArming))
            // P5 (spec §S4): one PlaybackGate shared between the speech player, the wake/acoustic suppression, and
            // the ASR ingestion guard. Created before the audio chain so it can be passed to provisionAudioChain.
            val playbackGate = PlaybackGate()
            val audio = provisionAudioChain(
                context = context,
                clock = clock, scheduler = scheduler, ingress = { connection[0]?.ingress },
                emit = { event -> hostHolder[0]?.onEvent(event) },
                cancelTurn = { turnId ->
                    hostHolder[0]?.let { runtime ->
                        runtime.currentInputEpoch?.let { epoch ->
                            runtime.cancelOutputTurn(epoch.sessionId, runtime.currentGeneration, turnId)
                        }
                    }
                },
                allowDebugImport = BuildConfig.DEBUG,
                blobs = blobs,
                onFault = { code -> android.util.Log.i("EdithAudio", code) },
                // Task 14a-fix item 7: an accepted chunk arrives every ~20 ms and a `STALE_EPOCH` offer is the
                // ordinary consequence of audio arriving after a session ended, so neither is worth a log line. Only
                // the two outcomes the loop itself treats as faults are reported here.
                onOffer = { offer -> when (offer) {
                    PcmOffer.OVERFLOW, PcmOffer.UNKNOWN_FORMAT -> android.util.Log.i("EdithAudio", "OFFER:${offer.name}")
                    PcmOffer.ACCEPTED, PcmOffer.STALE_EPOCH -> Unit
                } },
                // Task 14a item 3: the detector's emission becomes a real event. The identity is resolved **per
                // emission** and from the host itself, so the only wakes that exist are the ones the host will accept
                // - and a wake that arrives after the session ended is dropped by `onEvent` instead of opening a turn
                // under a generation that no longer exists.
                wakeEmit = { word, confidence -> hostHolder[0]?.let { host ->
                    val root = rootHolder[0]
                    if (root != null) {
                        // 0.23 P5 (D135): a short chime, governed by the same 「语音朗读」 switch, then the wake
                        // event. No Assistance, no conversation entry and no TTS phrase: the wearer hears that they
                        // were heard, and nothing claims to be an answer. 0.24 S5 final review Important #1: the
                        // composition owns the chime and the resident-panel decision together, so a wake the plugin
                        // will refuse cannot open an overlay with no turn behind it.
                        root.emitWake(host, word, confidence) {
                            val outcome = wakeChime.playOutcome(
                                clock.nowNanos(), root.speechEnabled.value, wakeChimeSink)
                            P3Log.line("P5_CHIME state=${outcome.name.lowercase()}")
                        }
                    } else {
                        // The composition does not exist yet (the chain is built before it): no panel can be asked
                        // for, but the detector's evidence still has to reach the host.
                        emitWakeDetected(host, clock, word, confidence)
                    }
                } },
                // Task 4 (P3.2): the same per-emission identity resolution for the acoustic gate, so a label that
                // arrives after the session ended is dropped by `onEvent` instead of opening anything under a
                // generation that no longer exists. Task 5: the host only produces the event while 「主动辅助」 is on.
                acousticEmit = armedAcousticSink({ hostHolder[0] }, clock, proactiveArming),
                cooldownNanos = smartSettings.cooldownNanos,
                suppressWhile = playbackSuppression { rootHolder[0] },
                playbackGate = playbackGate)
            // P5 (C1+C2+C3 fix): bridge audio capture → segment source → media source.
            val audioBridge = audio?.let { AudioSegmentBridge(it.audioCapture) }
            audioBridge?.let {
                // The same artifact store keeps serving images; audio keeps delegating to the existing bridge.
                model.setMediaSource(ArtifactMediaSource(artifactStore, audio = it))
                setGlobalAudioSegmentSource(it)
                // Wire the bridge into the pipeline's emit callback so sealForTurn fires on final transcripts.
                audio.bridgeRef?.set(it)
            }

            // The smart plugin is registered and configured here, with the composition's own clock, scheduler,
            // profile lookup, output-turn cancellation and configured cooldown - Task 13 made it a real
            // `ConfiguredPlugin`, so the bare constructor would be refused by the host's configuration policy.
            //
            // 0.24 S3 Task 14 fix (C2): the plugin must know whether speech can actually happen. The player is built
            // below (the wiring is assembled first), so the wiring reads it through this holder. Null means the
            // `both`/`voice` answer is display-only and must be settled by its `Displayed` acknowledgement.
            val speechPlayerHolder = java.util.concurrent.atomic.AtomicReference<com.sheen.rokid.plugin.SpeechPlayer?>(null)
            val smartWiring = SmartWiring(smartSettings, profiles, clock, scheduler, { sessionId, generation, turnId ->
                hostHolder[0]?.cancelOutputTurn(sessionId, generation, turnId)
            }, isPanelVisible = { rootHolder[0]?.isPanelVisible() ?: false },
                requestPanelOpen = { route -> rootHolder[0]?.requestPanelOpen(route) ?: false },
                speechAvailable = { speechPlayerHolder.get() != null },
                // 0.3 Phase 4B (`D189`): the gate's **provider**, not its current value. `saveSmartSettings` updates
                // this same instance, so the plugin's policy and shell-precheck wrapper see a save from the next call.
                noInterception = noInterceptionGate.provider())
            // ---- P3.3: the execution layer (spec §17.2/§17.4) ------------------------------------------------------
            // One `ToolSurface` is the single `Capability.Actions` provider (Phase 4A Task 4): the umbrella catalog's
            // four verified tools plus the five atomic channel legs, 22 descriptors in all. The advertised set is fixed
            // (A3): each channel is asked per call, never at read time, so a channel that goes down refuses rather than
            // disappearing from the model's table.
            val shizukuShell = ShizukuUserServiceShell(context, BuildConfig.VERSION_CODE, BuildConfig.DEBUG,
                // The bind is asynchronous, so the session's first probe can honestly read "not yet". This line is the
                // moment the channel actually becomes usable - and it is what a device stage reads instead of guessing.
                onReady = {
                    P3Log.line("P3_EXEC channel=shizuku available=true")
                    // Through the holder: this shell is built inside `create`, before any composition instance exists.
                    rootHolder[0]?.refreshExecutionChannels()
                })
            // 2026-10-20: the lock-screen unlock gate. One coordinator owns the single-flight PIN attempt, the
            // keyguard observation and the bounded screen wake lock; the tool catalog calls it before device/open/dial.
            val lockPinStore = KeystoreLockPinStore(context)
            val screenKeepAlive = ScreenKeepAlive(context)
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            val powerManager = context.getSystemService(android.os.PowerManager::class.java)
            val unlockCoordinator = UnlockCoordinator(
                lockState = object : LockState {
                    override fun isLocked(): Boolean = keyguard?.isKeyguardLocked ?: true
                    override fun isSecure(): Boolean = keyguard?.isDeviceSecure ?: false
                },
                shell = LockShell { command -> shizukuShell.run(command).exitCode == 0 },
                pins = lockPinStore,
                accessibilityReady = { EdithAccessibilityService.connected() },
                keepAlive = { screenKeepAlive.acquire() },
                nowMillis = { android.os.SystemClock.elapsedRealtime() },
                sleepMillis = { millis ->
                    try {
                        Thread.sleep(millis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
                // 2026-10-22 K70 acceptance: the PIN keyguard is drawn ~1.9 s after the wake key, so the gate must
                // poll the display state and the keyguard's own PIN view before injecting any digit.
                interactive = { powerManager?.isInteractive == true },
                keyguardPinVisible = { EdithAccessibilityService.keyguardPinVisibleNow() },
                trace = { stage -> P3Log.line("P3_UNLOCK $stage") },
            )
            // The one production gate the catalog and the debug probe share; the log line carries the outcome, never
            // the PIN or a shell command.
            val unlockGate = UnlockGate { requireAccessibility ->
                unlockCoordinator.ensureUnlocked(requireAccessibility).also {
                    P3Log.line("P3_UNLOCK requireA11y=$requireAccessibility outcome=${it.name}")
                }
            }
            val rootProbe = RootProbe.onDevice()
            // T2.7: one package catalog for the app-name resolution and the label fallback of `open`/`list`.
            val appCatalog = PackageManagerAppCatalog(context)
            // 0.3 Phase 1 Task 2: the static device profile / per-request state channel. Built here, before the
            // execution layer, because Phase 2 Task 7's perception provider and `device` tool both read it. Its
            // capability set is a live read of gates assembled above (the Shizuku shell exists, the speech player has
            // been decided); answering from a half-built composition would let the profile name a capability the host
            // cannot answer. Facts are read lazily on each profile()/state() call.
            // The wake-word source is the same registry the plugin declares its words to (`Capability.WakeWord`), so
            // the prompt and the detector describe one word set, not two copies of the settings.
            //
            // 0.3 Phase 2 Task 2 completes the perception facts. Each lambda reads the object that owns the fact at
            // frame time: the accessibility port for the foreground app and the IME window list, the media-session
            // source, and the composition/link for the panel and both glasses facts. The composition itself is reached
            // through `rootHolder` because the provider map is assembled before the root exists; a fact asked for
            // before then is honestly `null` rather than a fabricated default.
            //
            // Task 7 moves this construction above `DeviceKernel`/`DeviceTool`; the speech player is built later, so
            // the capability set reads the holder that `create` fills before the host exists.
            val deviceState = androidDeviceStateProvider(
                context = context,
                capabilities = {
                    buildSet {
                        if (EdithAccessibilityService.connected()) add("a11y")
                        if (shizukuShell.ready()) add("shizuku")
                        if (speechPlayerHolder.get() != null) add("tts")
                        if (notificationCategory.isConnected) add("notification_access")
                        if (foregroundApp.isGranted) add("usage_access")
                        if (calendar.isGranted) add("calendar")
                        // The media session is readable only through that same notification-listener grant
                        // (`MediaSessionManager.getActiveSessions` throws without it), so the capability follows the
                        // grant instead of being advertised and then refused. Clipboard readability is foreground-
                        // bound (R7), so it stays a coverage fact rather than a capability here.
                        if (notificationCategory.isConnected) add("media_session")
                    }
                },
                wakeWords = { smartWiring.wakeWords.current() },
                foreground = { foreground() },
                imeVisible = { EdithAccessibilityService.imeVisibleNow() },
                media = { mediaSession.current() },
                panelVisible = { rootHolder[0]?.isPanelVisible() },
                glassesConnected = { hintLink.isEstablished() },
                glassesWearing = { rootHolder[0]?.glassesWearing() },
            )
            // ---- 0.3 Phase 4A Task 4: the 22-host-tool surface (spec §4.1/§4.2) ---------------------------------------
            // `ToolCatalog` is the umbrella half: four host tools (`device`/`shell`/`open`/`dial`) that stay advertised
            // in every channel state (A3). The atomic legs built below it add the 18 fast-path tools, and the plugin
            // adds `notify_user`/`load_skill` beside the surface - 24 model-visible tools in all. The old 21-tool
            // `ExecutionRouter` remains retired from production.
            //
            // The accessibility port is resolved per call through the service's companion: the user can switch the
            // service off at any moment. Task 7 review I2 (R11): `device` stays advertised so its `status` action can
            // report `accessibility=false`; every other device action and every other port call still requires the
            // live service and fails on the kernel's own bound gate instead of dereferencing null. The delegate must
            // share the service's registry (R36), so the refs rendered by ScreenProjector are the refs
            // `performNodeAction` resolves.
            val accessibilityPort = object : AccessibilityPort {
                private fun live(): AccessibilityPort = EdithAccessibilityService.port()
                    ?: throw IllegalStateException("the accessibility service is not connected")

                override fun isBound() = live().isBound()
                override fun isInteractive() = live().isInteractive()
                override fun isKeyguardLocked() = live().isKeyguardLocked()
                override fun observe(filter: DeviceScreenFilter) = live().observe(filter)
                override fun performNodeAction(ref: Ref, action: String) = live().performNodeAction(ref, action)
                override fun dispatchGesture(gesture: Gesture) = live().dispatchGesture(gesture)
                override fun screenshot() = live().screenshot()
                // Task 7 review I2 (R11): the one port call `status` makes before it reads health. An unbound service
                // has no foreground to report - that is `null`, not a failed status call. Every other call above still
                // requires the live service, so this fallback cannot make an unbound channel look usable.
                override fun foreground() = EdithAccessibilityService.port()?.foreground() ?: Foreground(null, null)
                // 0.3 Phase 2 Task 8: same unbound style - no live service means no root package to report.
                override fun rootPackage() = EdithAccessibilityService.port()?.rootPackage()
            }
            val deviceKernel = DeviceKernel(
                accessibility = accessibilityPort,
                shell = PrivilegedShellPort(shizukuShell),
                // The companion registry exists with the class, so the kernel built during app start holds
                // the exact instance the service's adapter mints refs into, even if the accessibility
                // service connects later. There is deliberately no fallback registry.
                registry = EdithAccessibilityService.registry(),
                // 0.25 Task 13 (R67): screenshots become real artifact references in the same store the model's
                // `ArtifactMediaSource` resolves, instead of the `pending` placeholder.
                artifactStore = artifactStore,
            )
            val screenProjector = ScreenProjector(
                ObservationRenderer(deviceKernel.registry),
                deviceKernel.registry,
                foregroundActivity = { runCatching { foreground()?.activity }.getOrNull() },
            )
            // ---- edith-0.3 Phase 2 Task 7: perception wiring ------------------------------------------------
            // `observe` is the kernel-backed whole-screen read: a failed envelope is thrown (the provider turns that
            // into `screen=unavailable`), and a failed read never silently answers a pre-turn cache. The same
            // `foreground` lambda feeds the provider, the tool and the projector, so their foreground facts agree.
            val perceptionPolicy = PerceptionPolicy()
            val observe = { filter: DeviceScreenFilter ->
                val envelope = deviceKernel.observe(filter)
                // Carry the kernel's own reason (DEVICE_LOCKED / DEVICE_SCREEN_OFF / CHANNEL_UNAVAILABLE) instead of a
                // bare failure: the coverage line must be able to say "locked" rather than blaming accessibility.
                if (!envelope.ok) throw ScreenReadUnavailable(envelope.error?.code)
                deviceKernel.latestObservation ?: throw ScreenReadUnavailable("OBSERVE_NO_OBSERVATION")
            }
            val perception = AndroidPerceptionProvider(
                observe = observe,
                foreground = foreground,
                registry = deviceKernel.registry,
                notifications = notificationContent,
                calendar = calendar,
                media = mediaSession,
                deviceState = deviceState,
                clipboard = clipboard,
                acoustic = acoustic,
                glasses = glassesState,
                policy = { perceptionPolicy },
                // Phase 2 Task 10: the live switch, not an assembly-time copy. The provider answers `Disabled` and the
                // injected capture is not called while it is off.
                visionEnabled = { visionGate.enabled },
                // 0.3 Phase 2.1 Task 4 fix: the second gate resolves the **turn's** profile, not the assembly-time
                // settings choice. The repository selection (the same source `selectedProfile(pluginId)`/the turn
                // configuration reads) wins; the settings profile is only the fallback. Both are read inside the lambda,
                // so a profile selection change applies to the next capture. Null/exception reads false (no image).
                profileSupportsImage = {
                    autoVisionProfileSupportsImage(
                        selection = {
                            (profiles as? AndroidProfileRepository)?.selections()
                                ?.get(smartWiring.plugin.descriptor.pluginId)
                        },
                        load = profiles::get,
                        fallback = smartWiring::selectedProfile,
                    )
                },
                // 0.3 Phase 2.1 Task 4 fix: one mapping for the kernel envelope. A `pending` placeholder (the capture
                // worked, the artifact store did not) is `Unavailable("SCREENSHOT_PENDING")`, never an unresolvable
                // image reference in a model request.
                screenshot = { perceptionScreenshotResult(deviceKernel.screenshot()) },
                latestObservation = { deviceKernel.latestObservation },
                clock = { System.currentTimeMillis() },
            )
            val deviceTool = DeviceTool(
                DeviceKernelOperations(deviceKernel),
                screenProjector,
                perception = perception,
                deviceState = deviceState,
                registry = deviceKernel.registry,
                foreground = foreground,
                visionEnabled = { visionGate.enabled },
                refTtlMillis = { perceptionPolicy.refTtlMillis },
            )
            val callAvailable: () -> Boolean = {
                context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            // ---- edith-0.3 Phase 2.2 Task 6: one host observation source for the screen tools -------------------
            // `open`, `shell` and `dial` all describe "what the screen looks like after the call", so they share this
            // one source instead of each growing its own read. It is a real post-call read (`refresh`), not the turn's
            // opening cache: the whole point of a failed `open`'s observation is to show the screen the failure left
            // behind. With the accessibility channel off there is nothing honest to read, so the source answers an
            // explicit `unavailable/no_accessibility` observation - never an invented screen and never a silent null.
            // It is non-throwing: a failed read degrades to `OBSERVE_FAILED` and the tool keeps its own outcome.
            val toolObservation: () -> ToolObservation? = {
                if (!EdithAccessibilityService.connected()) {
                    PerceptionToolObservation.unavailable(PerceptionToolObservation.NO_ACCESSIBILITY)
                } else {
                    runCatching { PerceptionToolObservation.render(perception.refresh()) }
                        .getOrElse { PerceptionToolObservation.unavailable(TOOL_OBSERVATION_FAILED) }
                }
            }
            val openTool = OpenTool(
                shell = shizukuShell,
                catalog = appCatalog,
                foreground = { EdithAccessibilityService.port()?.foreground()?.packageName },
                // 0.3 Phase 2 Task 8: `am start -W`'s own `Status: ok` is not success; the tool verifies the
                // resolved package is both the foreground app and the accessibility root.
                foregroundActivity = { EdithAccessibilityService.port()?.foreground()?.activity },
                rootPackage = { EdithAccessibilityService.port()?.rootPackage() },
                // 0.3 Phase 2.1 Task 4: a verified open captures through the same provider gate as observe/act.
                capture = { perception.screenshot() },
                // 0.3 Phase 2.2 Task 6: the shared post-call observation, so an `open` failure tells the model what
                // the phone is actually showing now.
                observe = toolObservation,
            )
            val toolCatalog = ToolCatalog(
                device = deviceTool,
                shell = ShellTool(shizukuShell),
                open = openTool,
                // The number validation/permission check/ACTION_CALL hand-off stay in the one executor that owns them.
                dial = DialTool(actions, callAvailable),
                a11yAvailable = { EdithAccessibilityService.connected() },
                shellAvailable = { shizukuShell.ready() },
                callAvailable = callAvailable,
                unlock = unlockGate,
                // Task 6: the same source and the same capture gate as `open`; `shell`/`dial` differ only in their own
                // outcome shapes, which the catalog leaves untouched.
                observe = toolObservation,
                capture = { perception.screenshot() },
            )
            // ---- edith-0.3 Phase 4A Task 4 (A4): the 22-host-tool face ------------------------------------------
            // `toolCatalog` is the umbrella half (the verified, structured path). The five atomic backends beside it are
            // the fast path the model may choose instead: one leg per channel, each answering its own `available()` at
            // call time. `ToolSurface` is the **one** `Capability.Actions` provider over both halves - 18 atomic
            // descriptors plus the catalog's four = 22 - and it refuses a duplicate name at construction, which is why
            // the intent leg filters `dial`: the catalog's `DialTool` is that name's only advertised owner.
            //
            // Advertising is stable (A3): a channel that is down keeps its tools advertised and its own `execute`
            // answers the refusal, so the schema and the cached prompt prefix cannot change under the model mid-session.
            //
            // The atomic a11y backend needs the live `AccessibilityUi`, and the composition is assembled before the
            // service connects, so the adapter resolves it per call exactly as `accessibilityPort` above does. A call
            // that passes the backend's `connected` gate finds the live instance; an unbound one is the backend's own
            // `ACTION_CHANNEL_UNAVAILABLE` refusal, never a stale or fabricated UI.
            val accessibilityUi = object : AccessibilityUi {
                private fun live(): AccessibilityUi = EdithAccessibilityService.ui()
                    ?: throw IllegalStateException("the accessibility service is not connected")

                override fun clickText(text: String): Boolean = live().clickText(text)
                override fun setText(text: String): Boolean = live().setText(text)
                override fun screenFingerprint(): String = live().screenFingerprint()
                override fun snapshot(filter: A11yScreenFilter): ScreenSnapshot = live().snapshot(filter)
                override fun clickByIndex(snapshotId: String, index: Int): Boolean = live().clickByIndex(snapshotId, index)
                override fun longPressByIndex(snapshotId: String, index: Int): Boolean =
                    live().longPressByIndex(snapshotId, index)
                override fun scroll(direction: ScrollDirection): Boolean = live().scroll(direction)
                override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean =
                    live().setTextByIndex(snapshotId, index, text)
                override fun structuralFingerprint(): ScreenFingerprint = live().structuralFingerprint()
            }
            // `list_apps` and the umbrella `open` resolve spoken names against the same `appCatalog` instance.
            // `open_app` (intent) resolves through its own executor's package catalog over the same package manager,
            // so the paths cannot disagree about what is installed.
            // The a11y backend owns turn-scoped state (its `read_screen` snapshot), so its adapter is the one given
            // the turn-boundary seam: `ToolSurface.beginTurn()` then clears that snapshot at the start of every turn
            // and the index tools can only use a snapshot read in the current turn. The other legs own no turn state.
            val accessibilityBackend = AccessibilityExecutionBackend(
                ui = accessibilityUi,
                connected = { EdithAccessibilityService.connected() },
            )
            val atomicExecutors = listOf(
                // accessibility: the semantic UI fast path (`read_screen`/`tap`/...)
                AtomicActionExecutor(
                    backend = accessibilityBackend,
                    onTurnBoundary = accessibilityBackend::beginTurn,
                ),
                // intent: the L0/L1 open/alarm/note actions, without the `dial` advertisement the catalog owns
                IntentAtomicExecutor(actions),
                // Shizuku: the privileged four plus `set_brightness`; its own `available()` gates every call
                AtomicActionExecutor(
                    ShizukuExecutionBackend(
                        shell = shizukuShell,
                        state = ShizukuTargetState(shizukuShell),
                        channelUp = { shizukuShell.ready() },
                    ),
                ),
                // Local: `list_apps` only (no Shizuku; `launch_app` was retired 2026-10-22, 24 → 23)
                AtomicActionExecutor(LaunchAppExecutor(catalog = appCatalog)),
                // Shizuku: back/home/recents/notifications
                AtomicActionExecutor(
                    SystemActionExecutor(shell = shizukuShell, channelUp = { shizukuShell.ready() }),
                ),
            )
            // The catalog is closed through the surface's `onClose` (after the atomic executors), so the composition's
            // close path below adds the **surface** and not the catalog: one owner, one close.
            val toolSurface = ToolSurface(
                catalog = toolCatalog,
                atomic = atomicExecutors,
                onClose = { toolCatalog.close() },
            )
            // B (2026-10-22 acceptance): one lock-screen gate over every tool that needs an unlocked phone. The
            // catalog still owns `device`/`open`/`dial`; this decorator covers the atomic screen/intent tools that
            // used to run straight into the keyguard (`read_screen`, `open_app`, ...).
            val gatedActions = UnlockGatedActions(toolSurface, unlockGate)
            // 0.3 Phase 4B (B5): the production phone surface, through the named seam so a JVM test drives **this**
            // rule rather than a copy of it. It used to be `rootHolder[0] != null`, which is true for the whole
            // session and made the presenter's aggregate dishonest.
            val confirmationUi = phoneConfirmationUi { rootHolder[0] }
            val confirmationPresenter = ConfirmationPresenter(
                summary = object : SummaryDisplayer {
                    // 0.3 Phase 4B (B4): SHOW needs a live clock mapping; without one the display would drop the hint
                    // silently, so the surface reports itself unavailable instead of claiming it can present.
                    override fun available(): Boolean = hintLink.liveOutput() != null

                    override fun show(hintId: String, text: String, ttlMillis: Long): Boolean {
                        val device = hintLink.liveOutput() ?: return false
                        val identity = hostHolder[0]?.currentIdentity() ?: return false
                        val now = clock.nowNanos()
                        return runCatching {
                            device.show(
                                Assistance(
                                    // 0.3 Phase 4B (B5/B4 carry-forward): the presenter's **stable per-question**
                                    // id, passed through. Minting a fresh id here (as B4 did) made every PANEL_SHOWN
                                    // re-announcement append a new `say-<hintId>` row on the glasses.
                                    hintId = hintId,
                                    sessionId = identity.first,
                                    generation = identity.second,
                                    sourceTurnId = null,
                                    priority = 3,
                                    text = text,
                                    // The host refuses an assistance that carries speech, and P3.3 ships no speech path.
                                    optionalSpeech = null,
                                    expiresAtNanos = now + ttlMillis * 1_000_000L,
                                ),
                                // A re-announcement of the same hint must be a newer revision, or the glasses refuse it
                                // as stale; a monotonic counter keeps that true even when two sends share a clock tick.
                                revision = summaryRevision.incrementAndGet(),
                                listener = { },
                            )
                            true
                        }.getOrDefault(false)
                    }
                },
                ui = confirmationUi,
                // The host owns the identity: the result is rebuilt and emitted through the same path every other
                // host-originated event uses, so a stale session's answer is dropped by `onEvent` instead of delivered.
                deliver = { result ->
                    // The other half of §17.4's hard criterion. An answer the presenter settles but the plugin never
                    // receives is indistinguishable from a dropped tap from the outside, so both halves are logged:
                    // `identityPresent=false` means the envelope carried no session identity, which `HostRuntime.onEvent`
                    // rejects as a foreign event - a "yes" that silently becomes a timeout-as-denial.
                    //
                    // The identity is read off the result itself, which the presenter captured when the question went
                    // up. It used to be a `currentIdentity()` round trip **on this thread** (the session page's UI
                    // thread) in the same stack frame as the P3.3 ANR. The old code re-read the host at *answer* time, so
                    // the two agree only while the session has not been replaced - and when it has, the envelope names
                    // the session that asked, which `onEvent` drops as not current. Either way the field keeps its
                    // meaning, and the UI thread stops waiting on the host.
                    val accepted = hostHolder[0]?.onEvent(result) ?: false
                    P3Log.line("P3_CONFIRM_HOST delivered=$accepted " +
                        "identityPresent=${result.envelope.sessionId.value.isNotBlank()}")
                    accepted
                },
                clock = clock,
                scheduler = scheduler,
                envelopeFor = { requestId ->
                    val identity = hostHolder[0]?.currentIdentity()
                    val now = clock.nowNanos()
                    EventEnvelope(
                        identity?.first ?: SessionId(""),
                        identity?.second ?: Generation(0),
                        requestId,
                        now,
                        now,
                    )
                },
                // 0.25 Task 11 (R51): the presenter announces the question to the glasses through the same link that
                // carries hints; a link with no established session answers false, which only degrades to phone-only.
                // 0.3 Phase 4B (B4): the keys can serve exactly while a session is established; that is also the
                // condition under which the presenter asks the link to create the panel below.
                glasses = object : ConfirmationGlasses {
                    override fun available(): Boolean = hintLink.isEstablished()

                    override fun show(requestId: String, active: Boolean): Boolean =
                        hintLink.confirmationState(requestId, active)
                },
                // 0.3 Phase 4B (B4, spec §5.4): `PANEL_OPEN` creates the resident window, so the question is announced
                // into a visible panel rather than a hidden one. The route is WAKE: a confirmation is the wake turn's
                // decision, and a proactive turn opens its own (PROACTIVE) panel without a question.
                panel = ConfirmationPanel {
                    rootHolder[0]?.requestPanelOpen(AssistantRoute.WAKE) ?: false
                },
            )
            // P5 (spec §S1/S5): construct the speech player only when the user switch is on AND the engine is ready.
            // The player is passed to the environment so OutputArbiter can use it; null means no speech capability.
            val speechEnabledNow = runCatching { smartSettingsStore.load().speechEnabled }.getOrNull() ?: false
            val speechPlayer: com.sheen.rokid.plugin.SpeechPlayer? = if (speechEnabledNow) {
                AndroidSpeechPlayer.create(context, playbackGate)
            } else null
            // 0.24 S3 Task 14 fix (C2): the plugin's speech gate is the **player's presence**, not the stored switch:
            // a switch that is on but whose engine failed to create still produces no SpeechFinished. Set before the
            // host is built/activated so the value the plugin reads is the one the arbiter will honour.
            speechPlayerHolder.set(speechPlayer)
            // Phase C: one conversation record, shared by the provider built just below and by the root instance whose
            // assistant tab reads it. It has to be created here because the provider map is built before the root.
            val conversationLog = ConversationLog()
            // 0.24 S2 Task 11 / Task 6 review: the production display must send through the **real** CXR link, not the
            // silent 3-arg constructor. Built here because the provider map is assembled before the composition exists.
            val conversationDisplay = ConversationWireDisplay(
                log = conversationLog,
                wire = hintLink,
                sendSaid = { route, entryId, text -> hintLink.userSaid(route, entryId, text) },
                sendBusy = { route, busy -> hintLink.busy(route, busy) },
            )
            val environment = CxrHostEnvironment(clock = clock, input = audio?.input ?: UnwiredInputPort,
                liveOutput = hintLink::liveOutput, scheduler = scheduler, speech = speechPlayer,
                providers = buildMap {
                    put(Capability.Model, model)
                    audio?.let { put(Capability.Asr, it.asrSession) }
                    // Task 12: always answerable - the five L0/L1 actions need no engine, no transport and no
                    // permission at assembly time, so the advertised set and the answerable set stay equal.
                    // 0.3 Phase 4A Task 4: it is the one `ToolSurface` - the 18 atomic tools plus the catalog's four
                    // (22 host descriptors). A down channel keeps its tools and answers its own refusal (A3); the
                    // plugin adds `notify_user`/`load_skill` beside it for 24. The old 21-tool router stays retired.
                    put(Capability.Actions, gatedActions)
                    // P3.3: the confirmation channel is answerable whenever the composition has a screen to show the
                    // question on; a host that cannot ask must refuse the action, and this provider is what lets it ask.
                    put(Capability.Confirmation, confirmationPresenter)
                    // Task 14a item 2: the configured word list is answerable without a model, a transport or a
                    // permission, so `Capability.WakeWord` joins the advertised set and the smart plugin (which
                    // declares it) can finally pass the host's capability gate. The provider is the wiring's own
                    // registry - the instance the plugin declares its configured words to - so there is one word set,
                    // not a second registry built from the same settings (Task 14a-fix item 5).
                    put(Capability.WakeWord, smartWiring.wakeWords)
                    // P3.2 Task 6: `Capability.Context` stops being an advertised-but-unanswerable capability. The
                    // provider is the composition's own signals object, so the values a plugin reads and the events
                    // the host delivers describe one source, not two.
                    put(Capability.Context, contextSignals)
                    // 0.3 Phase 1 Task 2: the device profile/state channel. The provider is a pure local read (no
                    // permission, model or transport needed at assembly), so it joins the advertised set; the host can
                    // now grant the smart plugin's `Capability.DeviceState` when Task 12 declares it.
                    put(Capability.DeviceState, deviceState)
                    // edith-0.3 Phase 2 Task 7: the perception port. The provider is the same instance the `device`
                    // tool decorates its results from, and its registry is the kernel's own, so a snapshot's refs are
                    // exactly the refs the kernel/`performNodeAction` resolves (R36). Plugins that do not declare
                    // `Capability.Perception` are unaffected (Task 9 declares it).
                    put(Capability.Perception, perception)
                    // edith-0.2 Phase B (B4): the user's SKILL directory. `StorageAccessServiceImpl` is pure
                    // `java.io.File` work with no Android dependency and no permission to request at assembly time,
                    // so this capability is answerable whenever the composition exists - the advertised set and the
                    // answerable set stay equal. A device that denies the read (no card, revoked permission) yields
                    // null/empty from the implementation, which is "no SKILLs installed", not a failure (spec §3.7).
                    put(Capability.StorageAccess, storageAccess)
                    // edith-0.2 Phase C: the conversation view. Always answerable - the phone's own record exists with
                    // or without a live display, and a refused send only costs the display of an utterance (see
                    // `ConversationWireDisplay`). The wire is the real `CxrHintLink` (Task 11), not the silent fallback.
                    put(Capability.ConversationDisplay, conversationDisplay)
                    // edith-0.2 Phase B (B9/B11): the two local models become answerable, which is what lets the
                    // plugin serve at all - declaring them as hard dependencies meant a host that could not provide
                    // them correctly refused to activate it. Both are constructed here and load on their own threads;
                    // a missing artifact leaves the service UNAVAILABLE and the plugin refuses service, which is the
                    // honest answer rather than a stub answering in the model's place.
                    put(Capability.SystemOne, systemOne)
                    put(Capability.Embedding, embedding)
                },
                storageFor = namespaceFactory,
                taskScope = ExecutorTaskScope(pluginTasks))
            val host = HostRuntime(1, environment)
            hostHolder[0] = host
            // The observer is shared through a holder rather than captured by value, because the bring-up harness arms
            // it (via installRawPcmObserver) AFTER this composition is built but BEFORE the lazy connection is created.
            // Capturing the value here - or letting `create`'s parameter name shadow the field, as it did - hands the
            // transport a permanent no-op, so every capture reads zero bytes however much audio actually arrives.
            val observerHolder = arrayOf(initialObserver)
            val scene = SceneReadyResolver()
            val root = HostCompositionRoot(host, profiles, model, environment, buildList {
                add(AutoCloseable {
                    timer.shutdownNow(); pluginTasks.shutdownNow()
                    http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
                })
                // 0.25 Task 10 (R42): closing the surface closes the device kernel (through the catalog's onClose) and
                // any closeable atomic executor, so a closed composition cannot leave a device thread behind. The
                // The surface is added here **instead of** the catalog: one owner, so the catalog closes exactly once.
                // B wraps it in the lock-screen decorator, which closes the surface (and through it the catalog) once.
                add(gatedActions)
                // Closes the pipeline (and with it the native engines) with the composition that owns them.
                audio?.let { add(it) }
                // 0.23 P5: the chime's ToneGenerator is a native resource; release it with the composition.
                add(wakeChimeSink)
                // The unlock wake lock must not outlive the composition.
                add(screenKeepAlive)
            }, profileTester = tester, profileConsent = probeGate::grant,
                namespaceFactory = namespaceFactory, speakerProfiles = AndroidSpeakerProfileStore(blobs),
                // One CXR connection per composition, built on first use: the transport stays the only
                // SDK access path and the App carries the exact recorded pairing. Readiness still arrives
                // from the SDK, so a wired-but-not-ready connection keeps the audio capability missing.
                rokidConnectionFactory = { activity ->
                    // Read through the holder, so an observer armed after this composition was built is still the one
                    // the transport calls.
                    RokidConnection(activity.asCxrActivity(), CxrGlassesTarget.config(), host, scene::onSceneReady,
                        clock, { data -> observerHolder[0](data) },
                        // Task 14a item 5: the receiver's offer outcome is what turns an overflow into a report.
                        onOffer = { offer -> rootHolder[0]?.onPcmOffer(offer) }).also { built ->
                        connection[0] = built
                        // 0.24 S2 Task 11: register the scene listener **before** the session is created/connected,
                        // exactly where the official 1.1.2 sample does. One factory registration per new session; the
                        // backend releases the previous session's handle on reconnect.
                        built.input.setGlassesEventListenerFactory { glassesEvents }
                    }
                },
                authorizeResult = { resultCode, data -> connection[0]?.onAuthorizationResult(resultCode, data) },
                hintLink = hintLink,
                conversationLog = conversationLog,
                conversationDisplay = conversationDisplay,
                // Vendor callbacks (PIN / PANEL_SHOWN / UI state / scene events) are marshalled onto the composition's
                // own scheduler thread; the timer is already the host's off-main executor, and a late task after close
                // is inert in the receiving method.
                dispatchHostEvent = { task -> scheduler.at(clock.nowNanos(), task) },
                smartWiring = smartWiring,
                smartSettingsStore = smartSettingsStore,
                visionGate = visionGate,
                noInterceptionGate = noInterceptionGate,
                proactiveArming = proactiveArming,
                playbackGate = playbackGate)
            // Keep the instance field and the holder pointing at the same observer, so installRawPcmObserver affects a
            // connection that has not been built yet.
            root.observerHolder = observerHolder
            root.armObserver(initialObserver)
            root.lockPinStore = lockPinStore
            root.unlockCoordinatorForProbe = unlockGate
            root.toolCatalogForProbe = toolCatalog
            // Phase 4A Task 4: the one `Capability.Actions` provider, for the JVM gate. Production never reads it.
            root.toolSurfaceForProbe = toolSurface
            // Task 6: the JVM gate reads the same `open` instance the Actions capability serves, so it can prove the
            // observation source was really wired into `OpenTool(observe = ...)`.
            root.openToolForProbe = openTool
            root.audioChain = audio
            // P5 (spec §S5): sync the UI state with what was loaded above.
            root.speechOn.value = speechEnabledNow
            root.foregroundAppSource = foregroundApp
            root.notificationCategorySource = notificationCategory
            root.calendarSource = calendar
            // P3.3 (spec §17.2/§17.4): the execution channels, their probe lines, their bring-up, and the question-asker.
            root.channelProbes = listOf(
                INTENT_CHANNEL to { true },
                A11Y_CHANNEL to { EdithAccessibilityService.connected() },
                SHIZUKU_CHANNEL to { shizukuShell.ready() },
            )
            // Root is asked through `rootStatusProbe` rather than a `channelProbes` entry, so the panel gets the row
            // **and** the reason from one probe (leftover-sweep review, Important 1); the row itself is appended by
            // `executionChannels`, which keeps the four `P3_EXEC` ids and their order.
            root.rootStatusProbe = { rootProbe.status() }
            // Phase C: the skills page reads the same provider a turn does, and asks about the shared-storage grant
            // separately - `listFiles` answers "empty" for both "no SKILLs" and "no permission", which is the
            // distinction B4's device work needed and could not get.
            root.skillDirectory = StorageAccessSkillView(
                storage = storageAccess,
                sharedStorageGranted = {
                    android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
                },
            )
            root.executionChannelBringUp = {
                shizukuShell.bind()
                shizukuShell.requestPermissionIfNeeded()
            }
            // R-9 device probe: hand the debug-only instrument the **production** shell it would otherwise have to
            // duplicate (see the field's KDoc). Debug-gated so a release build never writes it.
            if (BuildConfig.DEBUG) root.privilegedShellForProbe = shizukuShell
            root.confirmationPresenter = confirmationPresenter
            // P3.3 (§17.4): the notification shade, so a question can be answered while this App is not in front.
            root.notificationSurface = AndroidNotificationSurface(context)
            // 0.3 Phase 4B (B5): the side-effect-free half of the overlay check. `raiseConfirmationUi` really starts the
            // Activity, so the availability ladder must not call it to find out whether raising is possible.
            root.canRaiseConfirmationUi = { android.provider.Settings.canDrawOverlays(context) }
            root.raiseConfirmationUi = {
                // Owner directive 2026-10-10: a time-boxed question must reach the user as a popup. Starting an
                // Activity from the background is allowed on Android 10+ only under an exemption, and holding the
                // overlay permission is one; MIUI adds its own "后台弹出界面" switch on top. Neither is assumed: when the
                // permission is missing this refuses and the notification (already posted) is the surface.
                if (!android.provider.Settings.canDrawOverlays(context)) {
                    P3Log.line("P3_CONFIRM_RAISE raised=false reason=NO_OVERLAY_PERMISSION")
                    false
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_SHOW_CONFIRMATION)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                                ),
                        )
                        P3Log.line("P3_CONFIRM_RAISE raised=true")
                        true
                    }.getOrElse { false }
                }
            }
            root.scriptedModelWire = scriptedWire
            rootHolder[0] = root
            // 0.23 P5 (U11): the glasses' STOP reaches the production composition through the link, and the identity
            // is read from the host at call time by `requestStop` (not captured here).
            hintLink.onStopRequest = { sessionId, generation ->
                rootHolder[0]?.requestStop(sessionId, generation) ?: false
            }
            // 0.25 Task 11 (R51): the glasses' ENTER/BACK decision. The validated identity travels with it so the
            // composition re-checks it against the current session at delivery time.
            hintLink.onConfirmationDecision = { identity, requestId, approved ->
                rootHolder[0]?.onConfirmationDecisionFromLink(identity, requestId, approved)
            }
            // 0.24 S2 Task 11: the resident handshake and panel lifecycle. Each link callback runs on the vendor
            // callback thread, so the root methods below marshal it (Task 12's timer consumes `onPanelShown`), and
            // each carries the identity the link validated against - never the identity current at delivery time.
            hintLink.onPin = { identity, route -> rootHolder[0]?.onPinFromLink(identity, route) }
            hintLink.onUiState = { identity, state -> rootHolder[0]?.onUiStateFromLink(identity, state) }
            hintLink.onPanelShown = { identity, route -> rootHolder[0]?.panelShownArrived(identity, route) }
            scene.composition = root
            // Task 14a item 1: the real plugin, not `SmartAssistantPlugin()` with defaults. Its configuration is
            // applied by `activateSelected` before every activation, from the same settings this root was built with.
            root.registerPlugin(smartWiring.plugin)
            root.registerPlugin(InterviewAssistantPlugin())
            // Debug-only: registers the diagnostic text plugin and the hint probe, and hands the UI the probe's
            // sender. Null in release builds, which is what keeps the probe out of the product surface.
            root.diagnosticHintSender = DiagnosticRegistration.register(root, clock, scheduler)
            return root
        }

        /** Holds the composition so a lazily built transport can report scene readiness back into it. */
        private class SceneReadyResolver {
            @Volatile var composition: HostCompositionRoot? = null
            fun onSceneReady() { composition?.onSceneReady() }
        }

        /**
         * 0.3 Phase 4B (B5, spec §5.4): the **production** phone surface, as a named seam.
         *
         * `create` builds it through a holder because the provider map is assembled before the composition exists; a
         * JVM test hands it an already-built composition. Either way there is one implementation of the truthfulness
         * rule: [HostCompositionRoot.canPresentOnPhone]. B4's inline answer was `rootHolder[0] != null`, which is true
         * for the whole session and made the presenter's aggregate dishonest.
         */
        internal fun phoneConfirmationUi(composition: () -> HostCompositionRoot?): ConfirmationUi =
            object : ConfirmationUi {
                override fun available(): Boolean = composition()?.canPresentOnPhone() == true

                // Through the composition that asked: the question is shown only after a session starts, by which
                // time the holder is filled. B7: the returned rung is the surface that really took the question, so the
                // presenter's count-only line names it instead of assuming the dialog.
                override fun show(requestId: String, summary: String): ConfirmationChannelKind? =
                    composition()?.showConfirmation(requestId, summary)

                override fun hide(requestId: String) {
                    composition()?.hideConfirmation(requestId)
                }
            }

        /**
         * `D53`① (2026-10-11) and 0.25 R75: the consent the gated model client consults, and the **only** one it is given.
         *
         * The session-level upload switch is gone - the owner's ruling was "zero knobs": **"会话在运行"即允许** the
         * transcript and its turn context to reach the configured model service, and the only consenting action is
         * pressing 开始. The gated client asks this for every real turn, and the session is running exactly when a turn
         * can be built, so `text` is allowed unconditionally here.
         *
         * What must **not** change: `image` is not granted by the session. It is granted only when the **profile**
         * declares `IMAGE` (0.25 R75; the session cannot start on a text-only profile at all, R74) and never by a
         * blanket OR; `audio` stays ungranted (not part of this version). The probe flow's own `syntheticProbe` flag is
         * passed through untouched - otherwise a real turn would quietly satisfy the probe confirmation that flow
         * requires. A named production seam for the same reason as [playbackSuppression]: `create` needs a real Android
         * `Context`, so the merge is driven from a JVM test here.
         */
        internal fun consentSource(probe: () -> RequestConsent): (ModelProfile) -> RequestConsent =
            { profile ->
                val consent = probe()
                consent.copy(text = true, image = consent.image || InputModality.IMAGE in profile.inputs)
            }

        /**
         * The wake detector's self-audio guard over the composition's one [HostCompositionRoot.playbackGate]
         * (Task 14b-fix item 1).
         *
         * A named production seam rather than an inline lambda in `create`: `create` provisions the chain **before**
         * the composition exists, so the guard takes the composition through [composition] and resolves the gate at
         * frame time - which is also what makes the identity testable. [create] hands exactly this function the holder
         * it later fills with the root, and a JVM test hands it an already-built composition; both read the same
         * exposed `playbackGate`, so `begin()` on the handle the UI exposes is the state the detector consults.
         *
         * The null branch keeps the gate **open** (no suppression) rather than closing it, because "the composition
         * is not built yet" is not a playback and must not drop legitimate wakes.
         */
        internal fun playbackSuppression(composition: () -> HostCompositionRoot?): () -> Boolean =
            { composition()?.playbackGate?.isPlaying == true }

        /**
         * Turns one detector emission into the event the host delivers (Task 14a item 3).
         *
         * Built here, and with the host's **current** identity, because that is the only identity `HostRuntime.onEvent`
         * accepts. Nothing else is added: `WakeDetected` and its `Capability.WakeWord` mapping already exist, so
         * arming the wake path needs a correct identity and nothing else - a new contract would have been the wrong
         * fix for "the event was dropped".
         *
         * `internal`, with the clock injected, so a test drives **this** builder rather than a copy of it
         * (Task 14a-fix item 6); the clock is the composition's own, which is what `HostRuntime` validates against,
         * so the removed `currentClockNanos()` accessor is not needed (item 4).
         */
        internal fun emitWakeDetected(host: HostRuntime, clock: MonotonicClock, word: String, confidence: Double) {
            val bounded = confidence.coerceIn(0.0, 1.0)
            // **Before the identity check.** This line is the only evidence in the whole system that a wake detector
            // fired, and logging it after the guard made "fired, but the host had no identity to deliver it under"
            // indistinguishable from "never fired" — the ambiguity a 2026-10-14 device investigation lost hours to.
            // The KDoc below has always claimed the line exists for that reason; this is the line catching up with it.
            // It carries the word and the bounded confidence and nothing else.
            P3Log.line("P3_WAKE word=$word confidence=$bounded")
            val identity = host.currentIdentity() ?: return
            val now = clock.nowNanos()
            host.onEvent(WakeDetected(
                EventEnvelope(identity.first, identity.second, "wake-${UUID.randomUUID()}", now, now),
                word,
                bounded,
            ))
        }

        /**
         * Turns one acoustic label transition into the event the host delivers (Task 4).
         *
         * Built here and with the host's **current** identity, exactly like [emitWakeDetected]: the label is the only
         * payload, so the line and the event carry no audio and no transcript. A label that arrives after the session
         * ended is dropped by `onEvent` rather than delivered under a generation that no longer exists.
         */
        internal fun emitAcoustic(host: HostRuntime, clock: MonotonicClock, label: AcousticLabel) {
            val identity = host.currentIdentity() ?: return
            val now = clock.nowNanos()
            // The label is a coarse category, never the audio that produced it.
            P3Log.line("P3_ACOUSTIC label=$label")
            host.onEvent(AcousticEvent(
                EventEnvelope(identity.first, identity.second, "acoustic-${UUID.randomUUID()}", now, now),
                label,
            ))
        }

        /**
         * The **arming gate**: the acoustic sink the chain is assembled with, which produces nothing unless
         * [ProactiveArming.enabled] is on (P3.2, spec §16.2).
         *
         * A named production seam rather than a lambda inside `create`, for the same reason as [playbackSuppression]:
         * the gate is the whole mechanism by which the switch works, so a JVM test drives **this** function instead of
         * a copy of it. The check happens before [emitAcoustic], so a disarmed composition emits no event *and* writes
         * no `P3_ACOUSTIC` line - nothing downstream can observe the label at all.
         *
         * `host` is a function because `create` provisions the chain before the composition's host holder is filled;
         * resolving it per label is also what makes a late label drop out through the host's own identity gate.
         */
        internal fun armedAcousticSink(host: () -> HostRuntime?, clock: MonotonicClock,
            arming: ProactiveArming): (AcousticLabel) -> Unit =
            { label -> if (arming.enabled.value) host()?.let { emitAcoustic(it, clock, label) } }

        /**
         * Turns one context change into the event the host delivers (Task 6).
         *
         * Only the **key names** reach the log: a value can be a foreground package or, in Stage B, a calendar title,
         * and neither belongs in a log line. The values themselves travel only inside the event, to a plugin the host
         * has already granted `Context`.
         */
        internal fun emitContextChanged(host: HostRuntime, clock: MonotonicClock, snapshot: ContextSnapshot) {
            val identity = host.currentIdentity() ?: return
            val now = clock.nowNanos()
            P3Log.line("P3_CONTEXT keys=${snapshot.values.keys.sorted().joinToString(",")}")
            host.onEvent(ContextChanged(
                EventEnvelope(identity.first, identity.second, "context-${UUID.randomUUID()}", now, now),
                snapshot.capturedAtNanos,
                snapshot.values,
            ))
        }

        /**
         * The arming gate for context, exactly as [armedAcousticSink] is for the acoustic labels: with 「主动辅助」 off
         * the composition produces no `ContextChanged`, so a plugin receives no context signal (spec §16.2, pre-flight
         * P4).
         */
        internal fun armedContextSink(host: () -> HostRuntime?, clock: MonotonicClock,
            arming: ProactiveArming): (ContextSnapshot) -> Unit =
            { snapshot -> if (arming.enabled.value) host()?.let { emitContextChanged(it, clock, snapshot) } }
    }
}

/** The SDK coordinate the shipping pairing resolves its uplink format for; see `PcmFormatEvidence`. */
private const val CXR_SDK_VERSION = "1.1.2"
private const val ENGINE_MANIFEST_ASSET = "engine-manifest.json"

/**
 * The debug-only import drop: `adb push` a locally produced bundle (P2/T17) here and a debug build picks it
 * up when the model store has no active bundle.
 *
 * Debug-only by construction ([provisionAudioChain] only reads it when `BuildConfig.DEBUG`), because a release
 * build must never activate model bytes that arrived from outside the App's own storage. **The manifest is not
 * always the App's bundled asset**: `EngineBundleProvisioning` reads a pushed `engine-manifest.json` as well, so a
 * bundle imported through the debug path is validated against the manifest it was pushed with - what limits the
 * trust is the debug-only gate above, not the manifest's origin.
 */
/**
 * edith-0.2 Phase B: the Laya graph.
 *
 * Resolved from the app's **internal** `filesDir`, which is the publisher's own constraint — `LayaEngine` reads its
 * assets from there and checks for them at construction, so making the location a setting would mean patching the
 * vendored host. The embedding model does **not** live there; see [EmbeddingModelDirectory].
 */
private const val LAYA_MAIN_GRAPH = "laya_ml_s256_embeds_wfp16.tflite"

/**
 * edith-0.2 Phase B (C4): where the EmbeddingGemma graph and its SentencePiece vocabulary are read from.
 *
 * These artifacts are **not** in the app's internal `filesDir`, and no code in this repository provisions them: they
 * were pushed to the external files directory by hand with adb, and the push command is recorded in
 * `docs/project/evidence/2026-10-13-phase-a-performance-k40.md`. The location is still a code decision rather than a
 * setting, because it is the difference between the plugin's hard dependency serving and refusing a turn, and a
 * wrong guess here (the invented `filesDir/engine-models`) is what made every turn refuse.
 */
internal object EmbeddingModelDirectory {
    /** Relative to `Context.getExternalFilesDir(null)`. */
    const val RELATIVE_PATH = "models/embeddinggemma"

    /** A directory name nothing creates. See [locate]. */
    private const val UNREACHABLE_PREFIX = "no-external-files-dir"

    /**
     * @param externalFilesDir `Context.getExternalFilesDir(null)`, or null when external storage is not mounted.
     * @param internalFilesDir `Context.getFilesDir()`, or null when there is no usable context at all — not
     *        hypothetical: the JVM rig builds the production composition root over a `ContextWrapper(null)`, where
     *        both read as null. Both parameters are nullable for that reason; routing a null through a non-null one
     *        threw `NullPointerException: getFilesDir(...) must not be null` out of `create()` and the app could not
     *        assemble, which is worse than a refusal.
     *
     *        The fallback is deliberately a path that cannot exist rather than the internal `filesDir`: falling back
     *        to a directory that might hold something else would answer "the model is here" with whatever happened to
     *        be there, and refusing the turn is the honest state when the artifacts are unreachable.
     */
    fun locate(externalFilesDir: File?, internalFilesDir: File?): File =
        if (externalFilesDir != null) {
            File(externalFilesDir, RELATIVE_PATH)
        } else {
            // `File("")` keeps the result relative when even the internal directory is absent; it still cannot exist,
            // which is the property that matters.
            File(File(internalFilesDir ?: File(""), UNREACHABLE_PREFIX), RELATIVE_PATH)
        }
}

internal object DebugImportDirectory {    const val STORE_DIRECTORY = "engine-models"
    const val IMPORT_DIRECTORY = "engine-models-import"

    fun store(context: Context) =
        EngineModelStore(context.applicationContext.noBackupFilesDir.toPath().resolve(STORE_DIRECTORY))

    fun source(context: Context): File =
        context.applicationContext.noBackupFilesDir.resolve(IMPORT_DIRECTORY)
}

/**
 * The engines a provisioned chain needs, as a seam.
 *
 * Production uses the defaults, which are the real sherpa factories over the App's own assets; a JVM unit test
 * cannot load the JNI library, so it substitutes stubs by overriding the factory methods and the branch
 * selection around them is still exercised. The wake detector's own VAD comes from the same validated bundle
 * and is deliberately a **separate instance** from the pipeline's: `WakeWordDetector` resets its VAD on the
 * speech→silence edge.
 *
 * A null `clock` means the default [wakeDetector] must not build a detector, because `WakeWordDetector` needs a
 * clock to honour its cooldown; tests override that method and never reach the default. The `suppressWhile`
 * argument is forwarded to the detector unchanged: it is the caller's state (the `PlaybackGate`), not the
 * factory's business, and this factory is the only place production constructs a detector.
 */
internal open class AudioChainEngines(
    internal val assets: android.content.res.AssetManager? = null,
    internal val clock: MonotonicClock? = null,
    internal val blobs: AndroidEncryptedBlobStore? = null,
    /**
     * The configured per-word cooldown, in nanoseconds, that the detector must honour.
     */
    internal val cooldownNanos: Long = 3_000_000_000L,
) {
    /** The VAD-only engine for the detector; null when it cannot be built from the bundle. */
    open fun vad(bundle: ValidatedModelBundle): VadEngine? = SherpaVadFactory.create(bundle).engine

    /**
     * The pipeline's engines loaded from the bundle, using the format the caller already resolved. The full
     * candidate is the only place the recognizer and the speaker extractor are loaded, and it agrees with the
     * VAD-only factory on the same validated bundle.
     */
    open fun asrCandidate(bundle: ValidatedModelBundle, verified: VerifiedPcm): CandidateEngines? {
        val candidate = SherpaEngineFactory.createCandidate(bundle, verified)
        return CandidateEngines(candidate.vad, candidate.asr,
            LocalSpeakerService(candidate.embedding, AndroidSpeakerProfileStore(requireNotNull(blobs)),
                // No attribution thresholds are configured in this phase, so every assessment is UNKNOWN - the
                // label the product rules require to be non-triggering. Enrolment, thresholds and the `Speaker`
                // capability are their own task; inventing them here would silently change attribution behaviour.
                SpeakerPolicy(minVoicedMillis = 1, selfThreshold = null, otherThreshold = null)))
    }

    /**
     * The detector over the packaged KWS assets, or null when the assets or the spotter are unavailable.
     *
     * The engine runs [KeywordSpotterWakeWordEngine.PHASE_DIVERSITY_STREAMS] staggered streams over one native
     * spotter: measured 2026-10-20, a single stream missed 8 of the 9 English alignment/prefix cases it was run on,
     * four streams hit all 11 probe cases but still missed one of twelve production injections, and eight streams
     * hit 12/12 in the probe and 7/7 in production (F-01). The model is loaded once; only its per-stream decoder
     * state is replicated.
     */
    open fun wakeDetector(bundle: ValidatedModelBundle, vad: VadEngine,
        emit: (word: String, confidence: Double) -> Unit,
        suppressWhile: () -> Boolean): WakeWordDetector? {
        val manager = assets ?: return null
        val timeSource = clock ?: return null
        val keywordAssets = KeywordAssetBundle.load(manager) ?: return null
        val port = SherpaKeywordSpotterFactory.create(keywordAssets, manager) ?: return null
        return WakeWordDetector(vad, KeywordSpotterWakeWordEngine(port, keywordAssets.keywordsArgument,
            streams = KeywordSpotterWakeWordEngine.PHASE_DIVERSITY_STREAMS,
            streamStrideFrames = KeywordSpotterWakeWordEngine.PHASE_DIVERSITY_STRIDE_FRAMES),
            emit = emit, clock = timeSource, cooldownNanos = cooldownNanos, suppressWhile = suppressWhile)
    }
}

/** The pipeline-side engines of one candidate; a holder rather than a `SherpaEngineCandidate` so tests can substitute. */
internal class CandidateEngines(val vad: VadEngine, val asr: StreamingAsrEngine, val speaker: SpeakerEngine)

/**
 * A wake detector together with the VAD-only engine it owns.
 *
 * Separate from the pipeline's VAD by contract (`WakeWordDetector` resets its VAD on the speech→silence edge),
 * and therefore separate in ownership too: closing the chain closes this VAD, while the pipeline closes its own
 * engines in `SpeechPipeline.close()`. Modelling that as one object means a caller cannot build a detector and
 * forget to release its engine.
 */
internal class WiredWakeDetector(val detector: WakeWordDetector, private val vad: VadEngine) : AutoCloseable {
    override fun close() {
        runCatching { detector.close() }
        runCatching { vad.close() }
    }
}

/**
 * The App's assembled audio chain (Task 11): the CXR ingress polled into the speech pipeline, with the pipeline's
 * transcripts exposed to plugins as the `Capability.Asr` provider.
 *
 * The chain is built from pieces that already exist - native engines from a validated bundle and the connection's
 * own [PcmIngress] - so nothing here reaches for a microphone or a network: PCM arrives over CXR, exactly as D33
 * requires it to stay in RAM.
 *
 * The ingress is resolved **lazily** because the CXR connection (and with it the ingress) is created on first
 * connect, after the environment that holds this chain has to exist. The loop is built on the first `start(epoch)`
 * - or on the first reported offer - and then reused.
 *
 * [onFault]/[onOffer] are the chain's public forwarders (Task 11b, Important 3). The ingress owner receives the
 * receiver's `PcmOffer` and cannot hand it to a private loop, so the chain is the object that turns it into the
 * loop's fault codes and passes the outcome on to diagnostics.
 */
internal class CxrAudioChain private constructor(
    private val ingress: () -> PcmIngress?,
    private val framer: PcmFramer,
    private val pipeline: SpeechPipeline,
    private val wake: WiredWakeDetector?,
    private val scheduler: DeadlineScheduler,
    private val clock: MonotonicClock,
    private val faultSink: (String) -> Unit,
    private val offerSink: (PcmOffer) -> Unit,
    val asrSession: AsrSession,
    /**
     * The detector's real emission sink, as the composition installs it.
     *
     * Exposed because it is the only seam the composition's wiring can be exercised through without a microphone or a
     * keyword-spotting model: a JVM test can call it exactly as the detector would and observe what reaches
     * `host.onEvent`. The identity a wake carries is the emitter's business - the composition resolves it from the
     * host at emission time - so this seam stays `(word, confidence)`, exactly like `WakeWordDetector.emit`.
     *
     * Assignable because the detector needs it at **construction** while the chain that owns it is built first; the
     * production factory assigns it immediately, before the chain can poll a single frame.
     */
    var wakeEmit: (word: String, confidence: Double) -> Unit = { _, _ -> },
    /**
     * The acoustic label sink, as the composition installs it (Task 4).
     *
     * Same shape and same reason as [wakeEmit]: the pipeline is built before the chain that owns it, so the
     * per-frame signal reads this property through a holder rather than capturing a value. Assignable, so a JVM test
     * can call it exactly as the labeler would - and the production factory assigns it before a single frame can be
     * polled.
     */
    var acousticEmit: (AcousticLabel) -> Unit = {},
    /** P5 (spec §A1): the audio segment capture fed from the pipeline's VAD signal. */
    val audioCapture: AudioSegmentCapture = AudioSegmentCapture(),
    /** P5 (C3 fix): the bridge ref shared with the pipeline's emit callback. Set after construction. */
    internal var bridgeRef: java.util.concurrent.atomic.AtomicReference<AudioSegmentBridge?>? = null,
) : AutoCloseable {

    private val lock = Any()
    private var loop: AudioIngressLoop? = null

    /** Outcomes observed before the first loop existed; drained into it as it is built. */
    private val pendingFaults = ArrayDeque<String>()
    private val pendingOffers = ArrayDeque<PcmOffer>()

    /**
     * The host-facing input port; the host starts it for an epoch exactly as it starts any other input.
     *
     * `stop` reads the loop through the lock rather than resolving one, so stopping cannot create a loop that
     * was never started.
     */
    val input: InputPort = object : InputPort {
        override fun start(epoch: InputEpoch) { resolved()?.start(epoch) }
        override fun stop(epoch: InputEpoch) { synchronized(lock) { loop }?.stop(epoch) }
    }

    /**
     * Reports a fault of the running loop, or one observed before the ingress existed.
     *
     * A forwarder rather than a direct call into the loop, because `PcmIngress` is owned by the transport and
     * its faults are real whether or not this chain has a loop yet. The buffering decision is made inside the
     * same lock the loop is built under, so a fault racing the first `start` cannot be lost.
     */
    fun onFault(code: String) {
        resolved(fault = code)
    }

    /** Reports what the receiver did with one offered datagram; see [AudioIngressLoop.onOffer]. */
    fun onOffer(offer: PcmOffer) {
        offerSink(offer)
        resolved(offer = offer)
    }

    /**
     * The loop for the current ingress, built at most once, with any outcome that arrived before it drained
     * into it.
     *
     * The `offer`/`fault` arguments are handled here rather than by the callers so that "is there a loop" and
     * "buffer this" are one atomic decision: a caller that checked and then buffered could interleave with the
     * call that builds the loop and drains the buffer, losing the outcome for good. Buffering only ever happens
     * before the first ingress exists (the CXR connection is built lazily), and the next call - a later offer,
     * or `start` - drains it.
     */
    private fun resolved(offer: PcmOffer? = null, fault: String? = null): AudioIngressLoop? =
        synchronized(lock) {
            val current = loop ?: ingress()?.let { source ->
                AudioIngressLoop(source, framer, pipeline, wake?.detector, scheduler, clock,
                    onFault = faultSink).also { built ->
                    loop = built
                    while (pendingOffers.isNotEmpty()) built.onOffer(pendingOffers.removeFirst())
                    while (pendingFaults.isNotEmpty()) built.reportFault(pendingFaults.removeFirst())
                }
            }
            if (current == null) {
                offer?.let { pendingOffers += it }
                fault?.let { pendingFaults += it }
            } else {
                offer?.let(current::onOffer)
                fault?.let(current::reportFault)
            }
            current
        }

    override fun close() {
        runCatching { wake?.close() }
        runCatching { pipeline.close() }
    }

    companion object {
        /**
         * Wires one PCM stream to both consumers and one transcript stream to plugins.
         *
         * [wake] is nullable on purpose: with no keyword-spotting assets, or no VAD that can be built for the
         * detector, there is no detector and the ruled fallback is to leave it unwired (the composition reports
         * `WAKE_UNAVAILABLE`) rather than build a second ASR engine over the same ~200 MB model. The pipeline
         * runs either way.
         */
        fun assemble(
            ingress: () -> PcmIngress?,
            framer: PcmFramer,
            vad: VadEngine,
            asr: StreamingAsrEngine,
            speaker: SpeakerEngine,
            wake: WiredWakeDetector?,
            scheduler: DeadlineScheduler,
            clock: MonotonicClock,
            emit: (HostEvent) -> Unit,
            cancelTurn: (String) -> Unit = {},
            onFault: (String) -> Unit = {},
            onOffer: (PcmOffer) -> Unit = {},
            /**
             * The detector's emission sink, as the composition installs it; see [wakeEmit].
             *
             * Defaulted inert for the harnesses that build a chain without a live host, because a chain that cannot
             * deliver a wake must not pretend it did.
             */
            wakeEmit: (word: String, confidence: Double) -> Unit = { _, _ -> },
            /**
             * The acoustic label sink, as the composition installs it; see [CxrAudioChain.acousticEmit]. Defaulted
             * inert for the harnesses that build a chain without a live host.
             */
            acousticEmit: (AcousticLabel) -> Unit = {},
            /** P5 (I2 fix): playback gate for echo suppression during TTS. */
            playbackGate: PlaybackGate = PlaybackGate(),
        ): CxrAudioChain {
            // The session needs the pipeline and the pipeline's emit needs the session: the holder breaks the cycle
            // so both the host's delivery and the plugin-facing provider see every revision, in order.
            val holder = arrayOfNulls<SherpaAsrSession>(1)
            // The labeler is derived from these same frames and reaches the chain's sink through a second holder, for
            // the same construction-order reason as the wake detector's emission.
            val chainHolder = arrayOfNulls<CxrAudioChain>(1)
            val labeler = AcousticLabeler()
            // P5 (spec §A1): bounded audio segment capture, fed from the pipeline's per-frame VAD signal.
            val audioCapture = AudioSegmentCapture()
            // P5 (C3 fix): bridge holder for sealing audio segments on transcript finalization.
            // The bridge is set after construction via CxrAudioChain.bridgeHolderRef.
            val bridgeRef = java.util.concurrent.atomic.AtomicReference<AudioSegmentBridge?>(null)
            val pipeline = SpeechPipeline(vad, asr, speaker,
                emit = { event ->
                    holder[0]?.onEvent(event); emit(event)
                    // Seal the audio segment when a final transcript arrives.
                    if (event is com.sheen.rokid.contracts.TranscriptEvent && event.revision.final) {
                        bridgeRef.get()?.sealForTurn(event.revision.turnId)
                    }
                }, cancelTurn = cancelTurn,
                onFrameSignal = frameSignal@{ frame, speech ->
                    // P5 (I2 fix, spec §S4): suppress ingestion during TTS playback to prevent echo self-trigger.
                    // When the gate is closed, discard the frame entirely — don't feed VAD, ASR, labeler, or capture.
                    if (playbackGate.isPlaying) return@frameSignal
                    labeler.accept(frame, speech)?.let { label -> chainHolder[0]?.acousticEmit?.invoke(label) }
                    // Feed the capture with PCM bytes and VAD speech flag.
                    val pcmBytes = ByteArray(frame.samples.size * 2)
                    frame.samples.forEachIndexed { i, sample ->
                        val value = (sample * 32768f).toInt().coerceIn(-32768, 32767)
                        pcmBytes[i * 2] = value.toByte()
                        pcmBytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    audioCapture.offer(pcmBytes, speech)
                },
                // The pipeline's own state is dropped on a gap/format change/fault/close; the labeler's must go with
                // it, or the first transition after the break is compared against a pre-break verdict.
                onReset = { labeler.reset(); /* P5: reset capture state on pipeline reset */ })
            val session = SherpaAsrSession(pipeline)
            holder[0] = session
            return CxrAudioChain(ingress, framer, pipeline, wake, scheduler, clock, onFault, onOffer, session,
                wakeEmit, acousticEmit, audioCapture).also {
                    chainHolder[0] = it
                    it.bridgeRef = bridgeRef
                }
        }
    }
}

/**
 * The engine-bundle import seam `provisionAudioChain` depends on, so a unit test can pin the branch selection
 * (attempted in debug, skipped in release, success activates, failure activates nothing) without a device.
 */
internal interface EngineBundleProvisioner {
    /** @throws ContractError.CapabilityMissing `ENGINE_MISSING` for any failed verification. */
    fun importBundle(root: File): String
}

/** The production provisioner over the App's own store. */
internal class StoreBundleProvisioner(private val store: EngineModelStore) : EngineBundleProvisioner {
    private val provisioning = EngineBundleProvisioning(store, EngineManifestLoader)
    override fun importBundle(root: File): String = provisioning.importBundle(root)
}

/**
 * The platform half of the provisioning: resolve the App's model store and manifest asset, then hand both to
 * [provisionAudioChainFrom].
 *
 * This is the only place the `Context` is touched, so a broken/absent context (a release build with no assets,
 * a test double) fails closed as "no chain" here rather than as an exception inside the composition - the same
 * result an absent bundle produces, with the class named in the log.
 */
private fun provisionAudioChain(
    context: Context,
    clock: MonotonicClock,
    scheduler: DeadlineScheduler,
    ingress: () -> PcmIngress?,
    emit: (HostEvent) -> Unit,
    cancelTurn: (String) -> Unit,
    allowDebugImport: Boolean,
    blobs: AndroidEncryptedBlobStore,
    onFault: (String) -> Unit,
    onOffer: (PcmOffer) -> Unit,
    wakeEmit: (word: String, confidence: Double) -> Unit,
    acousticEmit: (AcousticLabel) -> Unit,
    cooldownNanos: Long,
    suppressWhile: () -> Boolean,
    playbackGate: PlaybackGate = PlaybackGate(),
): CxrAudioChain? = try {
    val application = requireNotNull(context.applicationContext) { "APPLICATION_CONTEXT_UNAVAILABLE" }
    val store = DebugImportDirectory.store(application)
    val manifest = application.assets.open(ENGINE_MANIFEST_ASSET).use(EngineManifestLoader::load)
    provisionAudioChainFrom(
        store = ModelStoreBundles(store),
        provisioner = StoreBundleProvisioner(store),
        importRoot = DebugImportDirectory.source(application),
        manifest = manifest,
        allowDebugImport = allowDebugImport,
        clock = clock,
        scheduler = scheduler,
        ingress = ingress,
        emit = emit,
        cancelTurn = cancelTurn,
        engines = AudioChainEngines(application.assets, clock, blobs, cooldownNanos),
        suppressWhile = suppressWhile,
        onFault = onFault,
        onOffer = onOffer,
        wakeEmit = wakeEmit,
        acousticEmit = acousticEmit,
        playbackGate = playbackGate,
    )
} catch (unavailable: Exception) {
    android.util.Log.i("EdithHost", "audio chain unavailable: ${unavailable.javaClass.name}: ${unavailable.message}")
    null
}

/**
 * Builds the audio chain from a validated engine bundle, or returns `null` when the delivery build cannot honestly
 * support one.
 *
 * Both preconditions are real facts about the device, not optimism:
 * - the uplink format must be the verified one for the shipping pairing (`PcmFormatEvidence`), otherwise a framer
 *   would be built on an assumed geometry and the ASR would return confident nonsense;
 * - a validated bundle must be active or recoverable in the App's model store, imported through
 *   `EngineBundleProvisioning` (or the P2/T17 pipeline) with every size and digest checked.
 *
 * **The debug-only import (Task 11b, Important 2).** With neither an active **nor a recoverable** bundle (Task 15c
 * D3 made `recover` the second check, so a healthy bundle is reused rather than re-imported), a debug build attempts
 * `EngineBundleProvisioning.importBundle` on [DebugImportDirectory.IMPORT_DIRECTORY]. That is what makes the
 * ratified conditional `Asr` advertisement reachable: a bundle pushed there for development is activated exactly
 * as one imported through the P2/T17 path, or nothing happens at all. A release build never reads that
 * directory, so an external bundle cannot become the product's audio engine.
 *
 * **The wake detector (Task 11b, Important 1).** When the KWS assets are packaged and a VAD-only engine can be
 * built from the same bundle, the detector is wired with its **own** VAD (the pipeline keeps the candidate's),
 * because `WakeWordDetector` resets its VAD on the speech→silence edge. Building the second VAD from the `vad`
 * role costs one vad model handle, not a second recognizer; the pipeline's ASR model is never loaded twice.
 * Either piece missing keeps today's behaviour: `WAKE_UNAVAILABLE`.
 *
 * **The self-audio gate (Task 14b).** [suppressWhile] is handed to the detector through
 * [AudioChainEngines.wakeDetector] rather than applied here, so the seam is the factory's - the same place the
 * detector is built - and the gate is evaluated per frame. P3.1's gate is open for the whole phase; see
 * `PlaybackGate`.
 *
 * Called on the composition thread, so a build that has an imported bundle pays the validation (SHA-256 over every
 * artifact) and native engine load here, at startup. That is a known cost of keeping the ears always on; if startup
 * latency becomes the constraint, the fix is to build the chain after the first connect and re-`start` the session,
 * which is a lifecycle change and not free.
 */
internal fun provisionAudioChainFrom(
    store: EngineBundleStore,
    provisioner: EngineBundleProvisioner,
    importRoot: File,
    manifest: EngineManifest,
    allowDebugImport: Boolean,
    clock: MonotonicClock,
    scheduler: DeadlineScheduler,
    ingress: () -> PcmIngress?,
    emit: (HostEvent) -> Unit,
    cancelTurn: (String) -> Unit,
    engines: AudioChainEngines = AudioChainEngines(),
    /**
     * The detector's self-audio guard (Task 14b): true while the App itself is producing audio, so its own playback
     * cannot be re-detected as a wake. Evaluated per frame by `WakeWordDetector`; see `PlaybackGate` for why it is
     * open (and inert) for the whole of P3.1.
     */
    suppressWhile: () -> Boolean = { false },
    onFault: (String) -> Unit = {},
    onOffer: (PcmOffer) -> Unit = {},
    /**
     * The detector's emission sink: the composition turns `(word, confidence)` into a `WakeDetected` tagged with the
     * host's current identity and hands it to `HostRuntime.onEvent`.
     *
     * Defaulted inert so the existing harnesses keep compiling and a chain built without a live host emits nothing
     * rather than throwing into the audio loop's tick.
     */
    wakeEmit: (word: String, confidence: Double) -> Unit = { _, _ -> },
    /**
     * The acoustic label sink the audio chain reports transitions to (Task 4); the composition turns it into an
     * `AcousticEvent` tagged with the host's current identity.
     */
    acousticEmit: (AcousticLabel) -> Unit = {},
    /** P5 (I2 fix): the playback gate for echo suppression during TTS. */
    playbackGate: PlaybackGate = PlaybackGate(),
): CxrAudioChain? = try {
    val verified = PcmFormatEvidence.resolve(CxrGlassesTarget.GLASSES_FIRMWARE, CXR_SDK_VERSION)
    if (verified == null) {
        android.util.Log.i("EdithHost", "FORMAT_UNVERIFIED: no verified uplink format, the ASR chain stays unwired")
        null
    } else {
        val bundle = activateBundle(store, provisioner, importRoot, allowDebugImport, manifest)
        if (bundle == null) {
            android.util.Log.i("EdithHost", "ENGINE_MISSING: no validated engine bundle, the ASR chain stays unwired")
            null
        } else {
            // The framing comes from the manifest the bundle was validated against: a bundle is imported or
            // recovered through this same manifest, so the two cannot describe different geometries.
            val frameMillis = manifest.frameMillis
            val candidate = engines.asrCandidate(bundle, verified)
            if (candidate == null) {
                android.util.Log.i("EdithHost", "ENGINE_MISSING: the ASR candidate could not be built from the " +
                    "validated bundle, the ASR chain stays unwired")
                null
            } else {
                try {
                    // The detector's emission sink cannot be attached after construction (`WakeWordDetector.emit` is
                    // a constructor value), so it reads the chain's sink through this holder. The chain is built
                    // immediately below, before any frame can be polled.
                    val chainHolder = arrayOfNulls<CxrAudioChain>(1)
                    // The detection sink is installed as an indirection through the chain's `wakeEmit`, so the
                    // detector and the chain are not circular: the chain exists first, and this lambda reads the sink
                    // the chain currently holds. Building the detector with a no-op and patching it afterwards is
                    // what the old wiring did, and `WakeWordDetector.emit` being a constructor value made it silent.
                    //
                    // A detector needs its own VAD (it resets it on the speech→silence edge), so it is built from the
                    // same bundle's vad role. That is the only extra engine the wake path loads: one more vad handle,
                    // never a second recognizer. When there is no KWS asset bundle the engine is closed again at once
                    // and the chain runs ASR-only, which is the ruled fallback.
                    val wakeVad = engines.vad(bundle)
                    val wake = wakeVad?.let { vad ->
                        val detector = engines.wakeDetector(bundle, vad,
                            emit = { word, confidence -> chainHolder[0]?.wakeEmit(word, confidence) },
                            suppressWhile = suppressWhile)
                        if (detector == null) runCatching { vad.close() }
                        detector?.let { WiredWakeDetector(it, vad) }
                    }
                    if (wake == null) {
                        android.util.Log.i("EdithHost", "WAKE_UNAVAILABLE: the keyword-spotting path is not " +
                            "wired (no packaged KWS assets, or no VAD/detector could be built from the bundle); " +
                            "the explicit manual trigger is the only arming path")
                    }
                    CxrAudioChain.assemble(ingress = ingress, framer = PcmFramer(verified, frameMillis),
                        vad = candidate.vad, asr = candidate.asr, speaker = candidate.speaker,
                        wake = wake, scheduler = scheduler, clock = clock, emit = emit, cancelTurn = cancelTurn,
                        onFault = onFault, onOffer = onOffer, wakeEmit = wakeEmit,
                        acousticEmit = acousticEmit, playbackGate = playbackGate).also { chainHolder[0] = it }
                } catch (unavailable: Throwable) {
                    runCatching { candidate.vad.close() }
                    runCatching { candidate.asr.close() }
                    runCatching { candidate.speaker.close() }
                    throw unavailable
                }
            }
        }
    }
} catch (unavailable: Exception) {
    // The class, not only the message: a wiring bug (a missing asset, a null) and an absent bundle otherwise
    // produce the same "audio chain unavailable" line, and only one of them is a device state.
    android.util.Log.i("EdithHost", "audio chain unavailable: ${unavailable.javaClass.name}: ${unavailable.message}")
    null
}

/**
 * The bundle-store seam [activateBundle] reads.
 *
 * A named seam rather than the concrete `EngineModelStore` so a JVM test can pin the reuse-before-import rule
 * (active, then recoverable, then import) with a fake store and a recording provisioner, without a device or a
 * ~230 MiB bundle on disk. The two calls are exactly the ones the composition makes, in the order it makes them.
 */
internal interface EngineBundleStore {
    /** The bundle this process already validated, or null. */
    fun active(): ValidatedModelBundle?

    /** The bundle a previous process activated, revalidated against [manifest], or null. */
    fun recover(manifest: EngineManifest): ValidatedModelBundle?
}

/** The production [EngineBundleStore] over the App's own model store. */
internal class ModelStoreBundles(private val store: EngineModelStore) : EngineBundleStore {
    override fun active(): ValidatedModelBundle? = store.active()
    override fun recover(manifest: EngineManifest): ValidatedModelBundle? = store.recover(manifest)
}

/**
 * The bundle the chain is built from, reusing a validated one before importing the debug directory's.
 *
 * `store.active()` first, because an already-validated bundle is authoritative; then `store.recover(manifest)`, which
 * revalidates a bundle a previous process wrote and whose in-memory handle this process does not have; **only then**
 * the debug import (and only in a debug build). A refusal anywhere is `ENGINE_MISSING` - never a silently weaker
 * bundle.
 *
 * **The order is the fix (Task 15c D3).** Importing before `recover` re-copied the whole bundle into a fresh
 * directory on every cold start and left the previous copy in place: the device evidence (2026-10-09) showed two
 * different `ENGINE_IMPORTED: ACTIVE:…` ids across two runs, ~235 MB each, plus the import directory. Checking the
 * recoverable bundle first makes a healthy bundle reused instead of re-imported; the import stays the last resort for
 * a store that genuinely has nothing, which is the only case the debug drop is for.
 */
internal fun activateBundle(
    store: EngineBundleStore,
    provisioner: EngineBundleProvisioner,
    importRoot: File,
    allowDebugImport: Boolean,
    manifest: EngineManifest,
): ValidatedModelBundle? {
    // The reuse paths are logged as well as the import: without a line for them, a device run cannot tell "the
    // healthy bundle was reused" from "no bundle was found at all" - both used to be silent (Task 15b, concern 2).
    store.active()?.let { bundle ->
        android.util.Log.i("EdithHost", "ENGINE_REUSED: ACTIVE:${bundle.directory}")
        return bundle
    }
    runCatching { store.recover(manifest) }.getOrNull()?.let { bundle ->
        android.util.Log.i("EdithHost", "ENGINE_REUSED: RECOVERED:${bundle.directory}")
        return bundle
    }
    if (!allowDebugImport) return null
    val imported = runCatching { provisioner.importBundle(importRoot) }
    android.util.Log.i("EdithHost", imported.fold(
        onSuccess = { "ENGINE_IMPORTED: $it" },
        onFailure = { "ENGINE_IMPORT_FAILED: ${it.javaClass.name}: ${it.message}" }))
    return imported.getOrNull()?.let { store.active() }
}

/** A composition that was not wired for lock-screen unlock: the PIN is never configured. */
private object NoLockPinStore : LockPinStore {
    override fun hasPin(): Boolean = false
    override fun read(): CharArray? = null
    override fun save(pin: String) = Unit
    override fun clear() = Unit
}
