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
 */
@Composable
internal fun SettingsShell(root: HostCompositionRoot, state: SessionUiState) {
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
    // The catalog read is a single snapshot per shell composition. Task 13 replaces it with the catalog view model's
    // observable state; until then nothing in this task can mutate the catalog while the settings tab is on screen.
    val catalog = remember { runCatching { root.modelCatalogRepository?.catalog() }.getOrNull() }
    val textVerified = remember(catalog) {
        runCatching { root.activeCatalogProfile() }.getOrNull()?.let { profile ->
            SettingsSummaries.latestVerified(
                profile.verifications,
                profile.revision,
                SettingsSummaries.TEXT_OPERATION,
            )
        } ?: false
    }
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
 */
@Composable
internal fun SettingsShellContent(
    homeModel: SettingsHomeModel,
    auditLines: () -> List<String> = { emptyList() },
    homeScrollState: ScrollState = rememberScrollState(),
    assistantBehavior: (@Composable (requestPop: () -> Unit, registerBackGuard: (() -> Unit) -> Unit) -> Unit)? = null,
    runtimeAppearance: (@Composable () -> Unit)? = null,
    permissionsSecurity: (@Composable (requestPop: () -> Unit, registerBackGuard: (() -> Unit) -> Unit) -> Unit)? = null,
) {
    val stack = remember { mutableStateListOf<SettingsRoute>() }
    var pageBackGuard by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun closePage() {
        pageBackGuard = null
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    SettingsHomeScreen(
        model = homeModel,
        onOpen = { stack.add(it) },
        auditLines = auditLines,
        scrollState = homeScrollState,
    )

    val route = stack.lastOrNull() ?: return
    PhoneDetailPage(
        title = route.title,
        // One guarded exit for the arrow and for the dialog's own system-back dismissal. The page registers the
        // guard; a page without one (About/placeholders) closes directly.
        onBack = { pageBackGuard?.invoke() ?: closePage() },
        // A form page must not wrap its controls in the detail page's text-selection container.
        selectionEnabled = route != SettingsRoute.AssistantBehavior &&
            route != SettingsRoute.RuntimeAppearance &&
            route != SettingsRoute.PermissionsSecurity,
    ) {
        when (route) {
            SettingsRoute.About -> AboutScreen(
                onOpenModelService = { stack.add(SettingsRoute.ModelService) },
            )
            SettingsRoute.AssistantBehavior -> {
                if (assistantBehavior == null) {
                    PlaceholderPage(route)
                } else {
                    assistantBehavior({ closePage() }) { guard -> pageBackGuard = guard }
                }
            }
            SettingsRoute.RuntimeAppearance -> {
                if (runtimeAppearance == null) PlaceholderPage(route) else runtimeAppearance()
            }
            SettingsRoute.PermissionsSecurity -> {
                if (permissionsSecurity == null) {
                    PlaceholderPage(route)
                } else {
                    permissionsSecurity({ closePage() }) { guard -> pageBackGuard = guard }
                }
            }
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
