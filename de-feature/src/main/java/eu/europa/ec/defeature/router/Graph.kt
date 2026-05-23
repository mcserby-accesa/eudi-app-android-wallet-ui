/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.defeature.router

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import eu.europa.ec.defeature.ui.authorize.AuthorizeOperationScreen
import eu.europa.ec.defeature.ui.holdings.WalletHoldingsScreen
import eu.europa.ec.defeature.ui.qrconfig.QrConfigScreen
import eu.europa.ec.defeature.ui.receive.ReceiveOfflineScreen
import eu.europa.ec.defeature.ui.send.SendOfflineScreen
import eu.europa.ec.uilogic.navigation.DeScreens
import eu.europa.ec.uilogic.navigation.ModuleRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.featureDeGraph(navController: NavController) {
    navigation(
        startDestination = DeScreens.QrConfig.screenRoute,
        route = ModuleRoute.DeModule.route,
    ) {
        composable(
            route = DeScreens.QrConfig.screenRoute,
        ) {
            QrConfigScreen(
                navController = navController,
                viewModel = koinViewModel(),
            )
        }

        composable(
            route = DeScreens.AuthorizeOperation.screenRoute,
            arguments = listOf(
                navArgument("envelope") { type = NavType.StringType; defaultValue = "" },
                navArgument("state") { type = NavType.StringType; defaultValue = "" },
                navArgument("callback") { type = NavType.StringType; defaultValue = "" },
                navArgument("deliveryUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("deliveryToken") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val envelope = backStackEntry.arguments?.getString("envelope").orEmpty()
            val state = backStackEntry.arguments?.getString("state").orEmpty()
            val callback = backStackEntry.arguments?.getString("callback").orEmpty()
            val deliveryUrl = backStackEntry.arguments?.getString("deliveryUrl").orEmpty()
            val deliveryToken = backStackEntry.arguments?.getString("deliveryToken").orEmpty()
            AuthorizeOperationScreen(
                navController = navController,
                viewModel = koinViewModel {
                    parametersOf(envelope, state, callback, deliveryUrl, deliveryToken)
                },
            )
        }

        composable(
            route = DeScreens.WalletHoldings.screenRoute,
        ) {
            WalletHoldingsScreen(
                navController = navController,
                viewModel = koinViewModel(),
            )
        }

        composable(
            route = DeScreens.SendOffline.screenRoute,
        ) {
            SendOfflineScreen(
                navController = navController,
                viewModel = koinViewModel(),
            )
        }

        composable(
            route = DeScreens.ReceiveOffline.screenRoute,
        ) {
            ReceiveOfflineScreen(
                navController = navController,
                viewModel = koinViewModel(),
            )
        }
    }
}
