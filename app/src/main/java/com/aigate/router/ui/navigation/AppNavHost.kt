package com.aigate.router.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aigate.router.ui.design.AppScaffold
import com.aigate.router.ui.design.HelpSheet
import com.aigate.router.ui.viewmodel.GatewayViewModel

/** Маршруты приложения. Верхний уровень — табы, остальное — вложенные экраны. */
object Routes {
    const val OVERVIEW = "overview"
    const val RESOURCES = "resources"
    const val ROUTES = "routes"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"

    // Вложенные экраны (доступны из табов, живут в back stack)
    const val MODELS = "models"
    const val STATS = "stats"
    const val KEYS = "keys"
    const val ABOUT = "about"
    const val BACKUPS = "backups"
    const val CAPTURE = "capture"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem(Routes.OVERVIEW, "Обзор", Icons.Default.SpaceDashboard),
    TabItem(Routes.RESOURCES, "Ресурсы", Icons.Default.Hub),
    TabItem(Routes.ROUTES, "Маршруты", Icons.AutoMirrored.Filled.AltRoute),
    TabItem(Routes.ACTIVITY, "Активность", Icons.Default.Insights),
    TabItem(Routes.SETTINGS, "Настройки", Icons.Default.Settings),
)

/**
 * Корневая навигация: NavHost с настоящим back stack (системная кнопка «назад»
 * возвращает на предыдущий экран, а не закрывает приложение), адаптивная
 * навигация — NavigationRail на широких экранах, NavigationBar на компактных.
 */
@Composable
fun AppNavHost(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory()),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = currentRoute in tabs.map { it.route }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 720.dp
        if (expanded) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.background,
                    header = {
                        Text(
                            text = "AiGate",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    },
                ) {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AppNavGraph(navController, viewModel)
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (isTabRoute) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = { navController.switchTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    AppNavGraph(navController, viewModel)
                }
            }
        }
    }
}

/**
 * Переключение таба: сохраняем состояние покинутого таба, не плодим копии
 * в стеке и возвращаемся к корню при повторном тапе по активному табу.
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavGraph(
    navController: NavHostController,
    viewModel: GatewayViewModel,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.OVERVIEW,
        modifier = Modifier.fillMaxSize(),
    ) {
        tabDestinations(navController, viewModel)
        nestedDestinations(navController, viewModel)
    }
}

private fun NavGraphBuilder.tabDestinations(
    navController: NavHostController,
    viewModel: GatewayViewModel,
) {
    composable(Routes.OVERVIEW) {
        val snackbarHostState = remember { SnackbarHostState() }
        var showHelp by remember { mutableStateOf(false) }
        AppScaffold(
            title = "Обзор",
            onHelp = { showHelp = true },
            snackbarHostState = snackbarHostState,
        ) { m ->
            com.aigate.router.ui.screens.OverviewScreen(
                viewModel = viewModel,
                modifier = m,
                snackbarHostState = snackbarHostState,
            )
        }
        if (showHelp) {
            HelpSheet(
                title = "Обзор",
                sections = com.aigate.router.ui.screens.overviewHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
    composable(Routes.RESOURCES) {
        var showHelp by remember { mutableStateOf(false) }
        AppScaffold(title = "Ресурсы", onHelp = { showHelp = true }) { m ->
            com.aigate.router.ui.screens.ResourcesHubScreen(
                viewModel = viewModel,
                modifier = m,
            )
        }
        if (showHelp) {
            HelpSheet(
                title = "Ресурсы",
                sections = com.aigate.router.ui.screens.resourcesHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
    composable(Routes.ROUTES) {
        var showHelp by remember { mutableStateOf(false) }
        AppScaffold(title = "Маршруты", onHelp = { showHelp = true }) { m ->
            com.aigate.router.ui.screens.RoutesScreen(viewModel, m)
        }
        if (showHelp) {
            HelpSheet(
                title = "Маршруты",
                sections = com.aigate.router.ui.screens.routesHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
    composable(Routes.ACTIVITY) {
        var showHelp by remember { mutableStateOf(false) }
        AppScaffold(title = "Активность", onHelp = { showHelp = true }) { m ->
            com.aigate.router.ui.screens.ActivityScreen(viewModel, m)
        }
        if (showHelp) {
            HelpSheet(
                title = "Активность",
                sections = com.aigate.router.ui.screens.activityHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
    composable(Routes.SETTINGS) {
        var showHelp by remember { mutableStateOf(false) }
        AppScaffold(title = "Настройки", onHelp = { showHelp = true }) { m ->
            com.aigate.router.ui.screens.SettingsScreen(
                viewModel = viewModel,
                modifier = m,
                onOpenKeys = { navController.navigate(Routes.KEYS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenBackups = { navController.navigate(Routes.BACKUPS) },
                onOpenCapture = { navController.navigate(Routes.CAPTURE) },
            )
        }
        if (showHelp) {
            HelpSheet(
                title = "Настройки",
                sections = com.aigate.router.ui.screens.settingsHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
}

private fun NavGraphBuilder.nestedDestinations(
    navController: NavHostController,
    viewModel: GatewayViewModel,
) {
    composable(Routes.MODELS) {
        AppScaffold(title = "Модели", onBack = { navController.popBackStack() }) { m ->
            Box(m) { com.aigate.router.ui.screens.ModelsScreen(viewModel) }
        }
    }
    composable(Routes.STATS) {
        AppScaffold(title = "Статистика", onBack = { navController.popBackStack() }) { m ->
            Box(m) { com.aigate.router.ui.screens.StatsScreen(viewModel) }
        }
    }
    composable(Routes.KEYS) {
        com.aigate.router.ui.screens.KeyManagementScreen(onDismiss = { navController.popBackStack() })
    }
    composable(Routes.BACKUPS) {
        com.aigate.router.ui.screens.SettingsBackupScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(Routes.CAPTURE) {
        com.aigate.router.ui.screens.SettingsCaptureScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(Routes.ABOUT) {
        AppScaffold(title = "О программе", onBack = { navController.popBackStack() }) { m ->
            Box(m) { com.aigate.router.ui.screens.AboutScreen(viewModel) }
        }
    }
}
