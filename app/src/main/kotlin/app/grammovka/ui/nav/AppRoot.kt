package app.grammovka.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.grammovka.di.AppContainer
import app.grammovka.ui.catalog.CatalogScreen
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.progress.ProgressScreen
import app.grammovka.ui.settings.KeysScreen
import app.grammovka.ui.settings.SettingsScreen
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.today.TodayScreen
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object CatalogRoute
@Serializable object ProgressRoute
@Serializable object SettingsRoute
@Serializable object KeysRoute

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(TodayRoute, "Сегодня", GIcons.Today),
    Tab(CatalogRoute, "Каталог", GIcons.Catalog),
    Tab(ProgressRoute, "Прогресс", GIcons.Progress),
    Tab(SettingsRoute, "Настройки", GIcons.Settings),
)

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val onTab = tabs.any { t -> dest?.hierarchy?.any { it.hasRoute(t.route::class) } == true }

    Scaffold(
        bottomBar = {
            if (onTab) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        val selected = dest?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = GTheme.extra.accentText,
                                unselectedIconColor = GTheme.extra.textMuted,
                                unselectedTextColor = GTheme.extra.textMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TodayRoute, modifier = Modifier.padding(padding)) {
            composable<TodayRoute> { TodayScreen() }
            composable<CatalogRoute> { CatalogScreen() }
            composable<ProgressRoute> { ProgressScreen() }
            composable<SettingsRoute> {
                SettingsScreen(container, onOpenKeys = { nav.navigate(KeysRoute) })
            }
            composable<KeysRoute> { KeysScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}
