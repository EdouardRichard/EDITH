package com.sheen.rokid.phone.app
import com.sheen.rokid.phone.ui.profile.ProfileListScreen
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.AuditLogView
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.MemoryLine
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.MemoryLogView
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.NoInterceptionUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm
import com.sheen.rokid.phone.session.SessionService
import com.sheen.rokid.phone.session.SessionPermissions
import com.sheen.rokid.phone.session.SessionViewModel
import com.sheen.rokid.phone.lock.LockPinValidator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.phone.model.ModelReadiness
import com.sheen.rokid.phone.model.ModelStartGate
import com.sheen.rokid.phone.model.ProvisioningState
import com.sheen.rokid.phone.ui.PhoneDetailPage
import com.sheen.rokid.phone.ui.PhoneScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    companion object {
        /** The intent action the confirmation notification (and its full-screen intent) uses to raise this page. */
        const val ACTION_SHOW_CONFIRMATION = "edith.phone.SHOW_CONFIRMATION"
    }

    /**
     * The composition, only once the page has entered the ready UI. 0.4 Task 3 / spec §6.3: while the bundled models
     * are preparing or failed this stays null, so no lifecycle callback or permission result can build a composition
     * before the readiness gate is passed.
     */
    private var attachedRoot: HostCompositionRoot? = null
    private var pendingPlugin: String? = null

    /**
     * 0.4 Task 6 (`D199`): whether the start being permission-gated was a detached one.
     *
     * The permission result arrives after the dialog, when the gate may in principle have changed; the mode that
     * **asked** is the one whose grant must be checked, so it is remembered with the pending start rather than
     * re-derived from a possibly newer gate.
     */
    private var pendingDetached = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val selected = pendingPlugin
        val detached = pendingDetached
        pendingPlugin = null
        pendingDetached = false
        // The mode decides **which** permission can refuse the start, and the refusal is the same code the service
        // and composition would produce, so the visible reason cannot disagree with the gate.
        val refusal = SessionPermissions.startRefusal(detached, Build.VERSION.SDK_INT) { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (refusal != null) {
            attachedRoot?.error(refusal)
        } else if (selected != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            // Only now, after the grant, may a foreground service be started (spec §4.5).
            SessionService.startFromUi(this, selected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Owner directive 2026-10-10: a confirmation must reach the user. Over a locked or dark screen the notification's
        // full-screen intent raises this page, and these two flags are what let it appear over the keyguard and light
        // the panel - the question's own card is then on screen and answers through the usual path.
        if (intent?.action == ACTION_SHOW_CONFIRMATION) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            raisedForConfirmation = true
        }
        val app = application as EdithApplication
        if (ModelStartGate.refusal(app.modelReadiness.state.value) != null) {
            // No composition, no ViewModel, no CXR attach: the only thing this page can honestly do while the ~1 GiB
            // bundle is being materialized is show progress (or the failure and a retry). Ready re-enters `onCreate`
            // through `recreate()`, which then takes the normal path.
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF126B50), secondary = Color(0xFF405B79))) {
                    ModelPreparationScreen(
                        readiness = app.modelReadiness,
                        onRetry = { app.retryModelProvisioning() },
                        onReady = { recreate() },
                    )
                }
            }
            return
        }
        enterReadyUi(app)
    }

    private fun enterReadyUi(app: EdithApplication) {
        // W1 (F17/F18): the vendor SDK's `connect` needs an Activity, and only this page has one. Handing it over here
        // is what lets the session start path (which runs inside `SessionService`) establish the CXR link itself
        // instead of depending on one UI combination happening to be composed. Held weakly by the composition.
        val root = app.composition
        attachedRoot = root
        root.attachCxrActivity(this)
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(root) as T
        })[SessionViewModel::class.java]
        val profileViewModel = if (root.profileTester != null) ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(root.profileViewModel()) as T
        })[ProfileViewModel::class.java] else null
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF126B50), secondary = Color(0xFF405B79))) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val pending = state.pendingConfirmation
                // Owner directive 2026-10-10: an explicit countdown instead of a sentence about what silence means, and
                // the question closes itself when the count runs out (the plugin's gate settles it as a denial at the
                // same moment, which clears `pendingConfirmation` behind it).
                val remainingSeconds = rememberRemainingSeconds(pending?.requestId, pending?.expiresAtMillis ?: 0L)
                // User directive 2026-10-12: always show the confirmation as a modal dialog, never as an inline card.
                // The previous `raisedForConfirmation` gate meant the dialog only appeared when the system raised the
                // page via ACTION_SHOW_CONFIRMATION; when the user was already in the app, only the below-the-fold card
                // showed. Now the dialog is the sole confirmation surface regardless of how the page got here.
                if (pending != null && remainingSeconds > 0L) {
                    AlertDialog(
                        onDismissRequest = keepConfirmationOnDismissRequest,
                        title = { Text("需要你确认的动作") },
                        text = { Text(pending.summary) },
                        // All three buttons travel in **one** slot: Material3 re-flows `confirmButton` against
                        // `dismissButton` as their widths change, which is what made the row jump between one and two
                        // lines as the countdown shrank (user report 2026-10-11). One explicit Row, one arrangement -
                        // 拒绝 / 急停 / 允许 - and a Row does not wrap, so the arrangement is fixed by construction.
                        // Measured on the K40 (2026-10-11): the declared [CONFIRM_BUTTON_WIDTH] is **clamped** by what
                        // the row has left, so what really holds the geometry still is that the Allow button absorbs
                        // exactly that remainder - a quantity the countdown cannot change (the other two buttons are
                        // content-sized). The button containers' bounds were identical at `允许 (110)` and
                        // `允许 (87)`.
                        confirmButton = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(onClick = {
                                    root.answerConfirmation(false)
                                }) { Text("拒绝") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = {
                                    root.emergencyStop()
                                }) { Text("急停") }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    modifier = Modifier.width(CONFIRM_BUTTON_WIDTH),
                                    onClick = {
                                        root.answerConfirmation(true)
                                    },
                                ) { Text("允许 ($remainingSeconds)") }
                            }
                        },
                        dismissButton = {},
                    )
                }
                // 2026-10-22 (owner): the persistent top banner was removed. The mode is still enforced and its risk
                // copy still precedes the switch; the banner repeated it on every page and the owner asked for it to
                // go. The settings page remains the single place that states the mode and how to leave it.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        PhoneScreen(state, root, viewModel, profileViewModel, ::requestStart,
                            { SessionService.commandFromUi(this@MainActivity, it) }, notificationsGranted(),
                            { root.storedSettings() }, { settings -> root.saveSmartSettings(settings) },
                            remainingSeconds)
                    }
                }
            }
        }
    }

    /**
     * True while this page is front for a confirmation question, i.e. the composition raised it (see
     * `HostCompositionRoot.raiseConfirmationUi`). Snapshotted so the dialog above appears instead of the
     * below-the-fold card.
     */
    private var raisedForConfirmation by mutableStateOf(false)

    /**
     * P3.3 (§17.4): **visibility**, reported to the composition so a confirmation question can reach the notification
     * shade exactly when this page cannot be seen. `onStart`/`onStop` rather than `onResume`/`onPause`: a Shizuku grant
     * dialog or the MIUI install prompt pauses the Activity without hiding it, and a notification asking about a
     * question that is on screen is noise.
     */
    override fun onStart() {
        super.onStart()
        attachedRoot?.uiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        attachedRoot?.uiVisible(false)
    }

    /**
     * The confirmation popup, arriving at a page that is already running (`SINGLE_TOP`): same flags as in [onCreate],
     * because Android delivers the raise here rather than through a fresh instance.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_SHOW_CONFIRMATION) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            raisedForConfirmation = true
        }
    }

    private fun requestStart(pluginId: String) {
        // 0.4 Task 6 (`D199`/spec §4.2): the **mode** chooses the requested permissions. Detached mode asks for the
        // phone microphone and deliberately not Bluetooth; glasses mode keeps the existing Bluetooth request. The
        // dialog is only skipped when nothing is missing, so a foreground service is never started before the result.
        val detached = attachedRoot?.detachGate?.enabled?.value == true
        val requested = SessionPermissions.requiredForStart(detached, Build.VERSION.SDK_INT)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (requested.isEmpty()) SessionService.startFromUi(this, pluginId)
        else { pendingPlugin = pluginId; pendingDetached = detached; permissions.launch(requested.toTypedArray()) }
    }

    /**
     * The CXR AI-App authorization prompt is started by the SDK through
     * `requestAuthorization(activity, ...)`, so its result arrives here. It must reach the transport
     * (the only SDK access path) instead of being dropped, otherwise the token never completes.
     */
    @Deprecated("SDK authorization uses startActivityForResult; the transport is the only consumer")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        attachedRoot?.onCxrAuthorizationResult(resultCode, data)
    }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * 0.3 Phase 4B (`D189`): the persistent in-App banner was removed by the owner on 2026-10-22.
 *
 * The mode's risk copy stays on the settings page, before the switch and in the second confirmation; the banner
 * repeated it above every page, which the owner asked to stop. See [NoInterceptionUi] for the remaining copy.
 */

