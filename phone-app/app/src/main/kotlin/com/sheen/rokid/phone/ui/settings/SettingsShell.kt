package com.sheen.rokid.phone.ui.settings

import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sheen.rokid.phone.BuildConfig
import com.sheen.rokid.phone.app.HostCompositionRoot
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
    val missingPermissions = listOf(
        root.foregroundAppSource?.isGranted == false,
        root.notificationCategorySource?.isConnected == false,
        root.calendarSource?.isGranted == false,
        !Settings.canDrawOverlays(context),
    ).count { it }
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
    )
}

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
 */
@Composable
internal fun SettingsShellContent(
    homeModel: SettingsHomeModel,
    auditLines: () -> List<String> = { emptyList() },
    homeScrollState: ScrollState = rememberScrollState(),
) {
    var stack by remember { mutableStateOf(emptyList<SettingsRoute>()) }

    SettingsHomeScreen(
        model = homeModel,
        onOpen = { stack = stack + it },
        auditLines = auditLines,
        scrollState = homeScrollState,
    )

    val route = stack.lastOrNull() ?: return
    PhoneDetailPage(
        title = route.title,
        onBack = { stack = stack.dropLast(1) },
    ) {
        when (route) {
            SettingsRoute.About -> AboutScreen(
                onOpenModelService = { stack = stack + SettingsRoute.ModelService },
            )
            else -> {
                Text(route.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "后续任务接入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
