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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.grammovka.di.AppContainer
import app.grammovka.ui.capture.CaptureMode
import app.grammovka.ui.capture.CaptureScreen
import app.grammovka.ui.catalog.CatalogScreen
import app.grammovka.ui.catalog.FoodEditScreen
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.progress.ProgressScreen
import app.grammovka.ui.review.ReviewRoot
import app.grammovka.ui.settings.KeysScreen
import app.grammovka.ui.settings.ProfileScreen
import app.grammovka.ui.settings.SettingsScreen
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.today.TodayScreen
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable object TodayRoute
@Serializable object CatalogRoute
@Serializable object ProgressRoute
@Serializable object SettingsRoute
@Serializable object KeysRoute
@Serializable object ProfileRoute
@Serializable object ReviewRoute
@Serializable data class FoodEditRoute(val id: Long = -1, val barcode: String = "")
@Serializable data class CaptureRoute(val date: String, val mode: String)

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
            composable<TodayRoute> {
                TodayScreen(
                    container,
                    onReview = { container.reviewInbox.pending = it; nav.navigate(ReviewRoute) },
                    onScan = { nav.navigate(CaptureRoute(it.toString(), CaptureMode.BARCODE.name)) },
                    onLabel = { nav.navigate(CaptureRoute(it.toString(), CaptureMode.LABEL.name)) },
                    onOpenProfile = { nav.navigate(ProfileRoute) },
                )
            }
            composable<CatalogRoute> {
                CatalogScreen(container, onOpen = { nav.navigate(FoodEditRoute(it)) }, onCreate = { nav.navigate(FoodEditRoute()) })
            }
            composable<ProgressRoute> { ProgressScreen(container) }
            composable<SettingsRoute> {
                SettingsScreen(container, onOpenKeys = { nav.navigate(KeysRoute) }, onOpenProfile = { nav.navigate(ProfileRoute) })
            }
            composable<KeysRoute> { KeysScreen(container, onBack = { nav.popBackStack() }) }
            composable<ProfileRoute> { ProfileScreen(container, onBack = { nav.popBackStack() }) }
            composable<FoodEditRoute> { entry ->
                val r = entry.toRoute<FoodEditRoute>()
                FoodEditScreen(container, r.id.takeIf { it > 0 }, r.barcode.ifEmpty { null }, onBack = { nav.popBackStack() })
            }
            composable<ReviewRoute> { ReviewRoot(container, onBack = { nav.popBackStack() }) }
            composable<CaptureRoute> { entry ->
                val r = entry.toRoute<CaptureRoute>()
                CaptureScreen(
                    container, LocalDate.parse(r.date), CaptureMode.valueOf(r.mode),
                    onReview = { req ->
                        container.reviewInbox.pending = req
                        nav.navigate(ReviewRoute) { popUpTo<CaptureRoute> { inclusive = true } }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