/**
 * 0.4 Task 3 / spec §6.3: the only screen while the bundled models are not ready.
 *
 * It deliberately shows **nothing from the composition** - the composition does not exist yet. Preparing shows the
 * one honest fact (first start copies about 1.0 GiB); failed shows a stable reason and a retry. A READY transition
 * re-enters the Activity so the normal UI and its composition are built from the main thread exactly once.
 */
@Composable
private fun ModelPreparationScreen(
    readiness: ModelReadiness,
    onRetry: () -> Unit,
    onReady: () -> Unit,
) {
    val state by readiness.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is ProvisioningState.Ready) onReady()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val current = state) {
            ProvisioningState.Preparing -> {
                Text("正在准备本地模型", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "首次启动需要把内置模型复制到本机（约 1.0 GiB），请保持充足电量与存储空间。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ProvisioningState.Failed -> {
                Text("本地模型准备失败", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(modelFailureText(current.reason), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text("重试") }
            }

            is ProvisioningState.Ready -> Unit
        }
    }
}

/** Stable failure code to short Chinese copy; the code itself is safe to show (no path, no content). */
private fun modelFailureText(reason: String): String = when (reason) {
    "MODELS_NO_SPACE" -> "设备存储空间不足，需要约 1.0 GiB 可用空间。"
    "MODELS_ASR_FAILED" -> "语音识别模型准备失败。"
    "MODELS_LAYA_FAILED" -> "本地决策模型准备失败。"
    "MODELS_EMBEDDING_FAILED" -> "本地向量模型准备失败。"
    else -> "模型准备失败（$reason）。"
}

