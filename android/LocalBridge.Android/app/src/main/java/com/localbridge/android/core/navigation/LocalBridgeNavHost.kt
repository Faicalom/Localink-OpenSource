package com.localbridge.android.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.localbridge.android.core.di.AppContainer
import com.localbridge.android.core.di.LocalBridgeViewModelFactory
import com.localbridge.android.features.chat.ChatViewModel
import com.localbridge.android.features.devices.DevicesViewModel
import com.localbridge.android.features.settings.SettingsViewModel
import com.localbridge.android.features.transfers.TransfersViewModel
import com.localbridge.android.ui.screens.ChatScreen
import com.localbridge.android.ui.screens.DevicesScreen
import com.localbridge.android.ui.screens.HomeScreen
import com.localbridge.android.ui.screens.HomeScreenViewModel
import com.localbridge.android.ui.screens.SettingsScreen
import com.localbridge.android.ui.screens.TransfersScreen

@Composable
fun LocalBridgeNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.Home.route,
        modifier = modifier
    ) {
        composable(AppRoute.Home.route) {
            val viewModel: HomeScreenViewModel = viewModel(
                factory = LocalBridgeViewModelFactory {
                    HomeScreenViewModel(
                        discoveryService = container.discoveryService,
                        connectionService = container.connectionService,
                        settingsRepository = container.settingsRepository,
                        trustedDevicesService = container.trustedDevicesService
                    )
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            HomeScreen(
                uiState = uiState,
                onOpenDevices = { navController.navigate(AppRoute.Devices.route) },
                onOpenChat = { navController.navigate(AppRoute.Chat.route) },
                onOpenTransfers = { navController.navigate(AppRoute.Transfers.route) }
            )
        }

        composable(AppRoute.Devices.route) {
            val viewModel: DevicesViewModel = viewModel(
                factory = LocalBridgeViewModelFactory {
                    DevicesViewModel(
                        discoveryService = container.discoveryService,
                        connectionService = container.connectionService,
                        trustedDevicesService = container.trustedDevicesService,
                        deviceRepository = container.deviceRepository
                    )
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            DevicesScreen(
                uiState = uiState,
                onRefresh = viewModel::refresh,
                onPairingTokenChanged = viewModel::updatePairingToken,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                onToggleTrust = viewModel::toggleTrust
            )
        }

        composable(AppRoute.Chat.route) {
            val viewModel: ChatViewModel = viewModel(
                factory = LocalBridgeViewModelFactory {
                    ChatViewModel(
                        chatService = container.chatService,
                        connectionService = container.connectionService
                    )
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ChatScreen(
                uiState = uiState,
                onDraftChanged = viewModel::updateDraftMessage,
                onSend = viewModel::send,
                onRetry = viewModel::retry
            )
        }

        composable(AppRoute.Transfers.route) {
            val viewModel: TransfersViewModel = viewModel(
                factory = LocalBridgeViewModelFactory {
                    TransfersViewModel(
                        fileTransferService = container.fileTransferService,
                        connectionService = container.connectionService,
                        settingsRepository = container.settingsRepository,
                        storageDirectories = container.storageDirectories
                    )
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            TransfersScreen(
                uiState = uiState,
                onPickFiles = viewModel::queueFiles,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onCancel = viewModel::cancel
            )
        }

        composable(AppRoute.Settings.route) {
            val viewModel: SettingsViewModel = viewModel(
                factory = LocalBridgeViewModelFactory {
                    SettingsViewModel(
                        settingsRepository = container.settingsRepository,
                        trustedDevicesService = container.trustedDevicesService,
                        chatService = container.chatService,
                        fileTransferService = container.fileTransferService,
                        deviceRepository = container.deviceRepository,
                        loggerService = container.loggerService,
                        storageDirectories = container.storageDirectories
                    )
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                uiState = uiState,
                permissionsController = container.permissionsController,
                onPreferredModeChanged = viewModel::updatePreferredMode,
                onLanguageChanged = viewModel::updateLanguage,
                onReceiveFolderLabelChanged = viewModel::updateReceiveFolderLabel,
                onReceiveTreeSelected = viewModel::updateReceiveTree,
                onClearReceiveTree = viewModel::clearReceiveTree,
                onDarkThemeChanged = viewModel::updateDarkTheme,
                onDeviceAliasChanged = viewModel::updateDeviceAlias,
                onRemoveTrustedDevice = viewModel::removeTrustedDevice,
                onClearChatHistory = viewModel::clearChatHistory,
                onClearTransferHistory = viewModel::clearTransferHistory
            )
        }
    }
}
