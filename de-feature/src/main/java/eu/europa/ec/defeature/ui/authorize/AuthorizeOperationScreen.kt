/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * AUTHORIZE_OPERATION confirmation screen. Renders ONLY the user-visible fields
 * mandated by `de-wallet-app-api.md` §AUTHORIZE_OPERATION step 4:
 * description / formatted amount / bankDisplayName ?? bic / operation type.
 * Never renders payer.iban or payer.holderName — those travel in the signed
 * envelope but the wallet stays bank-IBAN-blind at the UI layer (§Privacy).
 */

package eu.europa.ec.defeature.ui.authorize

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.delogic.envelope.OperationEnvelope
import eu.europa.ec.delogic.envelope.OperationType
import eu.europa.ec.delogic.envelope.Payee
import eu.europa.ec.uilogic.extension.finish
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
fun AuthorizeOperationScreen(
    navController: NavController,
    viewModel: AuthorizeOperationViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Content(
        state = state,
        effectFlow = viewModel.effect,
        onCancel = { viewModel.setEvent(Event.Cancel) },
        onConfirm = { viewModel.setEvent(Event.Confirm(context)) },
        onEffect = { effect ->
            when (effect) {
                is Effect.FireCallbackAndFinish -> {
                    if (effect.uri != Uri.EMPTY) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, effect.uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }
                    context.finish()
                }
            }
        },
    )

    LaunchedEffect(Unit) {
        viewModel.setEvent(Event.OnEnter)
    }
}

@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onEffect: (Effect) -> Unit,
) {
    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is State.ReadyToConfirm -> RouteConfirmBody(
                    envelope = state.envelope,
                    enabled = true,
                    onCancel = onCancel,
                    onConfirm = onConfirm,
                )

                is State.Signing -> RouteConfirmBody(
                    envelope = state.envelope,
                    enabled = false,
                    onCancel = onCancel,
                    onConfirm = onConfirm,
                )

                is State.FailedEarly -> CircularProgressIndicator()
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach(onEffect).collect()
    }
}

/**
 * Dispatches to the per-`OperationType` confirm layout. `withdrawToWallet`
 * gets a distinct layout (big amount + bank line + disclaimer + simulated-SE
 * label, per `mobile-wallet.md` §M4a Confirm-screen render branch); every
 * other type renders via the original [ConfirmBody].
 */
@Composable
private fun RouteConfirmBody(
    envelope: OperationEnvelope,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    when (envelope.type) {
        OperationType.WITHDRAW_TO_WALLET -> WithdrawConfirmBody(
            envelope = envelope,
            enabled = enabled,
            onCancel = onCancel,
            onConfirm = onConfirm,
        )

        OperationType.OFFLINE_REDEEM -> OfflineRedeemConfirmBody(
            envelope = envelope,
            enabled = enabled,
            onCancel = onCancel,
            onConfirm = onConfirm,
        )

        OperationType.TOP_UP,
        OperationType.REDEEM,
        OperationType.PAYMENT -> ConfirmBody(
            envelope = envelope,
            enabled = enabled,
            onCancel = onCancel,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun ConfirmBody(
    envelope: OperationEnvelope,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = titleFor(envelope.type),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        // Render branches on envelope.type per de-wallet-app-api.md §AUTHORIZE_OPERATION
        // step 4. The wallet stays bank-IBAN-blind in every branch — payer.iban /
        // payer.holderName / payee.merchantId are NEVER displayed even though the
        // signed JWT carries the full envelope verbatim.
        when (envelope.type) {
            OperationType.PAYMENT -> envelope.payee?.let { PaymentPrimary(it) }
            OperationType.TOP_UP, OperationType.REDEEM -> Text(
                text = envelope.description,
                style = MaterialTheme.typography.bodyLarge,
            )
            // withdrawToWallet renders via [WithdrawConfirmBody] and
            // offlineRedeem via [OfflineRedeemConfirmBody] — RouteConfirmBody
            // dispatches before this composable is ever called for them.
            OperationType.WITHDRAW_TO_WALLET, OperationType.OFFLINE_REDEEM -> Unit
        }

        Spacer(Modifier.height(8.dp))

        FieldRow(label = "Amount", value = formatAmount(envelope.amount, envelope.currency))
        FieldRow(label = "Bank", value = envelope.bankDisplayName ?: envelope.bic)
        if (envelope.type != OperationType.PAYMENT) {
            FieldRow(label = "Operation", value = labelFor(envelope.type))
        }

        Spacer(Modifier.height(24.dp))

        if (!enabled) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Confirm") }
            }
        }
    }
}