/**
 * R109 (user report 2026-10-20, pocket false touch): a confirmation is settled **only** by its 拒绝 / 急停 / 允许
 * buttons. Material3's `AlertDialog` calls `onDismissRequest` for a tap outside the dialog (on the scrim) and for
 * system BACK; in a pocket either happens by accident, which is how the question used to vanish unasked. This handler
 * therefore does **nothing**: the dialog stays composed and the pending question stays pending, unanswered on both
 * the phone and the glasses. The only non-button exit is the countdown reaching zero, i.e. the plugin gate's own
 * 120 s denial delivered through `hideConfirmation` - existing policy, not a user cancel.
 */
private val keepConfirmationOnDismissRequest: () -> Unit = {}

/**
 * The countdown lives on the Allow button (owner directive 2026-10-10), so its **geometry** must not move: the label
 * shrinks from `允许 (120)` to `允许 (9)` once a second.
 *
 * Honest scope of this number, measured on the K40 (2026-10-11): the dialog's row has less space than this asks for, so
 * the declared width is **clamped** by the incoming constraint (`Modifier.width` enforces it) - the button rendered
 * 284 px (≈103 dp at 440 dpi) rather than 385 px. It is kept as the declared upper bound (it binds whenever the row is
 * wide enough, e.g. a wider dialog), but the invariant the user reported is carried by the single-slot Row: the Allow
 * button takes the row's remainder, and the two content-sized buttons beside it never change.
 */
private val CONFIRM_BUTTON_WIDTH = 140.dp

