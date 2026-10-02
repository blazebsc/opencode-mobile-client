package com.logicedge.opencodemobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.ui.screens.ChatScreen
import com.logicedge.opencodemobile.ui.screens.ConnectScreen
import com.logicedge.opencodemobile.ui.screens.HelpScreen
import com.logicedge.opencodemobile.ui.screens.LandingScreen
import com.logicedge.opencodemobile.ui.screens.ScanQrScreen
import com.logicedge.opencodemobile.ui.screens.ServerFormScreen
import com.logicedge.opencodemobile.ui.screens.ServerListScreen
import com.logicedge.opencodemobile.ui.screens.SettingsScreen

object Routes {
    const val LANDING = "landing"
    const val SERVERS = "servers"
    const val SERVER_NEW = "servers/new"
    const val SERVER_EDIT = "servers/{id}/edit"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val CONNECT = "connect/{id}"
    const val CHAT = "chat/{serverId}/{sessionId}"
    const val SCAN = "scan"

    const val QR_RESULT_KEY = "qr_result"

    fun edit(id: String) = "servers/$id/edit"
    fun connect(id: String) = "connect/$id"
    fun chat(serverId: String, sessionId: String) = "chat/$serverId/$sessionId"
}

class AppViewModelFactory(private val repository: ServerRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ServerViewModel::class.java) ->
            ServerViewModel(repository) as T
        modelClass.isAssignableFrom(ConnectionViewModel::class.java) ->
            ConnectionViewModel(repository) as T
        modelClass.isAssignableFrom(ChatViewModel::class.java) ->
            ChatViewModel(repository) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}

@Composable
fun OpenCodeNav(
    repository: ServerRepository,
    isDarkTheme: Boolean,
) {
    val navController = rememberNavController()
    val factory = remember(repository) { AppViewModelFactory(repository) }
    val serverViewModel: ServerViewModel = viewModel(factory = factory)
    val connectionViewModel: ConnectionViewModel = viewModel(factory = factory)

    OpenCodeMobileTheme(darkTheme = isDarkTheme) {
        NavHost(navController = navController, startDestination = Routes.LANDING) {
            composable(Routes.LANDING) {
                LandingScreen(
                    serverViewModel = serverViewModel,
                    onConnect = { navController.navigate(Routes.connect(it)) },
                    onAddServer = { navController.navigate(Routes.SERVER_NEW) },
                    onManage = { navController.navigate(Routes.SERVERS) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onHelp = { navController.navigate(Routes.HELP) },
                )
            }
            composable(Routes.SERVERS) {
                ServerListScreen(
                    serverViewModel = serverViewModel,
                    onOpen = { navController.navigate(Routes.connect(it)) },
                    onAdd = { navController.navigate(Routes.SERVER_NEW) },
                    onEdit = { navController.navigate(Routes.edit(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SERVER_NEW) { entry ->
                val qrFromScan by entry.savedStateHandle
                    .getStateFlow(Routes.QR_RESULT_KEY, "")
                    .collectAsState()
                ServerFormScreen(
                    repository = repository,
                    serverViewModel = serverViewModel,
                    profileId = null,
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                    onScan = { navController.navigate(Routes.SCAN) },
                    qrFromScan = qrFromScan,
                    onScanConsumed = { entry.savedStateHandle.remove<String>(Routes.QR_RESULT_KEY) },
                )
            }
            composable(
                Routes.SERVER_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val qrFromScan by entry.savedStateHandle
                    .getStateFlow(Routes.QR_RESULT_KEY, "")
                    .collectAsState()
                ServerFormScreen(
                    repository = repository,
                    serverViewModel = serverViewModel,
                    profileId = entry.arguments?.getString("id"),
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                    onScan = { navController.navigate(Routes.SCAN) },
                    qrFromScan = qrFromScan,
                    onScanConsumed = { entry.savedStateHandle.remove<String>(Routes.QR_RESULT_KEY) },
                )
            }
            composable(Routes.SCAN) {
                ScanQrScreen(
                    onResult = { link ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle?.set(Routes.QR_RESULT_KEY, link)
                        navController.popBackStack()
                    },
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    connectionViewModel = connectionViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.HELP) {
                HelpScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.CONNECT,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ConnectScreen(
                    serverId = entry.arguments?.getString("id"),
                    serverViewModel = serverViewModel,
                    connectionViewModel = connectionViewModel,
                    repository = repository,
                    onChat = { serverId, sessionId ->
                        navController.navigate(Routes.chat(serverId, sessionId)) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.CHAT,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("sessionId") { type = NavType.StringType },
                ),
            ) { entry ->
                val chatViewModel: ChatViewModel = viewModel(factory = factory)
                ChatScreen(
                    serverId = entry.arguments?.getString("serverId"),
                    sessionId = entry.arguments?.getString("sessionId"),
                    repository = repository,
                    chatViewModel = chatViewModel,
                    connectionViewModel = connectionViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenServers = {
                        navController.navigate(Routes.SERVERS) {
                            popUpTo(Routes.LANDING)
                        }
                    },
                    onEditServer = { navController.navigate(Routes.edit(it)) },
                    onDisconnected = {
                        navController.navigate(Routes.LANDING) {
                            popUpTo(Routes.LANDING) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
