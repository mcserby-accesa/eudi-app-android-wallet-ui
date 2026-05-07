/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.dashboardfeature.ui.settings

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemLeadingContentDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.WrapListItem
import eu.europa.ec.uilogic.extension.openIntentChooser
import eu.europa.ec.uilogic.extension.openUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ContentScreen(
        navigatableAction = ScreenNavigateAction.BACKABLE,
        isLoading = false,
        onBack = { viewModel.setEvent(Event.Pop) }
    ) { paddingValues ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(navigationEffect, navController, context)
            },
            context = context,
            paddingValues = paddingValues,
        )
    }
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navController: NavController,
    context: Context,
) {
    when (navigationEffect) {
        is Effect.Navigation.Pop -> navController.popBackStack()

        is Effect.Navigation.OpenUrlExternally -> context.openUrl(uri = navigationEffect.url)
    }
}

@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    context: Context,
    paddingValues: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ContentTitle(
                modifier = Modifier.fillMaxWidth(),
                title = state.screenTitle,
            )

            SettingsItems(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                items = state.settingsItems,
                onEventSent = onEventSend,
            )
        }

        // Accesa: long-press the version text to open the workshop-reset
        // dialog. Tap is a no-op (no ripple) — the affordance is intentionally
        // non-discoverable so casual users don't trigger it.
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SPACING_MEDIUM.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { onEventSend(Event.AppVersionLongPressed) },
                ),
            text = state.appVersion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }

    if (state.showResetDialog) {
        ResetWalletDialog(
            onConfirm = { onEventSend(Event.ResetConfirmed) },
            onDismiss = { onEventSend(Event.ResetDismissed) },
        )
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)
                is Effect.ShareLogFile -> {
                    context.openIntentChooser(
                        effect.intent,
                        effect.chooserTitle
                    )
                }
                is Effect.ClearApplicationData -> {
                    // Wipes all app data (prefs, db, files, Keystore key bindings)
                    // and kills the process. Next launcher tap starts a fresh
                    // install. The OS handles the re-init — no follow-up
                    // navigation needed.
                    val activityManager = context
                        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    activityManager.clearApplicationUserData()
                }
            }
        }.collect()
    }
}

/**
 * Accesa: workshop-reset confirmation. Shown when the facilitator long-presses
 * the version text. Confirm wipes all wallet data via
 * `ActivityManager.clearApplicationUserData()` and kills the process; the next
 * launcher tap starts the wallet from scratch (back to QR-config).
 */
@Composable
private fun ResetWalletDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset wallet?") },
        text = {
            Text(
                "This deletes the held PID, the device key, and all wallet " +
                    "settings. The wallet will close and restart fresh on " +
                    "next launch. Use this to hand the device to the next " +
                    "workshop participant.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsItems(
    modifier: Modifier = Modifier,
    items: List<SettingsItemUi>,
    onEventSent: (Event) -> Unit,
) {
    Column(
        modifier = modifier
    ) {
        items.forEachIndexed { index, settingsItemUi ->
            WrapListItem(
                modifier = Modifier.fillMaxWidth(),
                item = settingsItemUi.data,
                onItemClick = {
                    onEventSent(
                        Event.ItemClicked(itemType = settingsItemUi.type)
                    )
                },
                throttleClicks = false,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                mainContentVerticalPadding = SPACING_MEDIUM.dp,
            )


            if (index != items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SPACING_SMALL.dp)
                )
            }
        }
    }
}

@ThemeModePreviews
@Composable
private fun SettingsScreenPreview() {
    PreviewTheme {
        val context = LocalContext.current

        val settingsItems = listOf(
            SettingsItemUi(
                type = SettingsMenuItemType.RETRIEVE_LOGS,
                data = ListItemDataUi(
                    itemId = stringResource(R.string.settings_screen_option_retrieve_logs_id),
                    mainContentData = ListItemMainContentDataUi.Text(
                        text = stringResource(R.string.settings_screen_option_retrieve_logs)
                    ),
                    leadingContentData = ListItemLeadingContentDataUi.Icon(
                        iconData = AppIcons.OpenNew
                    ),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowRight
                    )
                )
            )
        )

        Content(
            state = State(
                screenTitle = stringResource(R.string.settings_screen_title),
                settingsItems = settingsItems,
                appVersion = "1.0.0",
                changelogUrl = ""
            ),
            effectFlow = emptyFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            context = context,
            paddingValues = PaddingValues(SPACING_MEDIUM.dp)
        )
    }
}