/**
 * The seconds left on the live question.
 *
 * Owner directive 2026-10-10: the user reads an explicit countdown, and the question closes itself when it reaches
 * zero - at that same moment the plugin's gate settles it as a denial, which clears `pendingConfirmation` and takes
 * both the card and the dialog with it. This ticker is only what the user *sees*; the gate remains the authority.
 */
@Composable private fun rememberRemainingSeconds(requestId: String?, expiresAtMillis: Long): Long {
    var remaining by remember(requestId) { mutableStateOf(secondsLeft(expiresAtMillis)) }
    LaunchedEffect(requestId) {
        remaining = secondsLeft(expiresAtMillis)
        while (remaining > 0L) {
            delay(250)
            remaining = secondsLeft(expiresAtMillis)
        }
    }
    return remaining
}

private fun secondsLeft(expiresAtMillis: Long): Long =
    ((expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)

/**
 * The minimal settings entry point (Task 14b): the values the user can set, plus the inline errors.
 *
 * Everything that can be validated lives in [SmartSettingsForm] (JVM-tested); this function only holds the text
 * fields and prints the errors, so the untested part is wiring. Two facts the copy must state truthfully
 * (Task 14b-fix item 4): the composition is a process-lifetime singleton and reads the settings **once** in
 * `HostCompositionRoot.create`, so only a full App restart re-reads them - restarting a session does not; and `L1`
 * is the ceiling the phase ships with while `L3`/`L4` are an explicit user choice.
 *
 * edith-0.3 Phase 2 Task 10 adds the one exception: 「感知截图」 is a **live** gate applied by a successful save, so
 * its copy says "immediately" while everything else keeps the restart sentence. The risk paragraph next to it is the
 * user's plain-language notice that screen/notification content (possibly passwords, OTPs, card numbers) travels to
 * the configured model service, that logs/evidence only carry counts and ids, and that turning it off stops images
 * only - screen text still travels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SmartSettingsSection(loadSettings: () -> SmartSettings, saveSettings: (SmartSettings) -> Unit) {
    var draft by remember { mutableStateOf(SmartSettingsForm.fromSettings(loadSettings())) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var saved by remember { mutableStateOf(false) }
    var levelExpanded by remember { mutableStateOf(false) }
    // 4B (`D189`): the second-confirmation dialog is only ever raised by an off -> on switch move. The switch itself
    // does not move until the user confirms, so a stray tap cannot arm the mode.
    var noInterceptionPending by remember { mutableStateOf(false) }
    val levels = listOf("L0", "L1", "L2", "L3", "L4")

    Text("唤醒与权限设置", style = MaterialTheme.typography.titleLarge)
    Text("唤醒词、权限上限、白名单、主动辅助等设置仅在 App 启动时读取一次（组合为进程级单例），修改后需完全重启 App 才会生效，重新开始会话不会重新读取；感知截图与不拦截开关保存后立即更新宿主状态。默认上限为 L1，如需 L3/L4 请在此明确选择。「脱离眼镜」在设置页下方单独切换（运行中会先停止会话），下次开始会话生效；本页保存不会改写它。",
        style = MaterialTheme.typography.bodyMedium)
    // Task 14b-fix item 5: an edit invalidates whatever the last save reported, so the stale error text cannot
    // outlive the input it was about.
    OutlinedTextField(value = draft.wakeWords, onValueChange = {
        draft = draft.copy(wakeWords = it); errors = emptyList(); saved = false
    }, label = { Text("唤醒词（逗号分隔，1-16 个字符）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = draft.whitelist, onValueChange = {
        draft = draft.copy(whitelist = it); errors = emptyList(); saved = false
    }, label = { Text("白名单（action|scope，逗号分隔）") }, modifier = Modifier.fillMaxWidth())
    ExposedDropdownMenuBox(expanded = levelExpanded, onExpandedChange = { levelExpanded = !levelExpanded }) {
        OutlinedTextField(value = draft.maxLevel, onValueChange = {}, readOnly = true, label = { Text("权限上限") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(levelExpanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded = levelExpanded, onDismissRequest = { levelExpanded = false }) {
            levels.forEach { level -> DropdownMenuItem(text = { Text(level) },
                onClick = { draft = draft.copy(maxLevel = level); errors = emptyList(); levelExpanded = false; saved = false }) }
        }
    }
    // P3.2: the proactive pair. The cooldown is a number the user types (5-600 s, in milliseconds), and the
    // sensitivity is one of three levels - the same chooser shape as the ceiling above.
    OutlinedTextField(value = draft.proactiveCooldown, onValueChange = {
        draft = draft.copy(proactiveCooldown = it); errors = emptyList(); saved = false
    }, label = { Text("主动辅助冷却（毫秒，5000-600000）") }, modifier = Modifier.fillMaxWidth())
    var sensitivityExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = sensitivityExpanded, onExpandedChange = { sensitivityExpanded = !sensitivityExpanded }) {
        OutlinedTextField(value = draft.proactiveSensitivity, onValueChange = {}, readOnly = true,
            label = { Text("主动辅助敏感度") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sensitivityExpanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded = sensitivityExpanded, onDismissRequest = { sensitivityExpanded = false }) {
            SmartSettings.SENSITIVITIES.forEach { level -> DropdownMenuItem(text = { Text(level) },
                onClick = {
                    draft = draft.copy(proactiveSensitivity = level)
                    errors = emptyList(); sensitivityExpanded = false; saved = false
                }) }
        }
    }
    // 0.24 S3 (spec §5.5): the official `continue_dialogue` switch, on by default. Persisted with the rest of the
    // section and read by the composition at its next assembly, exactly like the fields above.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("持续对话")
            Text(
                "答案显示/播报结束后自动进入下一轮聆听；关闭后答案结束约 1 秒关闭面板，不发 status 5。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = draft.continueDialogue, onCheckedChange = {
            draft = draft.copy(continueDialogue = it); errors = emptyList(); saved = false
        })
    }
    // 0.3 Phase 2 Task 10: the perception-screenshot switch. Unlike the fields above it is a **live** gate: a
    // successful save applies it to the composition immediately (no App restart). Turning it off stops screenshot
    // images from being captured/sent, but screen text keeps travelling with the request - the copy says both.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("感知截图（图片随请求发送）")
            Text(
                "关闭后不再截取并随请求发送屏幕图片；保存后立即生效。它不停止屏幕文字、通知等文字内容的发送。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = draft.visionEnabled, onCheckedChange = {
            draft = draft.copy(visionEnabled = it); errors = emptyList(); saved = false
        })
    }
    Text(
        "风险提示：开启后，屏幕与通知中的内容（可能包含密码、验证码、银行卡号等敏感信息）会随请求发送到你配置的模型服务；" +
            "本机日志与证据只记录计数和标识，不记录这些内容。关闭此开关只会停止发送截图图片，不会停止发送屏幕文字。",
        style = MaterialTheme.typography.bodySmall,
    )
    // 0.3 Phase 4B (`D189`): the global 「不拦截」 switch. This is the dangerous direction of the page: off -> on needs
    // the second confirmation below; on -> off is immediate. The draft is loaded from the persisted store at the top of
    // this section, so the switch shows the stored mode rather than a `remember { false }` UI default.
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(NoInterceptionUi.SWITCH_TITLE)
            Text(NoInterceptionUi.SWITCH_SUMMARY, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = draft.noInterception, onCheckedChange = { checked ->
            if (NoInterceptionUi.requiresSecondConfirmation(draft.noInterception, checked)) {
                noInterceptionPending = true
            } else {
                draft = draft.copy(noInterception = checked)
                errors = emptyList(); saved = false
            }
        })
    }
    if (draft.noInterception) {
        Text("当前已选择开启：保存后会立即更新宿主的「不拦截」门，并在关闭 App、被杀或重启后保持。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    if (saved) {
        if (errors.isEmpty()) Text("已保存：感知截图与不拦截开关会立即更新宿主状态；其余设置将在下次重启 App 后生效。「脱离眼镜」开关单独保存，本按钮不会改写它")
        else Text("可用字段已保存，被拒绝的输入请按上述提示修改")
    }
    Button(onClick = {
        // Task 14b-fix item 6: the store can refuse (a locked keystore, or a composition without one), and an
        // exception thrown out of a Compose click handler would propagate into the UI. It becomes inline text.
        runCatching {
            val result = SmartSettingsForm.toSettings(draft, loadSettings())
            saveSettings(result.settings)
            result
        }.onSuccess { result ->
            errors = result.errors
            saved = true
        }.onFailure { failure ->
            errors = listOf("保存失败：${failure.message ?: failure.javaClass.simpleName}")
            saved = false
        }
    }) { Text("保存") }
    // 4B (`D189`): the second confirmation. It is raised by the off -> on switch move above and never by the reverse;
    // the full risk copy lives in `NoInterceptionUi` so the JVM test can pin every consequence the user accepts.
    if (noInterceptionPending) {
        AlertDialog(
            onDismissRequest = { noInterceptionPending = false },
            title = { Text(NoInterceptionUi.CONFIRM_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoInterceptionUi.CONFIRM_LINES.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    draft = draft.copy(noInterception = true)
                    errors = emptyList(); saved = false; noInterceptionPending = false
                }) { Text("确认开启") }
            },
            dismissButton = {
                OutlinedButton(onClick = { noInterceptionPending = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 2026-10-20: the lock-screen PIN used when a device tool call arrives while the phone is locked.
 *
 * There is deliberately **no** on/off switch and no pocket switch: the owner's decision is that a device tool call
 * unlocks, and EDITH does not add another gate. The copy states the two limits that remain structural: the PIN is stored
 * encrypted on this phone and is only used for this unlock, and only a numeric PIN is supported (the unlock path sends
 * key codes, so pattern and non-ASCII passwords cannot be typed and are refused rather than half-supported).
 */
@Composable
internal fun LockPinSection(
    loadPinConfigured: () -> Boolean,
    savePin: (String) -> Boolean,
    clearPin: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(loadPinConfigured()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    Text("锁屏自动解锁", style = MaterialTheme.typography.titleLarge)
    Text(
        "工具调用需要手机处于解锁状态时会使用这里的 PIN。PIN 只保存在本机 Keystore（加密、不写日志、不上传）；" +
            "仅支持 4-16 位数字 PIN，不支持图案或中文密码。",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = pin,
        onValueChange = {
            if (it.length <= LockPinValidator.MAX_LENGTH && it.all { c -> c in '0'..'9' }) {
                pin = it
                error = null
                notice = null
            }
        },
        label = { Text("锁屏 PIN（4-16 位数字）") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val normalized = LockPinValidator.normalize(pin)
            if (normalized == null) {
                error = "请输入 4-16 位数字 PIN"
            } else if (savePin(normalized)) {
                configured = true
                pin = ""
                error = null
                notice = "PIN 已保存"
            } else {
                error = "PIN 保存失败，请重试"
            }
        }) { Text(if (configured) "更新 PIN" else "保存 PIN") }
        if (configured) {
            OutlinedButton(onClick = {
                clearPin()
                configured = false
                pin = ""
                error = null
                notice = "PIN 已清除"
            }) { Text("清除 PIN") }
        }
    }
    if (configured) Text("已配置：设备工具调用时会自动解锁", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider()
}

/**
 * P3.3 (spec §17.5): the audit trail its owner can read and delete.
 *
 * The copy says what is **not** in there as plainly as what is, because the value of this page is the user being able to
 * check that claim: the document has no field for a notification body, a screen line, a transcript or a phone number.
 */
@Composable
internal fun AuditSection(view: AuditLogView) {
    var revision by remember { mutableStateOf(0) }
    val lines = remember(revision) { view.lines() }
    Text("审计记录", style = MaterialTheme.typography.titleLarge)
    Text(
        "本地加密保存；只记录动作、风险级别、裁决、确认来源与执行结果，不含通知正文、屏幕文字、转写内容或电话号码。" +
            "最多保留最近 200 条。",
        style = MaterialTheme.typography.bodySmall,
    )
    if (lines.isEmpty()) Text("暂无记录")
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = {
        view.clear()
        revision++
    }) { Text("清空审计记录") }
    HorizontalDivider()
}

/**
 * P3.4 §3.4 (spec §6.5's 可查看、可删除): the long-term memory the assistant keeps, one line per memory.
 *
 * The sentence says the whole boundary out loud, because this page is where a user forms their model of it: the
 * document is local and encrypted, it holds their own facts/preferences/habits, the few entries retrieved for a request
 * travel to the configured model service **only** once this session's upload switch is on, and anything here can be
 * deleted on the spot. Deleting writes the document; it is not a hidden flag.
 *
 * It reads the same way `AuditSection` does - one snapshot per [revision] - so a memory written during a turn appears
 * after this section recomposes (a session stop/start does that, and so does any delete).
 */
@Composable
internal fun MemorySection(view: MemoryLogView) {
    var revision by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<MemoryLine?>(null) }
    val lines = remember(revision) { view.lines() }
    Text("长期记忆", style = MaterialTheme.typography.titleLarge)
    Text(
        "本地加密保存；只记录你的事实、偏好与常做任务。被检索到的那几条会随请求发到所配置的模型服务" +
            "（会话在运行时即允许，见「开始」）；可随时单条删除或全部清空。",
        style = MaterialTheme.typography.bodySmall,
    )
    if (lines.isEmpty()) Text("暂无记忆")
    lines.forEach { line ->
        // U-4: the list is a thumbnail (time, kind, 40 code points); the row opens the whole sentence on its own page.
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "查看记忆") { selected = line },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                view.renderPreview(line),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                view.delete(line.id)
                revision++
            }) { Text("删除") }
        }
    }
    OutlinedButton(onClick = {
        view.clear()
        revision++
    }) { Text("清空长期记忆") }
    selected?.let { line ->
        PhoneDetailPage(title = "记忆详情", onBack = { selected = null }) {
            Text("${line.stamp} | ${line.kind}", style = MaterialTheme.typography.bodySmall)
            Text(line.text)
        }
    }
    HorizontalDivider()
}

