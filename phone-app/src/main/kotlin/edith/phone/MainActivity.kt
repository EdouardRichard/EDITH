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
                    { SessionService.commandFromUi(this, it) }, notificationsGranted(), { root.connectCxr(this) })
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
    connectCxr: () -> Boolean) {
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
                        }
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
}
private fun errorLabel(code: String) = when (code) {
    "BLUETOOTH_PERMISSION_DENIED" -> "蓝牙权限未获授权"
    "CAPABILITY_MISSING" -> "会话所需服务尚不可用"
    "FOREGROUND_START_DENIED", "FOREGROUND_UI_REQUIRED" -> "会话未能启动"
    "UNKNOWN_PLUGIN" -> "插件不可用"
    else -> "会话异常"
}
