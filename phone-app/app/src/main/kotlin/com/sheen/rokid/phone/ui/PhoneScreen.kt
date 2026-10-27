package com.sheen.rokid.phone.ui
import com.sheen.rokid.phone.ui.profile.ProfileListScreen
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.app.LockPinSection
import com.sheen.rokid.phone.app.SmartSettingsSection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.phone.app.AuditSection
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.app.MemorySection
import com.sheen.rokid.phone.app.TypedSendResult
import com.sheen.rokid.phone.audio.DictationStartResult
import com.sheen.rokid.phone.audio.DictationState
import com.sheen.rokid.phone.audio.DictationUiState
import com.sheen.rokid.phone.session.SessionService
import com.sheen.rokid.phone.session.SessionUiState
import com.sheen.rokid.phone.session.SessionViewModel
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SkillContent
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SkillListing
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.app.capabilityLabel
import com.sheen.rokid.phone.ui.capabilityReasonLabel
import com.sheen.rokid.phone.bridge.cxrAuthorizationLabel
import com.sheen.rokid.phone.bridge.cxrStatusLabel
import com.sheen.rokid.phone.app.errorLabel
import com.sheen.rokid.phone.actions.executionChannelLabel
import com.sheen.rokid.phone.ui.pluginPickerOptions
import com.sheen.rokid.plugin.Capability
import kotlinx.coroutines.flow.StateFlow
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
    // 0.4 Task 13e-prep: the composer state belongs to the page, not to the assistant tab. A bottom-tab switch
    // disposes AssistantTab, which used to discard the draft — and a dictated final appended to it — even though
    // PhoneScreen never left the composition. Hoisted here it survives every 助手/技能/记忆/设置 switch and still
    // round-trips through rememberSaveable on an Activity recreation.
    var typedInput by rememberSaveable(stateSaver = typedInputSaver) { mutableStateOf(TypedInputState()) }
    // A typed result is an answer about the session that was live when the send happened. Remember the previous state
    // so a stop or a start clears a stale 「助手正在处理」/「会话未运行」 without touching the draft or a live dictation.
    var previousSession by remember { mutableStateOf(state.session) }
    LaunchedEffect(state.session) {
        typedInput = typedInput.afterSessionChange(previousSession, state.session)
        previousSession = state.session
    }
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
                    // The start click must hand the page back to the assistant: leaving the drawer over the
                    // conversation made the corpus/model see "会话管理/已就绪" instead of the intended assistant page
                    // (2026-10-20 actual-screen finding). A refused start still closes; the error is shown on the page.
                    start = { pluginId ->
                        start(pluginId)
                        scope.launch { drawerState.close() }
                    },
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
            val body = Modifier.padding(padding).fillMaxSize()
            if (selected != PhoneTab.SMART_ASSISTANT_PLUGIN_ID) {
                // The four tabs read the smart assistant's own state, so another plugin gets an honest sentence
                // rather than that state under the wrong name (spec §6.7 主体).
                ScrollableTab(body) {
                    Text("「${pluginTitle(selected, plugins)}」未提供专属界面。", style = MaterialTheme.typography.bodyLarge)
                    Text("会话管理、插件选择与诊断在左上角菜单里。", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                when (phoneTabFor(tab.ordinal)) {
                    // U1: only the message list scrolls. The title, the route switch and the chat frame header stay
                    // outside every scroll container, so a long record cannot take the switch away from the wearer.
                    PhoneTab.ASSISTANT -> Column(body) {
                        AssistantTab(
                            log = root.conversationLog,
                            session = state.session,
                            typedInput = typedInput,
                            onTypedInputChange = { typedInput = it },
                            send = { root.requestTypedMessage(it) },
                            dictation = root.dictationUi,
                            startDictation = { root.startDictation() },
                            stopDictation = { root.stopDictation() },
                            clearDictationStatus = { root.clearDictationStatus() },
                            consumeDictationFinal = { root.consumeDictationFinal(it) },
                            modifier = Modifier.weight(1f),
                        )
                        state.errorCode?.let {
                            Text(
                                errorLabel(it),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                    }
                    PhoneTab.SKILLS -> ScrollableTab(body, state.errorCode) { SkillsTab(root) }
                    PhoneTab.MEMORY -> ScrollableTab(body, state.errorCode) {
                        root.memoryLogView?.let { MemorySection(it) } ?: Text("记忆服务不可用")
                    }
                    PhoneTab.SETTINGS -> ScrollableTab(body, state.errorCode) {
                        SettingsTab(
                            state = state,
                            root = root,
                            profileViewModel = profileViewModel,
                            plugins = plugins,
                            loadSettings = loadSettings,
                            saveSettings = saveSettings,
                            notificationsGranted = notificationsGranted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The whole-page scroll container the three list-like tabs still use.
 *
 * The assistant tab is the exception (U1): its title, route switch and chat frame header must not move while the
 * messages scroll, so [AssistantTab] owns its own fixed frame and is deliberately not wrapped in this.
 */
@Composable
private fun ScrollableTab(
    body: Modifier,
    errorCode: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        body.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        errorCode?.let { Text(errorLabel(it), color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * The top bar's session state, in the resident vocabulary (0.24 spec §5.5).
 *
 * One 开始 makes the session resident - closing the glasses panel does not end it - so the old turn words
 * (未开始/监听中) do not describe what the wearer is now running. `Idle` therefore reads 「常驻监听中」 (the resident
 * process at rest) and any live session reads 「会话运行中」. An error is still named as an error, because hiding it
 * would make the indicator lie about a state the user has to act on.
 */
internal fun residentSessionLabel(state: SessionState): String = when (state) {
    SessionState.Idle -> "常驻监听中"
    SessionState.Error -> "会话异常"
    else -> "会话运行中"
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
        Text(residentSessionLabel(session), style = MaterialTheme.typography.labelLarge)
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
 * U1 fixes the original defect: the title, the route switch and the chat frame's own header (route name + refresh
 * icon) are **outside** every scroll container, so a long record cannot carry them off screen. Only the message
 * `LazyColumn` scrolls.
 *
 * U2 puts the assistant's bubbles on the left and the wearer's on the right; the owner 2026-10-17 follow-up chose a
 * visible role avatar (a feminine face for the assistant, a person for the wearer) and no timestamp, so the two sides
 * are named as well as positioned. The avatar is decorative to a screen reader: the bubble row carries the spoken
 * "助手说：…" text.
 *
 * 0.4 Task 11 (spec §5.1) adds the WAKE view's composer below the chat frame; the owner's 2026-10-27 redesign made it
 * one soft pill with a single circular action (mic/send/stop). It is part of the fixed frame like the header — outside
 * the message `LazyColumn` — is drawn only on WAKE, and never reads `currentIdentity()`: the host call it hands the
 * text to owns that check. `send` returns the host's verdict so the composer can clear on `Accepted` and keep the
 * draft on every retryable refusal.
 *
 * The switch is **read-only**: it changes which conversation is in front of the wearer and nothing else. It reads the
 * same `ConversationLog` the glasses render — same shape, same order, same eviction — so the two screens cannot drift
 * apart the way two implementations would. They are not the same *instance*: the phone records what the plugin said,
 * the glasses what the display accepted, so a refused hint shows here and never there.
 *
 * The list re-reads itself while the tab is on screen, because a record that only updates when the wearer presses a
 * button is a record that looks frozen exactly when a turn is running. It follows new entries only when the reader is
 * already at the bottom; the owner 2026-10-17 chose to start each route at its newest entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTab(
    log: ConversationLog,
    session: SessionState,
    typedInput: TypedInputState,
    onTypedInputChange: (TypedInputState) -> Unit,
    send: (String) -> TypedSendResult,
    dictation: StateFlow<DictationUiState>,
    startDictation: () -> DictationStartResult,
    stopDictation: () -> Unit,
    clearDictationStatus: () -> Unit,
    consumeDictationFinal: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var route by remember { mutableStateOf(AssistantRoute.WAKE) }
    var revision by remember { mutableStateOf(0) }
    // 0.4 Task 13e-prep: the draft/result are owned by PhoneScreen (a tab switch would dispose this function and take
    // them with it); this page only reads the value and reports every change back up.
    // The dictation host is the only writer of this flow (its callbacks may arrive on the pipeline or scheduler
    // thread; StateFlow + lifecycle collection marshal them onto the main thread). The host also owns the final's
    // lifetime: it stays in the flow until this page has appended it and acknowledged it with
    // `consumeDictationFinal`, so the page's own counter is deliberately **not** saved across compositions
    // (0.4 Task 12b-ii fix 2, finding 1). Saving a raw sequence would make a tab switch reset it to 0 (duplicate
    // append) and a process restart restore an old N (the new host's first N finals silently dropped).
    val dictationUi by dictation.collectAsStateWithLifecycle()
    var appendedDictationSequence by remember { mutableStateOf(0L) }
    LaunchedEffect(dictationUi.final?.sequence) {
        val delivered = dictationUi.final ?: return@LaunchedEffect
        if (!isNewDictationFinal(delivered, appendedDictationSequence)) return@LaunchedEffect
        appendedDictationSequence = delivered.sequence
        // 0.4 Task 12b-ii (spec §5.3): the recognized words are appended to the editable draft. Nothing is sent:
        // the wearer can fix them and presses 发送 when ready. The transition is pure so its draft-preserving
        // behaviour is JVM-tested (0.4 Task 13e-prep).
        onTypedInputChange(typedInput.afterDictationFinal(delivered.text))
        // Only after the append: the host then stops handing this final to a re-created page.
        consumeDictationFinal(delivered.sequence)
    }
    val entries = remember(revision, route) { log.entries(route) }
    // Start the first layout at the newest entry, so the frame before the route-opening effect cannot show the oldest
    // one. The route-opening effect still owns route switches (the state is shared, not recreated).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollTarget(entries.size) ?: 0)
    val bottomPaddingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    // The values the follow decision compares against. They reset with the route, because the list state belongs to one
    // conversation at a time: carrying the old position into the other route would judge the wrong reader position.
    var previousCount by remember(route) { mutableStateOf(entries.size) }
    var previousLastId by remember(route) { mutableStateOf(entries.lastOrNull()?.entryId) }

    // The log is written from the host's drain thread and the plugin's task pool, so this is a poll rather than a
    // subscription: a cheap re-read every two seconds while the tab is composed, and nothing while it is not.
    LaunchedEffect(route) {
        while (true) {
            revision++
            kotlinx.coroutines.delay(2_000)
        }
    }

    // Opening a route, or switching to the other one, starts at the newest entry (owner 2026-10-17). scrollToItem, not
    // animateScrollToItem: a first frame that animates through the whole record would show the oldest line first.
    LaunchedEffect(route) {
        initialScrollTarget(entries.size)?.let { listState.scrollToItem(it) }
        previousCount = entries.size
        previousLastId = entries.lastOrNull()?.entryId
    }

    // Follow a newly appended entry only when the reader was already at the bottom. The previous last entry's *end*
    // being visible is the frame-order-independent fact that means "the reader is at the end": an index/count comparison
    // cannot see a capped append (count stays 40), and a long last bubble that is only partly visible is still being
    // read rather than finished.
    LaunchedEffect(entries.size, entries.lastOrNull()?.entryId) {
        val layout = listState.layoutInfo
        val visibleItems = layout.visibleItemsInfo.map { VisibleItem(it.key, it.offset + it.size) }
        val atBottomBefore = wasAtBottom(previousLastId, visibleItems, layout.viewportEndOffset, bottomPaddingPx)
        // Either a count increase or a changed last id is a new entry; the second covers the bounded log's cap.
        val shouldFollow = shouldAutoScroll(atBottomBefore, previousCount, entries.size) ||
            shouldFollowNewEntry(atBottomBefore, previousLastId, entries.lastOrNull()?.entryId)
        val oldCount = previousCount
        // Bookkeeping is updated BEFORE the suspend scroll: if the animation is cancelled by the user starting to
        // scroll, the next decision must still compare against the list that is now on screen, not the one before it.
        previousCount = entries.size
        previousLastId = entries.lastOrNull()?.entryId
        if (entries.isNotEmpty() && shouldFollow) {
            if (oldCount == 0) listState.scrollToItem(entries.lastIndex)
            else listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(routeLabel(route), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { revision++ }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = REFRESH_DESCRIPTION,
                        )
                    }
                }
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("暂无记录", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = { it.entryId }) { entry -> MessageBubble(entry) }
                    }
                }
            }
        }
        if (showsTypedInputRow(route)) {
            TypedInputRow(
                session = session,
                state = typedInput,
                dictation = dictationUi.state,
                refusal = dictationUi.refusal,
                onDraftChange = { candidate ->
                    // 0.4 Task 12b-ii fix 2 (finding 2): editing is the wearer starting a newer action, so a finished
                    // dictation's no-speech/timeout/error text must not outrank what they are doing now.
                    clearDictationStatus()
                    onTypedInputChange(typedInput.copy(draft = typedDraftAfterInput(candidate)))
                },
                onSend = {
                    val outgoing = typedInput
                    // The button is already disabled for both facts; the check is repeated here so a queued click
                    // that lands after a state change cannot submit a second request.
                    if (canSendTyped(session, outgoing.draft.isBlank(), outgoing.sending)) {
                        onTypedInputChange(outgoing.beginSend().finishSend(send(outgoing.draft)))
                    }
                },
                onStartDictation = { startDictation() },
                onStopDictation = stopDictation,
            )
        }
    }
}

/**
 * The input row's page-recreation state: the draft and the last result survive an Activity recreation. `sending` is
 * deliberately not restored — the host call is synchronous, so there is no request left to resume.
 */
private val typedInputSaver: Saver<TypedInputState, Any> = listSaver(
    save = { typedInputSaveValues(it) },
    restore = { typedInputRestore(it) },
)

/**
 * 0.4 Task 11 (spec §5.1) + Task 12b-ii (spec §5.3): the WAKE conversation's composer, below the chat frame.
 *
 * Always rendered on WAKE (the 脱离眼镜 switch does not hide it), never on PROACTIVE. The composer draws the pure
 * decisions from `TypedInput.kt`/`DictationInput.kt` and reads no identity itself.
 *
 * The owner's 2026-10-27 redesign (option A) replaced the outlined field + microphone icon + text button row with one
 * cohesive pill and **one** circular action: `BasicTextField` on a soft `surfaceContainerHigh` fill, 28 dp corners, no
 * outline and no floating label; the action is a `FilledIconButton` clipped to [CircleShape] and is green while live
 * (EDITH's primary), muted grey when the same action is unavailable. The slot's icon follows the draft and the
 * dictation lease ([composerActionSlot]); it is never a second, disappearing button. The status line is drawn only when
 * [inputRowStatusText] has something to say.
 *
 * The microphone starts a phone-microphone dictation on a tap and stops the running one on a second tap (D204). A
 * missing `RECORD_AUDIO` grant is requested right here - glasses sessions never ask for it at start, so the dictation
 * is the only moment the permission can be obtained - and the host re-checks the grant at start. While listening the
 * status line shows 正在听写…; the final transcript is appended to the draft by [AssistantTab], never sent. A refused
 * start (`refusal`) shows its retryable Chinese reason in the same one status line, so a tap that does nothing is
 * explained rather than silent (0.4 Task 12b-ii fix 2, finding 4c).
 */
@Composable
private fun TypedInputRow(
    session: SessionState,
    state: TypedInputState,
    dictation: DictationState,
    refusal: DictationStartResult?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStartDictation: () -> DictationStartResult,
    onStopDictation: () -> Unit,
) {
    val slot = composerActionSlot(session, state, dictation)
    val status = inputRowStatusText(session, state, dictation, refusal)
    val failed = (state.result != null && state.result != TypedSendResult.Accepted) ||
        dictation is DictationState.Error
    val context = LocalContext.current
    // The permission result is asynchronous. On a denial the host is still asked once: it re-checks the grant and
    // publishes the retryable explanation (需要麦克风权限…), so the composer says what to do instead of the tap being
    // silent.
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStartDictation()
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // One surface, one action. imePadding keeps the pill above the keyboard instead of letting the IME cover it.
        Surface(
            modifier = Modifier.fillMaxWidth().imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (state.draft.isEmpty()) {
                                Text(
                                    TYPED_INPUT_PLACEHOLDER,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                FilledIconButton(
                    onClick = {
                        when (slot.action) {
                            ComposerAction.Microphone -> {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onStartDictation()
                                } else {
                                    requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            ComposerAction.Send -> onSend()
                            ComposerAction.Stop -> onStopDictation()
                        }
                    },
                    modifier = Modifier.size(46.dp),
                    enabled = slot.enabled,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Icon(
                        imageVector = when (slot.action) {
                            ComposerAction.Microphone -> Icons.Default.Mic
                            ComposerAction.Send -> Icons.Default.ArrowUpward
                            ComposerAction.Stop -> Icons.Default.Stop
                        },
                        contentDescription = composerActionDescription(slot.action),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        status?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/**
 * One message: the assistant's on the left, the wearer's on the right (U2), content-sized up to 80% of the row.
 *
 * A small avatar sits on the *outside* of each bubble — robot for the assistant, person for the wearer — so the two
 * sides are identifiable without relying on position and colour alone (owner follow-up 2026-10-17). It is a rendered
 * control, not a text label: the role name travels in the avatar's content description.
 */
@Composable
private fun MessageBubble(entry: ConversationEntry) {
    val side = sideFor(entry.role)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = spokenMessage(entry.role, entry.text)
        },
        horizontalArrangement = if (side == BubbleSide.END) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (side == BubbleSide.START) {
            RoleAvatar(entry.role)
            Spacer(Modifier.width(8.dp))
        }
        BoxWithConstraints {
            Surface(
                modifier = Modifier.widthIn(max = maxWidth * BUBBLE_MAX_WIDTH_FRACTION),
                shape = RoundedCornerShape(12.dp),
                color = if (entry.role == ConversationRole.USER) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(entry.text, modifier = Modifier.padding(12.dp))
            }
        }
        if (side == BubbleSide.END) {
            Spacer(Modifier.width(8.dp))
            RoleAvatar(entry.role)
        }
    }
}

/** The role avatar: a feminine face for the assistant, a person for the wearer; the bubble carries the spoken role. */
@Composable
private fun RoleAvatar(role: ConversationRole) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = if (role == ConversationRole.USER) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                // Face4 is the feminine face with a bun; the owner asked for a softer, female-looking assistant
                // avatar instead of the robot glyph (2026-10-17 follow-up).
                imageVector = if (role == ConversationRole.USER) Icons.Default.Person else Icons.Default.Face4,
                // Decorative: the bubble row's merged semantics already say "助手说：…" / "你说：…".
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (role == ConversationRole.USER) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
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
    var selected by remember { mutableStateOf<String?>(null) }
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
                // U-3: the whole row opens the full text; the delete verb keeps its own click target at the end.
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "查看技能") { selected = name },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

    selected?.let { name ->
        PhoneDetailPage(title = name, onBack = { selected = null }) {
            // Read again when the row is opened: the listing and the file can disagree if the grant or the file changed.
            when (val content = remember(name) { root.skillDirectory?.read(name) }) {
                is SkillContent.Text -> Text(content.body)
                SkillContent.Empty -> Text("（空技能文件）")
                else -> Text("未授权或文件不可读", color = MaterialTheme.colorScheme.error)
            }
        }
    }
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
    LockPinSection(
        loadPinConfigured = { root.lockPinConfigured },
        savePin = { root.saveLockPin(it) },
        clearPin = { root.clearLockPin() },
    )

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
    val detached by root.detachGate.enabled.collectAsStateWithLifecycle()
    var detachNotice by remember { mutableStateOf<String?>(null) }
    var detachFailed by remember { mutableStateOf(false) }

    // 0.4 Task 5 (`D199`): the host-only mode switch. Its change goes through `DetachGate`, not the settings form and
    // not the settings blob directly: the gate stops a running session first and refuses visibly if it cannot persist.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(DetachUi.SWITCH_TITLE)
            Text(DetachUi.SWITCH_SUMMARY, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = detached, onCheckedChange = { wanted ->
            val result = root.detachGate.set(wanted)
            detachFailed = !result.saved
            detachNotice = if (result.saved) {
                DetachUi.successText(result.stoppedSession)
            } else {
                DetachUi.errorText(requireNotNull(result.error), result.stoppedSession)
            }
        })
    }
    detachNotice?.let { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = if (detachFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = proactiveArmed,
            enabled = DetachUi.proactiveSwitchEnabled(detached),
            onCheckedChange = { root.proactiveArming.set(it) },
        )
        Spacer(Modifier.width(8.dp))
        Text("主动辅助（未唤醒时给一句短提示）")
    }
    if (detached) {
        Text(DetachUi.PROACTIVE_DETACHED_NOTE, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
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
        "关闭后不会自动打开；引擎缺失时提示照常显示、只是不出声。唤醒后的短提示音也受这个开关控制。",
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
