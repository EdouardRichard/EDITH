package com.sheen.rokid.phone.ui
import com.sheen.rokid.phone.app.SmartSettingsSection

import android.Manifest
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.AuditSection
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.app.MemorySection
import com.sheen.rokid.phone.session.SessionService
import com.sheen.rokid.phone.session.SessionUiState
import com.sheen.rokid.phone.session.SessionViewModel
import com.sheen.rokid.phone.SkillListing
import com.sheen.rokid.phone.SmartSettings
import com.sheen.rokid.phone.app.capabilityLabel
import com.sheen.rokid.phone.capabilityReasonLabel
import com.sheen.rokid.phone.bridge.cxrAuthorizationLabel
import com.sheen.rokid.phone.bridge.cxrStatusLabel
import com.sheen.rokid.phone.app.errorLabel
import com.sheen.rokid.phone.executionChannelLabel
import com.sheen.rokid.phone.model.ProfileListScreen
import com.sheen.rokid.phone.model.ProfileViewModel
import com.sheen.rokid.phone.pluginPickerOptions
import com.sheen.rokid.phone.app.sessionLabel
import com.sheen.rokid.plugin.Capability
import kotlinx.coroutines.launch

/**
 * The phone's whole page (spec §6.7): a top bar that names the plugin, a left-hand menu, and the assistant's four tabs.
 *
 * The three regions are the spec's three: 顶部栏 carries the active plugin's name and a read-only session indicator;
 * 左上角菜单页 carries session management, the plugin picker and the diagnostics page; and the body is the active
 * plugin's own UI — which for the smart assistant is 助手/技能/记忆/设置.
 *
 * This file is the shell and the two navigation decisions it makes are unit-tested (`PhoneNavigationTest`); the tabs
 * themselves live beside it. Everything that draws is verified on the device, because that is the only place a layout
 * can be judged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneScreen(
    state: SessionUiState,
    root: HostCompositionRoot,
    viewModel: SessionViewModel,
    profileViewModel: ProfileViewModel?,
    start: (String) -> Unit,
    command: (String) -> Unit,
    notificationsGranted: Boolean,
    loadSettings: () -> SmartSettings,
    saveSettings: (SmartSettings) -> Unit,
    remainingSeconds: Long,
) {
    var tab by remember { mutableStateOf(PhoneTab.ASSISTANT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val plugins = pluginPickerOptions(
        debug = BuildConfig.DEBUG,
        diagnosticHintSenderPresent = root.diagnosticHintSender != null,
    )
    val selected = state.selectedPluginId ?: plugins.firstOrNull()?.first

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                PhoneMenuDrawer(
                    state = state,
                    root = root,
                    plugins = plugins,
                    selected = selected,
                    start = start,
                    command = command,
                    onSelectPlugin = { viewModel.selectPlugin(it) },
                    onClose = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(pluginTitle(selected, plugins)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = { SessionStatusIndicator(state.session) },
                )
            },
            bottomBar = {
                NavigationBar {
                    PhoneTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(iconFor(entry), contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selected != PhoneTab.SMART_ASSISTANT_PLUGIN_ID) {
                    // The four tabs read the smart assistant's own state, so another plugin gets an honest sentence
                    // rather than that state under the wrong name (spec §6.7 主体).
                    Text("「${pluginTitle(selected, plugins)}」未提供专属界面。", style = MaterialTheme.typography.bodyLarge)
                    Text("会话管理、插件选择与诊断在左上角菜单里。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    when (phoneTabFor(tab.ordinal)) {
                        PhoneTab.ASSISTANT -> AssistantTab(root.conversationLog)
                        PhoneTab.SKILLS -> SkillsTab(root)
                        PhoneTab.MEMORY -> root.memoryLogView?.let { MemorySection(it) } ?: Text("记忆服务不可用")
                        PhoneTab.SETTINGS -> SettingsTab(
                            state = state,
                            root = root,
                            profileViewModel = profileViewModel,
                            plugins = plugins,
                            loadSettings = loadSettings,
                            saveSettings = saveSettings,
                            notificationsGranted = notificationsGranted,
                        )
                    }
                    state.errorCode?.let { Text(errorLabel(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/**
 * The read-only session indicator (spec §6.7 右上角：会话状态指示器（仅展示）).
 *
 * "Only displays" is the requirement: the controls that change the session live in the menu, so this must not become a
 * second place to start or stop one.
 */
