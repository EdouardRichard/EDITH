package com.sheen.rokid.phone.ui.settings

import android.provider.Settings
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
 * The model is a `remember` route stack. An empty stack renders [SettingsHomeScreen]; a non-empty one renders exactly one
 * [PhoneDetailPage] for the top route, so a page is a modal over the **still-composed** home. That is deliberate: the
 * home keeps its scroll position while a page is open, and a bottom-tab switch disposes the whole tab and therefore
 * resets it (Task 7's approved behaviour).
 *
 * This is the only host-coupled settings composable. It reads the live switches and catalog snapshot, projects the home
 * model through the pure [SettingsSummaries.from], and passes the audit loader without invoking it.
 *
 * Task 7 ships placeholder bodies for every secondary page except About; Tasks 9-14 fill them through the same routes
 * and stack, without changing the home or the shell's navigation contract.
 */
@Composable
internal fun SettingsShell(root: HostCompositionRoot, state: SessionUiState) {
    var stack by remember { mutableStateOf(emptyList<SettingsRoute>()) }

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

    val route = stack.lastOrNull()
    if (route == null) {
        SettingsHomeScreen(
            model = home,
            onOpen = { stack = stack + it },
            auditLines = { root.auditLog?.lines().orEmpty() },
        )
        return
    }

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
