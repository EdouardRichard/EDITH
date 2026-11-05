package com.sheen.rokid.phone.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.overlay.PhoneOnlyOverlayService
import com.sheen.rokid.phone.overlay.phoneOnlyStartFailureText
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.session.SessionUiState
import com.sheen.rokid.phone.ui.PhoneDetailPage

/**
 * 0.52 design §3.1: the settings navigation shell.
 *
 * This is the only host-coupled settings composable. It reads the live switches and catalog snapshot, projects the home
 * model through the pure [SettingsSummaries.from], and hands the audit loader to [SettingsShellContent] without invoking
 * it. The route stack, the always-composed home and the page dialog live in the host-free [SettingsShellContent].
 *
 * Task 7 ships placeholder bodies for every secondary page except About; Tasks 9-14 fill them through the same routes
 * and stack, without changing the home or the shell's navigation contract.
 *
 * Task 13 adds the three catalog pages through the [SettingsShellContent.modelCatalog] slot. The slot owns one
 * [com.sheen.rokid.phone.ui.settings.ModelCatalogViewModel] for as long as any catalog route is open: `leave()` cancels
 * in-flight work when the group closes and a re-entry composes a fresh view model that re-reads the catalog. The home
 * snapshot is re-read whenever that view model publishes a catalog, so the 模型服务 summary is never stale after a save.
 * Task 14 adds the model detail page to the same group; it is a catalog form (no text-selection container) whose saved
 * and confirmed-delete events pop it back to the model service, where the notice is shown.
 * [initialRoute] is the first-run prompt's 「去配置」 hand-off (PhoneScreen sets the settings tab and asks for 模型服务).
 */