internal fun sessionLabel(state: SessionState) = when (state) {
    SessionState.Idle -> "未开始"
    SessionState.Ready -> "已就绪"
    SessionState.Listening -> "监听中"
    SessionState.Paused -> "已暂停"
    SessionState.BuildingScene -> "准备中"
    SessionState.Authorizing -> "授权中"
    SessionState.Connecting -> "连接中"
    SessionState.Interrupted -> "已中断"
    SessionState.Reconnecting -> "重新连接中"
    SessionState.Error -> "会话异常"
}
internal fun capabilityLabel(capability: Capability) = when (capability) {
    Capability.Audio -> "眼镜音频"
    Capability.Asr -> "语音转写"
    Capability.Speaker -> "说话人"
    Capability.Model -> "模型服务"
    Capability.TextOutput -> "眼镜显示"
    Capability.SpeechOutput -> "语音输出"
    Capability.Storage -> "持久存储"
    Capability.Actions -> "指令执行"
    Capability.Context -> "上下文感知"
    Capability.WakeWord -> "唤醒词"
    Capability.Confirmation -> "动作确认"
    Capability.SystemOne -> "决策模型"
    Capability.Embedding -> "语义嵌入"
    Capability.StorageAccess -> "技能目录"
    Capability.ConversationDisplay -> "会话展示"
    Capability.DeviceState -> "设备画像"
    Capability.Perception -> "感知"
}
internal fun errorLabel(code: String) = when (code) {
    "BLUETOOTH_PERMISSION_DENIED" -> "蓝牙权限未获授权"
    // 0.4 Task 6 (`D199`): the detached session's microphone gate and start failures are their own visible reasons -
    // a user whose phone refuses the microphone must not read "会话异常".
    "MIC_PERMISSION_MISSING" -> "麦克风权限未获授权"
    "MIC_UNAVAILABLE" -> "麦克风不可用（可能被其他应用占用）"
    "MIC_START_FAILED" -> "麦克风未能开始采音"
    "MIC_FORMAT_UNSUPPORTED" -> "麦克风音频格式不受支持"
    "CAPABILITY_MISSING" -> "会话所需服务尚不可用"
    "PROFILE_MODEL_NOT_MULTIMODAL" -> "该模型档案不支持图片输入；请更换支持图片的模型/档案（本版要求 IMAGE+TEXT）"
    "FOREGROUND_START_DENIED", "FOREGROUND_UI_REQUIRED" -> "会话未能启动"
    "UNKNOWN_PLUGIN" -> "插件不可用"
    else -> "会话异常"
}
