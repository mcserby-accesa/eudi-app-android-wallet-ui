/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.defeature.ui.authorize

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

/**
 * PR2 placeholder. PR6 replaces this with the real confirmation UI per
 * `specs/protocols/de-wallet-app-api.md` §AUTHORIZE_OPERATION:
 * description / amount / bankDisplayName ?? bic / operation type — no IBAN,
 * no holder name, ever. Cancel / Confirm buttons; Confirm gates biometric and
 * fires the callback URI back to the bank app.
 */
@Composable
fun AuthorizeOperationScreen(
    navController: NavController,
    viewModel: AuthorizeOperationViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Column {
                Text("DE authorise — PR2 placeholder")
                Text("envelope: ${state.envelope.take(48)}…")
                Text("state: ${state.state}")
                Text("callback: ${state.callback}")
            }
        }
    }
}
