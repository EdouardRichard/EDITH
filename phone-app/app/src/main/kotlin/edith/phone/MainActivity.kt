package edith.phone

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import edith.phone.model.ProfileViewModel
import edith.phone.model.ProfileListScreen
import edith.phone.ui.PhoneScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    companion object {
        /** The intent action the confirmation notification (and its full-screen intent) uses to raise this page. */
        const val ACTION_SHOW_CONFIRMATION = "edith.phone.SHOW_CONFIRMATION"
    }

    private val root get() = (application as EdithApplication).composition
    private var pendingPlugin: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val selected = pendingPlugin
        pendingPlugin = null
        if (!SessionService.bluetoothGranted(this)) root.error("BLUETOOTH_PERMISSION_DENIED")
        else if (selected != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
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
        // W1 (F17/F18): the vendor SDK's `connect` needs an Activity, and only this page has one. Handing it over here
        // is what lets the session start path (which runs inside `SessionService`) establish the CXR link itself
        // instead of depending on one UI combination happening to be composed. Held weakly by the composition.
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
                if (pending != null && remainingSeconds > 0L &&
                    dismissedRequestId != pending.requestId
                ) {
                    AlertDialog(
                        onDismissRequest = { dismissedRequestId = pending.requestId },
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
                PhoneScreen(state, root, viewModel, profileViewModel, ::requestStart,
                    { SessionService.commandFromUi(this, it) }, notificationsGranted(),
                    { root.storedSettings() }, { settings -> root.saveSmartSettings(settings) }, remainingSeconds)
            }
        }
    }

    /**
     * True while this page is front for a confirmation question, i.e. the composition raised it (see
     * `HostCompositionRoot.raiseConfirmationUi`). Snapshotted so the dialog above appears instead of the
     * below-the-fold card.
     */
    private var raisedForConfirmation by mutableStateOf(false)

    /** The question the user waved away; it must not be pushed at them again while it is still the same one. */
    private var dismissedRequestId by mutableStateOf<String?>(null)

    /**
     * P3.3 (§17.4): **visibility**, reported to the composition so a confirmation question can reach the notification
     * shade exactly when this page cannot be seen. `onStart`/`onStop` rather than `onResume`/`onPause`: a Shizuku grant
     * dialog or the MIUI install prompt pauses the Activity without hiding it, and a notification asking about a
     * question that is on screen is noise.
     */
    override fun onStart() {
        super.onStart()
        root.uiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        root.uiVisible(false)
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
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (requested.isEmpty()) SessionService.startFromUi(this, pluginId)
        else { pendingPlugin = pluginId; permissions.launch(requested.toTypedArray()) }
    }

    /**
     * The CXR AI-App authorization prompt is started by the SDK through
     * `requestAuthorization(activity, ...)`, so its result arrives here. It must reach the transport
     * (the only SDK access path) instead of being dropped, otherwise the token never completes.
     */
    @Deprecated("SDK authorization uses startActivityForResult; the transport is the only consumer")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        root.onCxrAuthorizationResult(resultCode, data)
    }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

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
 * The minimal settings entry point (Task 14b): the three things the user can set, plus the inline errors.
 *
 * Everything that can be validated lives in [SmartSettingsForm] (JVM-tested); this function only holds the text
 * fields and prints the errors, so the untested part is wiring. Two facts the copy must state truthfully
 * (Task 14b-fix item 4): the composition is a process-lifetime singleton and reads the settings **once** in
 * `HostCompositionRoot.create`, so only a full App restart re-reads them - restarting a session does not; and `L1`
 * is the ceiling the phase ships with while `L3`/`L4` are an explicit user choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SmartSettingsSection(loadSettings: () -> SmartSettings, saveSettings: (SmartSettings) -> Unit) {
    var draft by remember { mutableStateOf(SmartSettingsForm.fromSettings(loadSettings())) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var saved by remember { mutableStateOf(false) }
    var levelExpanded by remember { mutableStateOf(false) }
    val levels = listOf("L0", "L1", "L2", "L3", "L4")

    Text("唤醒与权限设置", style = MaterialTheme.typography.titleLarge)
    Text("设置仅在 App 启动时读取一次（组合为进程级单例），修改后需完全重启 App 才会生效，重新开始会话不会重新读取；默认上限为 L1，如需 L3/L4 请在此明确选择。",
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
    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    if (saved) {
        if (errors.isEmpty()) Text("已保存，将于下次重启 App 后生效")
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
    val lines = remember(revision) { view.lines() }
    Text("长期记忆", style = MaterialTheme.typography.titleLarge)
    Text(
        "本地加密保存；只记录你的事实、偏好与常做任务。被检索到的那几条会随请求发到所配置的模型服务" +
            "（会话在运行时即允许，见「开始」）；可随时单条删除或全部清空。",
        style = MaterialTheme.typography.bodySmall,
    )
    if (lines.isEmpty()) Text("暂无记忆")
    lines.forEach { line ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.render(line), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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
}
internal fun errorLabel(code: String) = when (code) {
    "BLUETOOTH_PERMISSION_DENIED" -> "蓝牙权限未获授权"
    "CAPABILITY_MISSING" -> "会话所需服务尚不可用"
    "FOREGROUND_START_DENIED", "FOREGROUND_UI_REQUIRED" -> "会话未能启动"
    "UNKNOWN_PLUGIN" -> "插件不可用"
    else -> "会话异常"
}