@Composable
private fun SessionStatusIndicator(session: SessionState) {
    val running = session != SessionState.Idle && session != SessionState.Error
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Icon(
            if (running) Icons.Default.PlayArrow else Icons.Default.Info,
            contentDescription = null,
            tint = if (running) Color(0xFF126B50) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(sessionLabel(session), style = MaterialTheme.typography.labelLarge)
    }
}

private fun iconFor(tab: PhoneTab) = when (tab) {
    PhoneTab.ASSISTANT -> Icons.Default.Chat
    PhoneTab.SKILLS -> Icons.Default.Build
    PhoneTab.MEMORY -> Icons.Default.Star
    PhoneTab.SETTINGS -> Icons.Default.Settings
}

/**
 * The assistant's page (spec §6.7 助手: 对话上下文记录；顶部切换 WAKE/PROACTIVE).
 *
 * The switch is **read-only**: it changes which conversation is in front of the wearer and nothing else. It reads the
 * same `ConversationLog` the glasses render — same shape, same order, same eviction — so the two screens cannot drift
 * apart the way two implementations would. They are not the same *instance*: the phone records what the plugin said,
 * the glasses what the display accepted, so a refused hint shows here and never there.
 *
 * The list re-reads itself while the tab is on screen, because a record that only updates when the wearer presses a
 * button is a record that looks frozen exactly when a turn is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTab(log: com.sheen.rokid.output.ConversationLog) {
    var route by remember { mutableStateOf(AssistantRoute.WAKE) }
    var revision by remember { mutableStateOf(0) }
    // The log is written from the host's drain thread and the plugin's task pool, so this is a poll rather than a
    // subscription: a cheap re-read every two seconds while the tab is composed, and nothing while it is not.
    LaunchedEffect(route) {
        while (true) {
            revision++
            kotlinx.coroutines.delay(2_000)
        }
    }

    Text("对话上下文记录", style = MaterialTheme.typography.titleLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AssistantRoute.entries.forEachIndexed { index, candidate ->
            SegmentedButton(
                selected = route == candidate,
                onClick = { route = candidate },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AssistantRoute.entries.size),
            ) { Text(if (candidate == AssistantRoute.WAKE) "唤醒" else "主动") }
        }
    }
    Text(
        "只读视图：切换只改变这里显示哪一条对话，不影响正在运行的会话。眼镜上看的是同一份记录。",
        style = MaterialTheme.typography.bodySmall,
    )
    val entries = remember(revision, route) { log.entries(route) }
    if (entries.isEmpty()) {
        Text("暂无记录")
    } else {
        entries.forEach { entry ->
            ListItem(
                headlineContent = { Text(entry.text) },
                supportingContent = {
                    Text(if (entry.role == com.sheen.rokid.output.ConversationRole.USER) "你" else "助手")
                },
                leadingContent = {
                    Icon(
                        if (entry.role == com.sheen.rokid.output.ConversationRole.USER) Icons.Default.Person else Icons.Default.Info,
                        contentDescription = null,
                    )
                },
            )
        }
    }
    OutlinedButton(onClick = { revision++ }) { Text("刷新") }
}

/**
 * The SKILL page (spec §6.7 技能: SKILL 列表 + 删除).
 *
 * Listing and deleting go through the same `Capability.StorageAccess` the plugin reads the directory with, so the page
 * shows exactly the files a turn would load. The empty case and the unreadable case are kept apart on purpose: B4's
 * device work was blocked for a day by a refused grant looking exactly like "the user has no SKILLs".
 */
