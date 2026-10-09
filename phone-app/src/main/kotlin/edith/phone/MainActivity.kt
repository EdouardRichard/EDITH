package edith.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edith.contracts.SessionState
import edith.plugin.Capability
import edith.phone.model.ProfileViewModel
import edith.phone.model.ProfileListScreen

class MainActivity : ComponentActivity() {
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
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(root) as T
        })[SessionViewModel::class.java]
        val profileViewModel = if (root.profileTester != null) ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(root.profileViewModel()) as T
        })[ProfileViewModel::class.java] else null
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF126B50), secondary = Color(0xFF405B79))) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                PhoneScreen(state, root, viewModel, profileViewModel, ::requestStart,
                    { SessionService.commandFromUi(this, it) }, notificationsGranted(), { root.connectCxr(this) },
                    { root.storedSettings() }, { settings -> root.saveSmartSettings(settings) })
            }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun PhoneScreen(state: SessionUiState, root: HostCompositionRoot, viewModel: SessionViewModel,
    profileViewModel: ProfileViewModel?,
    start: (String) -> Unit, command: (String) -> Unit, notificationsGranted: Boolean,
    connectCxr: () -> Boolean,
    loadSettings: () -> SmartSettings, saveSettings: (SmartSettings) -> Unit) {
    var page by remember { mutableStateOf(0) }
    val plugins = buildList {
        add("smart-assistant" to "智能助手")
        add("interview-assistant" to "面试助手")
        if (BuildConfig.DEBUG) add("p2-diagnostic" to "诊断")
        if (BuildConfig.DEBUG && root.diagnosticHintSender != null) add("p2-hint" to "提示探针（调试）")
    }
    Scaffold(topBar = { Text("EDITH", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = page) {
                listOf("会话", "模型档案", "诊断").forEachIndexed { index, label ->
                    Tab(selected = page == index, onClick = { page = index }, text = { Text(label) }, enabled = index != 2 || BuildConfig.DEBUG)
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    0 -> {
                        Text(sessionLabel(state.session), style = MaterialTheme.typography.headlineSmall)
                        var expanded by remember { mutableStateOf(false) }
                        val selected = state.selectedPluginId ?: plugins.first().first
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                            OutlinedTextField(value = plugins.firstOrNull { it.first == selected }?.second ?: selected,
                                onValueChange = {}, readOnly = true, label = { Text("插件") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                plugins.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.selectPlugin(id); expanded = false }) }
                            }
                        }
                        if (selected != "p2-diagnostic") Text("业务功能尚未交付", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var helpResult by remember { mutableStateOf<String?>(null) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val inactive = state.session == SessionState.Idle || state.session == SessionState.Paused
                            Button(onClick = { if (state.session == SessionState.Paused) command(SessionService.RESUME) else start(selected) }, enabled = inactive) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(if (state.session == SessionState.Paused) "继续" else "开始")
                            }
                            OutlinedButton(onClick = { command(SessionService.PAUSE) }, enabled = !inactive) {
                                Icon(Icons.Default.Pause, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("暂停")
                            }
                            OutlinedButton(onClick = { command(SessionService.STOP) }, enabled = state.session != SessionState.Idle) {
                                Icon(Icons.Default.Stop, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("停止")
                            }
                            // Task 15c D2: the spec's non-wake entry point. The plugin runs its most recent final
                            // transcript of this session; a request with no live session is dropped by the host, and
                            // the label says so instead of pretending it was delivered.
                            OutlinedButton(onClick = {
                                helpResult = if (root.requestHelp()) "已请求帮助：将回应本会话最近一次最终转写"
                                else "当前没有可响应的会话（需已开始并处于就绪/监听中）"
                            }) { Icon(Icons.Default.HelpOutline, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("手动求助") }
                        }
                        helpResult?.let { Text(it) }
                        HorizontalDivider()
                        // D13: the only path by which a real turn's text may leave the device. Session-scoped, off by
                        // default, and reset by the composition when the session stops - the switch only ever writes
                        // the composition's one consent object, and nothing about it is persisted.
                        val uploadAllowed by root.sessionUploadConsent.enabled.collectAsStateWithLifecycle()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = uploadAllowed,
                                onCheckedChange = { root.sessionUploadConsent.set(it) },
                                enabled = state.session != SessionState.Idle)
                            Spacer(Modifier.width(8.dp))
                            Text("允许上传本会话的语音转写")
                        }
                        Text("开始会话后可开启。开启后，本会话的语音转写文本会随请求离开设备、发送到所配置的模型服务" +
                            "（含该轮请求所需的会话上下文）；默认关闭，会话停止后自动恢复为关闭，该开关也不会写入存储。",
                            style = MaterialTheme.typography.bodySmall)
                        state.errorCode?.let { Text(errorLabel(it), color = MaterialTheme.colorScheme.error) }
                        // Debug-only acceptance affordance: with the shipped plugins still shells, this is the only
                        // way a wearer can check what the glasses actually show. Hidden whenever no probe is
                        // registered, so a release build never shows it.
                        root.diagnosticHintSender?.let { sender ->
                            var sent by remember { mutableStateOf<String?>(null) }
                            OutlinedButton(onClick = {
                                sent = if (selected == "p2-hint" && sender()) "已提交一条测试提示，请看眼镜"
                                else "未能提交：请先选择「提示探针（调试）」并开始，且眼镜显示已就绪"
                            }) { Text("发送测试提示（眼镜）") }
                            sent?.let { Text(it) }
                        }
                        if (!notificationsGranted) Text("通知未获授权", color = MaterialTheme.colorScheme.error)
                        HorizontalDivider()
                        val cxrReady = state.capabilityReasons[Capability.Audio] == null
                        if (BuildConfig.DEBUG || !cxrReady) {
                            var connectResult by remember { mutableStateOf<String?>(null) }
                            OutlinedButton(onClick = {
                                connectResult = if (connectCxr()) "已请求连接眼镜，请在 AI App 中确认授权"
                                else "眼镜连接未装配（TRANSPORT_MISSING）"
                            }) { Text(if (cxrReady) "重连眼镜" else "连接眼镜（CXR）") }
                            connectResult?.let { Text(it) }
                        }
                        state.capabilityReasons.forEach { (capability, _) ->
                            ListItem(headlineContent = { Text(capabilityLabel(capability)) }, supportingContent = { Text("尚不可用") }, leadingContent = { Icon(Icons.Default.Info, null) })
                        }
                        HorizontalDivider()
                        SmartSettingsSection(loadSettings, saveSettings)
                    }
                    1 -> {
                        if (profileViewModel == null) Text("档案服务不可用")
                        else {
                            val profileState by profileViewModel.state.collectAsStateWithLifecycle()
                            ProfileListScreen(profileViewModel, profileState, plugins)
                        }
                    }
                    2 -> {
                        Text("诊断", style = MaterialTheme.typography.titleLarge)
                        Text("会话：${sessionLabel(state.session)}")
                        val marks = root.trace.snapshot()
                        Text("事件数：${marks.size}")
                        marks.takeLast(20).forEach { Text("${it.mark.name} · ${it.safeCode ?: ""}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

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
@Composable private fun SmartSettingsSection(loadSettings: () -> SmartSettings, saveSettings: (SmartSettings) -> Unit) {
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

private fun sessionLabel(state: SessionState) = when (state) {
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
private fun capabilityLabel(capability: Capability) = when (capability) {
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
}
private fun errorLabel(code: String) = when (code) {
    "BLUETOOTH_PERMISSION_DENIED" -> "蓝牙权限未获授权"
    "CAPABILITY_MISSING" -> "会话所需服务尚不可用"
    "FOREGROUND_START_DENIED", "FOREGROUND_UI_REQUIRED" -> "会话未能启动"
    "UNKNOWN_PLUGIN" -> "插件不可用"
    else -> "会话异常"
}