/**
 * Confirm-screen render branch for `type: "withdrawToWallet"`, per
 * `mobile-wallet.md` §M4a Confirm-screen render branch:
 *
 *     ┌────────────────────────────────────────┐
 *     │   Withdraw onto this device            │
 *     │   €25.00                                │
 *     │   From your account at Bank A           │
 *     │   <envelope.description verbatim>       │
 *     │   ⓘ This will reduce your online …      │
 *     │   [ Cancel ]            [ Confirm 👆 ]  │
 *     │   Simulated SE — workshop demo …        │
 *     └────────────────────────────────────────┘
 *
 * The disclaimer line and the "Simulated SE — workshop demo. Not a real
 * Secure Element." label are non-negotiable per the M4a handoff: anywhere
 * the simulated SE is the trust anchor must be labelled as such.
 *
 * Same privacy rule as every other branch: `payer.iban` and
 * `payer.holderName` are NEVER displayed even though the signed JWT
 * carries the full envelope verbatim.
 */
@Composable
private fun WithdrawConfirmBody(
    envelope: OperationEnvelope,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = titleFor(envelope.type),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = formatAmount(envelope.amount, envelope.currency),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "From your account at ${envelope.bankDisplayName ?: envelope.bic}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = envelope.description,
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = "ⓘ This will reduce your online balance by the same amount.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        if (!enabled) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Confirm") }
            }
        }

        Text(
            text = "Simulated SE — workshop demo. Not a real Secure Element.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Confirm-screen render branch for `type: "offlineRedeem"` (M4c).
 * Used for BOTH M4c-sync (recipient cashes in NFC-received tokens —
 * `envelope.serials` present) and M4c-self-redeem (citizen converts
 * own LIVE tokens back to online DE — `serials` absent). Layout
 * mirrors `mobile-wallet.md` §M4b+M4c handoff — big amount, bank
 * line, description verbatim, simulated-SE disclaimer.
 */
@Composable
private fun OfflineRedeemConfirmBody(
    envelope: OperationEnvelope,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isSync = !envelope.serials.isNullOrEmpty()
    val title = if (isSync) "Receive Digital Euro" else "Redeem to your account"
    val accountLine = if (envelope.targetPlane == "bank-balance") {
        "Into your bank balance at ${envelope.bankDisplayName ?: envelope.bic}"
    } else {
        "Into your Digital Euro at ${envelope.bankDisplayName ?: envelope.bic}"
    }
    val info = if (isSync) {
        "ⓘ ${envelope.serials!!.size} token(s) from the wallet's simulated SE — workshop demo only."
    } else {
        "ⓘ This will move offline tokens back to your online balance."
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = formatAmount(envelope.amount, envelope.currency),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = accountLine,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = envelope.description,
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = info,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        if (!enabled) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) { Text("Confirm") }
            }
        }

        Text(
            text = "Simulated SE — workshop demo. Not a real Secure Element.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Payment primary block per `mobile-wallet.md` §AUTHORIZE_OPERATION
 * `type: "payment"`:
 *
 *     Pay  <merchantName>
 *          <description, if present>
 *
 * `payee.merchantId` is never displayed — it's the merchant's NCB userId,
 * opaque to the user and signed only for bank-side cross-check.
 */
@Composable
private fun PaymentPrimary(payee: Payee) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pay",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = payee.merchantName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            payee.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

private fun titleFor(type: OperationType): String = when (type) {
    OperationType.TOP_UP -> "Authorise top-up"
    OperationType.REDEEM -> "Authorise redemption"
    OperationType.PAYMENT -> "Authorise payment"
    OperationType.WITHDRAW_TO_WALLET -> "Withdraw onto this device"
    // OfflineRedeem renders via [OfflineRedeemConfirmBody]; this fallback only
    // reaches ConfirmBody if a future branch forgets to dispatch.
    OperationType.OFFLINE_REDEEM -> "Authorise offline redeem"
}

private fun labelFor(type: OperationType): String = when (type) {
    OperationType.TOP_UP -> "Top up"
    OperationType.REDEEM -> "Redeem"
    OperationType.PAYMENT -> "Payment"
    OperationType.WITHDRAW_TO_WALLET -> "Withdraw"
    OperationType.OFFLINE_REDEEM -> "Offline redeem"
}

private fun formatAmount(cents: Long, currency: String): String {
    val whole = cents / 100
    val remainder = (cents % 100).toString().padStart(2, '0')
    val symbol = when (currency.uppercase()) { "EUR" -> "€"; else -> "$currency " }
    return "$symbol$whole.$remainder"
}