@Composable
internal fun SettingsShell(
    root: HostCompositionRoot,
    state: SessionUiState,
    initialRoute: SettingsRoute? = null,
    onInitialRouteConsumed: () -> Unit = {},
) {
    val detached by root.detachGate.enabled.collectAsStateWithLifecycle()
    val appearance by root.phoneOnlyAppearance.collectAsStateWithLifecycle()
    val speechEnabled by root.speechEnabled.collectAsStateWithLifecycle()
    // 0.52 fix round 1 (design §4.2): the phone-only transition's auto-on speech write is not the page's switch
    // callback, so the host publishes its refusal separately; the shell maps it with the one central copy table.
    val speechAutoOnFailure by root.speechAutoOnFailure.collectAsStateWithLifecycle()
    // The service publishes what it actually did (0.5.1 C12/C14); the page never infers 「运行中」 from the overlay
    // permission. `startFailure` is mapped to the short Chinese reason before it reaches the host-free page.
    val phoneOnlyRunning by PhoneOnlyOverlayService.isRunning.collectAsStateWithLifecycle()
    val phoneOnlyStartFailure by PhoneOnlyOverlayService.startFailure.collectAsStateWithLifecycle()
    // The catalog read is a single snapshot per shell composition, re-read whenever the model-service view model
    // publishes a new catalog (T13): without the tick the home row would keep showing 未配置 after the first provider
    // was saved. The page group stays composed only while a catalog route is open, so this is the only writer path.
    var homeCatalogTick by remember { mutableIntStateOf(0) }
    val catalog = remember(homeCatalogTick) {
        runCatching { root.modelCatalogRepository?.catalog() }.getOrNull()
    }
    val textVerified = remember(catalog) {
        runCatching { root.activeCatalogProfile() }.getOrNull()?.let { profile ->
            SettingsSummaries.latestVerified(
                profile.verifications,
                profile.revision,
                SettingsSummaries.TEXT_OPERATION,
            )
        } ?: false
    }
    // 0.52 fix round 1 (design §3.6): the composition advertises `Capability.SystemOne`/`Embedding` unconditionally, so
    // `state.capabilityReasons` can never name a failed model bundle. The bundled-model gate is the real availability
    // source; collect it so the page's model rows can report 尚不可用 with the gate's reason. Hand-built compositions
    // without a readiness state keep the old behavior (nothing invented).
    val readiness = root.modelReadiness
    val provisioning = if (readiness == null) null else readiness.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    // 0.52 design §3.5/§6.1: the four system grants are not observable; returning from a system settings page
    // recomposes nothing by itself. `grantEpoch` re-reads them on every ON_RESUME (the old page's rule) and the
    // permission page's four rows repaint from that read.
    var grantEpoch by remember { mutableIntStateOf(0) }
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        grantEpoch++
    }
    // 0.52 design §3.4: returning from a system settings screen can change both the overlay grant and the service's
    // real state. MainActivity.onResume already syncs, but the settings page must not depend on another page having
    // done it: this observer re-reads the grant through the same policy and the published flows then repaint the page.
    // Calling sync while the permission is unchanged is a no-op (`KeepRunning`/`Stop`), so the extra call is safe.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, root) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                PhoneOnlyOverlayService.sync(context, root)
                grantEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // One read per resume for the home's count and the page's four rows, so the two cannot disagree.
    val grants = remember(grantEpoch) {
        listOf(
            root.foregroundAppSource?.isGranted == true,
            root.notificationCategorySource?.isConnected == true,
            root.calendarSource?.isGranted == true,
            Settings.canDrawOverlays(context),
        )
    }
    val missingPermissions = grants.count { !it }
    val permissionStates = remember(grantEpoch) {
        PermissionsSecurityUi.permissionStates(
            foregroundGranted = grants[0],
            notificationsConnected = grants[1],
            calendarGranted = grants[2],
            overlayGranted = grants[3],
        ).map { state ->
            // The page is host-free; the shell maps each row to its OS action here. Nothing on the page touches an
            // Intent. The calendar grant is a runtime permission, the other three open their own system pages.
            state.copy(
                onGrant = {
                    when (state.kind) {
                        PermissionKind.USAGE_ACCESS -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                        PermissionKind.NOTIFICATION_ACCESS -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        PermissionKind.CALENDAR -> requestCalendar.launch(Manifest.permission.READ_CALENDAR)
                        PermissionKind.OVERLAY -> runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                },
            )
        }
    }
    val unavailableCapabilities = state.capabilityReasons.size +
        state.executionChannels.count { !it.available }

    val home = SettingsSummaries.from(
        catalog = catalog,
        textVerified = textVerified,
        detached = detached,
        ballOpacityPercent = appearance.ballOpacityPercent,
        speechEnabled = speechEnabled,
        pendingPermissions = missingPermissions,
        unavailableCapabilities = unavailableCapabilities,
        versionName = BuildConfig.VERSION_NAME,
    )

    SettingsShellContent(
        homeModel = home,
        auditLines = { root.auditLog?.lines().orEmpty() },
        auditClear = { root.auditLog?.clear() },
        assistantBehavior = { requestPop, registerBackGuard ->
            AssistantBehaviorScreen(
                // The page reads the persisted snapshot once (design §4.4). A store that cannot even answer gets the
                // shipped defaults so the page renders; a following save reports the real refusal inline.
                load = { runCatching { root.storedSettings() }.getOrElse { SmartSettings() } },
                save = { settings -> saveThroughHost(root, settings) },
                // Immediate and outside the form; the gate's own result drives the inline failure line.
                setProactive = { enabled -> root.proactiveArming.set(enabled) },
                help = rememberHelpState(),
                requestPop = requestPop,
                registerBackGuard = registerBackGuard,
            )
        },
        runtimeAppearance = {
            RuntimeAppearanceScreen(
                detached = detached,
                onDetach = { enabled -> root.detachGate.set(enabled) },
                ballOpacity = appearance.ballOpacityPercent,
                bubbleOpacity = appearance.bubbleOpacityPercent,
                onPreviewOpacity = { ball, bubble -> root.previewPhoneOnlyOpacity(ball, bubble) },
                onCommitOpacity = { ball, bubble -> root.setPhoneOnlyOpacity(ball, bubble) },
                speechEnabled = speechEnabled,
                onSpeech = { enabled -> root.setSpeechEnabled(enabled) },
                phoneOnlyRunning = phoneOnlyRunning,
                startFailure = phoneOnlyStartFailure?.let(::phoneOnlyStartFailureText),
                help = rememberHelpState(),
                autoOnSpeechFailure = RuntimeAppearanceUi.autoOnSpeechText(speechAutoOnFailure),
            )
        },
        permissionsSecurity = { requestPop, registerBackGuard ->
            PermissionsSecurityScreen(
                // Same one-load contract as the other form page (design §4.4); a store that cannot answer gets the
                // shipped defaults so the page renders, and a following save reports the real refusal inline.
                load = { runCatching { root.storedSettings() }.getOrElse { SmartSettings() } },
                save = { settings -> saveThroughHost(root, settings) },
                // The live gate, not the stored value: after a failed safe-direction write the gate is off while the
                // blob may still say on. The page shows and then persists the live answer.
                noInterceptionLive = root.noInterceptionGate.enabled,
                savePin = { pin -> root.saveLockPin(pin) },
                clearPin = { root.clearLockPin() },
                pinConfigured = root.lockPinConfigured,
                permissionStates = permissionStates,
                help = rememberHelpState(),
                requestPop = requestPop,
                registerBackGuard = registerBackGuard,
            )
        },
        localCapabilities = {
            // The page is a pure projection of the live session state plus the model gate (design §3.6): the same
            // capability reasons and channel rows the 会话 panel reads, with no page-level load and therefore no second
            // read to get stale. `localCapabilityReasons` adds the two model rows' real availability; the page's
            // existing status path renders it, and before the first session the page names the four channels as
            // not-yet-probed instead of hiding the section.
            LocalCapabilitiesScreen(
                capabilityReasons = localCapabilityReasons(state.capabilityReasons, provisioning),
                channels = state.executionChannels,
                help = rememberHelpState(),
            )
        },
        initialRoute = initialRoute,
        onInitialRouteConsumed = onInitialRouteConsumed,
        modelCatalog = { route, openRoute, requestPop, registerBackGuard ->
            // One view model per catalog-page session (design §4.4). Leaving the catalog page group disposes it and
            // `leave()` cancels in-flight work; re-entering composes a fresh view model whose `enter()` re-reads the
            // catalog, so a fetch in flight when the user left can never land on the page afterwards.
            val catalogViewModel = remember { root.modelCatalogViewModel() }
            if (catalogViewModel == null) {
                ModelCatalogUnavailableScreen()
            } else {
                val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
                // Keep the always-composed settings home in step with the catalog the page just wrote.
                LaunchedEffect(catalogState.catalog) {
                    if (catalogState.catalog != null) homeCatalogTick++
                }
                DisposableEffect(catalogViewModel) {
                    catalogViewModel.enter()
                    onDispose { catalogViewModel.leave() }
                }
                val actions = remember(catalogViewModel) {
                    ModelCatalogActions(
                        refresh = catalogViewModel::refresh,
                        saveProvider = catalogViewModel::saveProvider,
                        deleteProvider = { providerId -> catalogViewModel.deleteProvider(providerId, null) },
                        setActiveProvider = catalogViewModel::setActiveProvider,
                        addManualModel = catalogViewModel::addManualModel,
                        updateModel = catalogViewModel::updateModel,
                        deleteModel = catalogViewModel::deleteModel,
                        selectModel = catalogViewModel::selectModel,
                        restoreDefaults = catalogViewModel::restoreDefaults,
                        testModel = catalogViewModel::testModel,
                        resetCatalog = catalogViewModel::resetCatalog,
                        dismissNotice = catalogViewModel::dismissNotice,
                        dismissRefreshSummary = catalogViewModel::dismissRefreshSummary,
                        confirmPending = catalogViewModel::confirmPending,
                        cancelPending = catalogViewModel::cancelPending,
                        consumeSaved = catalogViewModel::consumeSaved,
                        consumeSavedModel = catalogViewModel::consumeSavedModel,
                    )
                }
                when (route) {
                    SettingsRoute.ModelService -> ModelServiceScreen(
                        state = catalogState,
                        actions = actions,
                        help = rememberHelpState(),
                        onAddProvider = { openRoute(SettingsRoute.AddProvider) },
                        onOpenProviders = { openRoute(SettingsRoute.Providers) },
                        onOpenModel = { openRoute(SettingsRoute.ModelDetail(it)) },
                    )
                    SettingsRoute.Providers -> ProviderListScreen(
                        state = catalogState,
                        actions = actions,
                        help = rememberHelpState(),
                        onAddProvider = { openRoute(SettingsRoute.AddProvider) },
                        onEditProvider = { openRoute(SettingsRoute.ProviderEditor(it)) },
                    )
                    SettingsRoute.AddProvider -> ProviderEditorScreen(
                        state = catalogState,
                        actions = actions,
                        help = rememberHelpState(),
                        providerId = null,
                        requestPop = requestPop,
                        registerBackGuard = registerBackGuard,
                    )
                    is SettingsRoute.ProviderEditor -> ProviderEditorScreen(
                        state = catalogState,
                        actions = actions,
                        help = rememberHelpState(),
                        providerId = route.providerId,
                        requestPop = requestPop,
                        registerBackGuard = registerBackGuard,
                    )
                    is SettingsRoute.ModelDetail -> ModelDetailScreen(
                        state = catalogState,
                        actions = actions,
                        help = rememberHelpState(),
                        modelId = route.modelId,
                        requestPop = requestPop,
                        registerBackGuard = registerBackGuard,
                    )
                    else -> Unit
                }
            }
        },
    )
}