@Composable
internal fun SkillsTab(root: HostCompositionRoot) {
    var revision by remember { mutableStateOf(0) }
    val listing = remember(revision) { root.skillDirectory?.list() ?: SkillListing.Unreadable }

    Text("技能（SKILL）", style = MaterialTheme.typography.titleLarge)
    Text(
        "读取 /sdcard/EDITH/skills 下的 .md 文件；每次回合开始前重新读取，删除后下一回合即生效。",
        style = MaterialTheme.typography.bodySmall,
    )
    when (listing) {
        is SkillListing.Unreadable -> Text("未授权或目录不存在，读不到技能目录。", color = MaterialTheme.colorScheme.error)
        is SkillListing.Listed -> if (listing.names.isEmpty()) {
            Text("暂无技能")
        } else {
            listing.names.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        root.skillDirectory?.delete(name)
                        revision++
                    }) { Text("删除") }
                }
            }
        }
    }
    OutlinedButton(onClick = { revision++ }) { Text("刷新") }
}

/**
 * The settings page (spec §6.7 插件设置: 模型配置、参数配置、本地模型状态).
 *
 * Everything that used to live on the old 会话 tab ends up here, unchanged in behaviour: the switches, the four
 * permission entries, the execution channels and the audit trail. Two things are deliberately *not* here — starting or
 * stopping a session, and the CXR link's own facts — because those moved to the menu (C6/C10).
 */
@Composable
internal fun SettingsTab(
    state: SessionUiState,
    root: HostCompositionRoot,
    profileViewModel: ProfileViewModel?,
    plugins: List<Pair<String, String>>,
    loadSettings: () -> SmartSettings,
    saveSettings: (SmartSettings) -> Unit,
    notificationsGranted: Boolean,
) {
    Text("模型配置", style = MaterialTheme.typography.titleLarge)
    if (profileViewModel == null) {
        Text("档案服务不可用")
    } else {
        val profileState by profileViewModel.state.collectAsStateWithLifecycle()
        ProfileListScreen(profileViewModel, profileState, plugins)
    }

    HorizontalDivider()
    SmartSettingsSection(loadSettings, saveSettings)

    HorizontalDivider()
    LocalModelStatus(state)

    HorizontalDivider()
    Text("本地模型状态之外的能力", style = MaterialTheme.typography.titleLarge)
    if (!notificationsGranted) Text("通知未获授权", color = MaterialTheme.colorScheme.error)
    state.capabilityReasons.forEach { (capability, reason) ->
        ListItem(
            headlineContent = { Text(capabilityLabel(capability)) },
            supportingContent = { Text("尚不可用（${capabilityReasonLabel(reason)}）") },
            leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
        )
    }
    state.executionChannels.forEach { channel ->
        ListItem(
            headlineContent = { Text(executionChannelLabel(channel.id)) },
            supportingContent = {
                Text(
                    when {
                        channel.available -> "可用"
                        channel.note != null -> channel.note
                        channel.reason != null -> "尚不可用（${capabilityReasonLabel(channel.reason)}）"
                        else -> "尚不可用"
                    },
                )
            },
            leadingContent = { Icon(if (channel.available) Icons.Default.Check else Icons.Default.Info, null) },
        )
    }

    HorizontalDivider()
    PermissionEntries(root)

    HorizontalDivider()
    SmartToggles(root)

    HorizontalDivider()
    root.auditLog?.let { AuditSection(it) }
}

/**
 * Local model status (spec §6.7 插件设置：本地模型状态).
 *
 * Reports availability for the two hard dependencies from the state the page already holds. The finer
 * LOADING/READY/UNAVAILABLE detail of spec §2.6 needs a status seam the composition does not expose to the UI yet;
 * saying "可用 / 尚不可用 + reason" is what this build can say truthfully, and it is the distinction the wearer acts on.
 */
