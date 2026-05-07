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

import android.content.Intent
import android.net.Uri
import eu.europa.ec.businesslogic.extension.toUri
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import org.koin.core.annotation.KoinViewModel

data class State(
    val screenTitle: String,

    val settingsItems: List<SettingsItemUi> = emptyList(),

    val appVersion: String = "",
    val changelogUrl: String?,

    /**
     * Accesa: when true, render the workshop-reset confirmation dialog.
     * Triggered by a long-press on the version text — a deliberately
     * non-discoverable affordance that the workshop facilitator uses
     * between participants.
     */
    val showResetDialog: Boolean = false,
) : ViewState

sealed class Event : ViewEvent {
    data object Pop : Event()
    data class ItemClicked(val itemType: SettingsMenuItemType) : Event()

    // Accesa: workshop reset
    data object AppVersionLongPressed : Event()
    data object ResetConfirmed : Event()
    data object ResetDismissed : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()

        data class OpenUrlExternally(val url: Uri) : Navigation()
    }

    data class ShareLogFile(val intent: Intent, val chooserTitle: String) : Effect()

    /**
     * Accesa: signal the screen to wipe all app data via
     * `ActivityManager.clearApplicationUserData()`. The OS kills the process
     * after the call returns, so there is no follow-up navigation — the next
     * launcher tap starts a fresh install.
     */
    data object ClearApplicationData : Effect()
}

@KoinViewModel
class SettingsViewModel(
    private val settingsInteractor: SettingsInteractor,
    private val resourceProvider: ResourceProvider,
) : MviViewModel<Event, State, Effect>() {
    override fun setInitialState(): State {
        val changelogUrl = settingsInteractor.getChangelogUrl()
        return State(
            screenTitle = resourceProvider.getString(R.string.settings_screen_title),
            settingsItems = settingsInteractor.getSettingsItemsUi(changelogUrl = changelogUrl),

            appVersion = settingsInteractor.getAppVersion(),
            changelogUrl = changelogUrl,
        )
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Pop -> setEffect { Effect.Navigation.Pop }

            is Event.ItemClicked -> handleSettingsMenuItemClicked(event.itemType)

            is Event.AppVersionLongPressed -> setState { copy(showResetDialog = true) }

            is Event.ResetDismissed -> setState { copy(showResetDialog = false) }

            is Event.ResetConfirmed -> {
                setState { copy(showResetDialog = false) }
                setEffect { Effect.ClearApplicationData }
            }
        }
    }

    private fun handleSettingsMenuItemClicked(itemType: SettingsMenuItemType) {
        when (itemType) {
            SettingsMenuItemType.RETRIEVE_LOGS -> {
                val logs = settingsInteractor.retrieveLogFileUris()
                if (logs.isNotEmpty()) {
                    setEffect {
                        Effect.ShareLogFile(
                            intent = Intent().apply {
                                action = Intent.ACTION_SEND_MULTIPLE
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, logs)
                                type = "text/*"
                            },
                            chooserTitle = resourceProvider.getString(R.string.settings_intent_chooser_logs_share_title)
                        )
                    }
                }
            }

            SettingsMenuItemType.CHANGELOG -> {
                val changelogUrl = viewState.value.changelogUrl
                if (changelogUrl != null) {
                    setEffect {
                        Effect.Navigation.OpenUrlExternally(
                            url = changelogUrl.toUri()
                        )
                    }
                }
            }
        }
    }
}