/**
 * Adapts the host's throwing save to [SettingsSaveResult] (0.52 design §4.2).
 *
 * The page deliberately does not `try/catch`: the host knows which refusal happened, and the mapped code is what the
 * inline error shows. `saveSmartSettings` can only refuse with the store-unavailable contract at this layer; every
 * other exception is the blob write refusing, which the page must still see rather than lose in a log.
 */
private fun saveThroughHost(root: HostCompositionRoot, settings: SmartSettings): SettingsSaveResult =
    runCatching { root.saveSmartSettings(settings) }.fold(
        onSuccess = { SettingsSaveResult(saved = true) },
        onFailure = { failure ->
            SettingsSaveResult(
                saved = false,
                errorCode = if (failure.message == SettingsSaveResult.ERROR_STORE_UNAVAILABLE) {
                    SettingsSaveResult.ERROR_STORE_UNAVAILABLE
                } else {
                    SettingsSaveResult.ERROR_SAVE_FAILED
                },
            )
        },
    )

/**
 * The host-free core of the settings shell: the route stack, the home and the page dialog.
 *
 * The home is composed **unconditionally**; the stack only decides whether a [PhoneDetailPage] dialog is drawn on top of
 * it. That is the design §4.4 contract - a page is a modal over a still-composed home, so returning keeps the home's
 * scroll position. [homeScrollState] therefore lives here rather than inside [SettingsHomeScreen]: a page push and pop
 * must not dispose and recreate it at offset 0. Leaving the settings tab still disposes the whole shell and resets the
 * home to the top (Task 7's approved behaviour).
 *
 * Host-derived input (the [SettingsHomeModel] and the audit loader) stays in [SettingsShell]; this composable has no
 * host dependency, which lets a Compose test drive the page push and back directly.
 *
 * [assistantBehavior] is the page seam Task 9 adds without changing the shell's existing contract: when it is null (all
 * Task 7 call sites, including the home/scroll tests) the route keeps its placeholder. When the host wires it, the page
 * receives a `requestPop` that closes the route and a `registerBackGuard` that installs a guarded back action. The
 * header arrow and the dialog's dismiss/back path both run `pageBackGuard ?: requestPop`, so a page can intercept the
 * one shared exit with its 「放弃修改？」 confirmation and then call `requestPop` itself. The stack is a state list so
 * pop does not depend on a stale captured `List` instance.
 *
 * [runtimeAppearance] is the same seam Task 10 adds for 运行与外观: the page needs no back guard, so the slot is a plain
 * composable that replaces Task 7's placeholder when the host supplies it. The null default keeps every existing
 * call site (including T7/T9 tests and the route stack tests) source-compatible.
 *
 * [permissionsSecurity] is Task 11's seam for 权限与安全: like the assistant page it owns a form, so it takes the same
 * `requestPop`/`registerBackGuard` pair and the shell's one guarded exit. Null keeps Task 7's placeholder.
 *
 * [auditLines]/[auditClear] are Task 7's loader plus Task 12's writer. The home receives the loader but must never call
 * it; only the 审计记录 route composes [AuditScreen], so the log is read when - and only when - that page is opened. One
 * lambda pair is enough for the test seam: pushing the route with a counting pair proves the home leaves it untouched
 * and the page reads once. Both default to a no-op for the compositions (T7/T9-T11 tests) that never open the page.
 *
 * [localCapabilities] is Task 12's seam for 本地能力: a read-only projection, so it is a plain composable with no back
 * guard. Null keeps Task 7's placeholder for every existing call site.
 */