@Composable
private fun LocalModelStatus(state: SessionUiState) {
    Text("本地模型状态", style = MaterialTheme.typography.titleLarge)
    listOf(Capability.SystemOne to "决策模型", Capability.Embedding to "语义嵌入").forEach { (capability, label) ->
        val reason = state.capabilityReasons[capability]
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(if (reason == null) "可用" else "尚不可用（${capabilityReasonLabel(reason)}）") },
            leadingContent = { Icon(if (reason == null) Icons.Default.Check else Icons.Default.Info, null) },
        )
    }
}

/**
 * The two persisted switches (P3.2 主动辅助, P5 语音朗读).
 *
 * Kept verbatim from the old page, including the sentences about what each switch does *not* grant: the arming switch
 * is not an upload grant (`D53`①), and turning speech off is a choice the app never reverses.
 */
@Composable
private fun SmartToggles(root: HostCompositionRoot) {
    val proactiveArmed by root.proactiveArming.enabled.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = proactiveArmed, onCheckedChange = { root.proactiveArming.set(it) })
        Spacer(Modifier.width(8.dp))
        Text("主动辅助（未唤醒时给一句短提示）")
    }
    Text(
        "一次配置、长期生效：跨会话、重连、重启 App 或手机都保持；关掉后不会被任何路径自动打开。",
        style = MaterialTheme.typography.bodySmall,
    )
    val speechOn by root.speechEnabled.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = speechOn, onCheckedChange = { root.setSpeechEnabled(it) })
        Spacer(Modifier.width(8.dp))
        Text("语音朗读（提示会朗读出来）")
    }
    Text(
        "关闭后不会自动打开；引擎缺失时提示照常显示、只是不出声。唤醒后的「在的」也受这个开关控制。",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The four system grants the assistant's perception needs (P3.2 Stage B).
 *
 * A refused source contributes **no** context key, never a placeholder, and the copy says what the assistant cannot see
 * without it. The grant is not observable state and the user changes it outside this App, so returning from Settings
 * recomposes nothing; `grantEpoch` re-reads all of them on every resume.
 */
@Composable
private fun PermissionEntries(root: HostCompositionRoot) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var grantEpoch by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) grantEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activityContext = LocalContext.current

    root.foregroundAppSource?.let { foreground ->
        val granted = remember(grantEpoch) { foreground.isGranted }
        OutlinedButton(onClick = {
            runCatching { activityContext.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }) { Text("前台 App 感知授权（使用情况访问）") }
        if (!granted) {
            Text("未授予使用情况访问，主动辅助看不到前台 App。", color = MaterialTheme.colorScheme.error)
        }
    }
    root.notificationCategorySource?.let { notifications ->
        val connected = remember(grantEpoch) { notifications.isConnected }
        OutlinedButton(onClick = {
            runCatching { activityContext.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }) { Text("通知分类感知授权（通知使用权）") }
        if (!connected) {
            Text(
                "未连接通知监听，主动辅助看不到通知分类（只读分类，不读通知内容）。",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    root.calendarSource?.let { calendar ->
        val granted = remember(grantEpoch) { calendar.isGranted }
        val requestCalendar = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()) { grantEpoch++ }
        OutlinedButton(onClick = { requestCalendar.launch(Manifest.permission.READ_CALENDAR) }) {
            Text("日程标题感知授权（日历权限）")
        }
        if (!granted) {
            Text("未授予日历权限，主动辅助看不到今天的日程标题。", color = MaterialTheme.colorScheme.error)
        }
    }
    val overlayGranted = remember(grantEpoch) { Settings.canDrawOverlays(activityContext) }
    OutlinedButton(onClick = {
        runCatching {
            activityContext.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${activityContext.packageName}")))
        }
    }) { Text("悬浮窗授权（确认弹窗需要）") }
    if (!overlayGranted) {
        Text("未授予悬浮窗权限，确认弹窗无法在其他 App 上方弹出。", color = MaterialTheme.colorScheme.error)
    }
}