@Composable
internal fun SettingsShellContent(
    homeModel: SettingsHomeModel,
    auditLines: () -> List<String> = { emptyList() },
    auditClear: () -> Unit = {},
    homeScrollState: ScrollState = rememberScrollState(),
    assistantBehavior: (@Composable (requestPop: () -> Unit, registerBackGuard: (() -> Unit) -> Unit) -> Unit)? = null,
    runtimeAppearance: (@Composable () -> Unit)? = null,
    permissionsSecurity: (@Composable (requestPop: () -> Unit, registerBackGuard: (() -> Unit) -> Unit) -> Unit)? = null,
    localCapabilities: (@Composable () -> Unit)? = null,
    initialRoute: SettingsRoute? = null,
    onInitialRouteConsumed: () -> Unit = {},
    modelCatalog: (@Composable (
        route: SettingsRoute,
        openRoute: (SettingsRoute) -> Unit,
        requestPop: () -> Unit,
        registerBackGuard: (() -> Unit) -> Unit,
    ) -> Unit)? = null,
) {
    val stack = remember { mutableStateListOf<SettingsRoute>() }
    var pageBackGuard by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 0.52 first-run upgrade: PhoneScreen asks for the model service page after cleaning the old payloads. The route is
    // pushed once and the request is consumed, so leaving/re-entering the settings tab does not re-open it.
    LaunchedEffect(initialRoute) {
        val requested = initialRoute ?: return@LaunchedEffect
        if (stack.none { it == requested }) stack.add(requested)
        onInitialRouteConsumed()
    }

    fun closePage() {
        pageBackGuard = null
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** Pushing a new route must not inherit the previous page's back guard (T9 review Minor). */
    fun openRoute(route: SettingsRoute) {
        pageBackGuard = null
        stack.add(route)
    }

    SettingsHomeScreen(
        model = homeModel,
        onOpen = { openRoute(it) },
        auditLines = auditLines,
        scrollState = homeScrollState,
    )

    val route = stack.lastOrNull() ?: return
    // T14: the model detail page is a catalog form too - it writes the catalog, has its own back guard and must not be
    // wrapped in the detail surface's text-selection container.
    val catalogRoute = route is SettingsRoute.ProviderEditor ||
        route is SettingsRoute.ModelDetail || when (route) {
            SettingsRoute.ModelService, SettingsRoute.Providers, SettingsRoute.AddProvider -> true
            else -> false
        }
    PhoneDetailPage(
        title = route.title,
        // One guarded exit for the arrow and for the dialog's own system-back dismissal. The page registers the
        // guard; a page without one (About/placeholders) closes directly.
        onBack = { pageBackGuard?.invoke() ?: closePage() },
        // A form page must not wrap its controls in the detail page's text-selection container.
        selectionEnabled = route != SettingsRoute.AssistantBehavior &&
            route != SettingsRoute.RuntimeAppearance &&
            route != SettingsRoute.PermissionsSecurity &&
            !catalogRoute,
    ) {
        when {
            catalogRoute -> {
                if (modelCatalog == null) {
                    PlaceholderPage(route)
                } else {
                    modelCatalog(route, ::openRoute, { closePage() }) { guard -> pageBackGuard = guard }
                }
            }
            route == SettingsRoute.About -> AboutScreen(
                onOpenModelService = { openRoute(SettingsRoute.ModelService) },
            )
            route == SettingsRoute.AssistantBehavior -> {
                if (assistantBehavior == null) {
                    PlaceholderPage(route)
                } else {
                    assistantBehavior({ closePage() }) { guard -> pageBackGuard = guard }
                }
            }
            route == SettingsRoute.RuntimeAppearance -> {
                if (runtimeAppearance == null) PlaceholderPage(route) else runtimeAppearance()
            }
            route == SettingsRoute.PermissionsSecurity -> {
                if (permissionsSecurity == null) {
                    PlaceholderPage(route)
                } else {
                    permissionsSecurity({ closePage() }) { guard -> pageBackGuard = guard }
                }
            }
            route == SettingsRoute.LocalCapabilities -> {
                if (localCapabilities == null) PlaceholderPage(route) else localCapabilities()
            }
            // The audit page is composed only while this route is on the stack, so its `remember(revision)` read below
            // never runs for the home or for any other route. The loader is the same lambda the home was handed.
            route == SettingsRoute.Audit -> AuditScreen(loadLines = auditLines, onClear = auditClear)
            else -> PlaceholderPage(route)
        }
    }
}

/** Task 7's placeholder for routes later tasks have not filled yet. */
@Composable
private fun PlaceholderPage(route: SettingsRoute) {
    Text(route.title, style = MaterialTheme.typography.titleLarge)
    Text(
        "后续任务接入",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
