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
import app.grammovka.ui.catalog.RecipeEditScreen
import app.grammovka.domain.model.FoodSource
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.ui.review.ReviewRequest
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.progress.ProgressScreen
import app.grammovka.ui.review.ReviewRoot
import app.grammovka.ui.settings.KeysScreen
import app.grammovka.ui.settings.ProfileScreen
import app.grammovka.ui.settings.SettingsScreen
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.today.TodayScreen
import app.grammovka.ui.weekly.WeeklyReviewScreen
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
@Serializable data class RecipeEditRoute(val id: Long = -1)
@Serializable data class WeeklyReviewRoute(val week: String = "", val auto: Boolean = false)
@Serializable data class CaptureRoute(val date: String, val mode: String, val meal: String = "")

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
    val rootScope = rememberCoroutineScope()

    /** Deferred items of the oldest date go to the confirm screen; Claude resolves them there. */
    val openPending: () -> Unit = {
        rootScope.launch {
            val b = container.pendingRepository.nextBatch() ?: return@launch
            val plan = container.quickLogPlanner.plan(b.text).let { if (b.meal != null) it.copy(meal = b.meal) else it }
            container.reviewInbox.pending = ReviewRequest(b.date, b.method, plan, pendingIds = b.ids, pendingTime = b.time)
            nav.navigate(ReviewRoute)
        }
    }
    val widgetAction by container.widgetAction.collectAsStateWithLifecycle()
    LaunchedEffect(widgetAction) {
        when (widgetAction) {
            app.grammovka.widget.GrammovkaWidget.ACTION_SCAN -> {
                container.widgetAction.value = null
                // Into the day the user is filling in (an open yesterday stays yesterday), not blindly today.
                val day = container.logRepository.defaultDay()
                nav.navigate(CaptureRoute(day.toString(), CaptureMode.BARCODE.name))
            }
            app.grammovka.widget.GrammovkaWidget.ACTION_ADD -> {
                // Today consumes it (focus + keyboard); make sure Today is on screen.
                nav.navigate(TodayRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
            }
        }
    }
    val openWeekly by container.openWeekly.collectAsStateWithLifecycle()
    LaunchedEffect(openWeekly) {
        openWeekly?.let { week ->
            container.openWeekly.value = null
            // From the Sunday notification: the review is written as soon as the screen opens.
            nav.navigate(WeeklyReviewRoute(week, auto = true)) { launchSingleTop = true }
        }
    }
    val openPendingSignal by container.openPending.collectAsStateWithLifecycle()
    LaunchedEffect(openPendingSignal) {
        if (openPendingSignal) {
            container.openPending.value = false
            openPending()
        }
    }

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
                    // Opens in the mode used last (barcode at first): one button instead of scanner and camera.
                    onCapture = { d, m -> nav.navigate(CaptureRoute(d.toString(), container.lastCaptureMode, m.name)) },
                    onOpenProfile = { nav.navigate(ProfileRoute) },
                    onOpenPending = openPending,
                )
            }
            composable<CatalogRoute> {
                val scope = rememberCoroutineScope()
                CatalogScreen(
                    container,
                    onOpen = { f ->
                        scope.launch {
                            val recipe = if (f.source == FoodSource.RECIPE) container.recipeRepository.byFoodId(f.id) else null
                            nav.navigate(if (recipe != null) RecipeEditRoute(recipe.id) else FoodEditRoute(f.id))
                        }
                    },
                    onCreate = { recipe -> nav.navigate(if (recipe) RecipeEditRoute() else FoodEditRoute()) },
                )
            }
            composable<ProgressRoute> { ProgressScreen(container, onOpenWeekly = { nav.navigate(WeeklyReviewRoute()) }) }
            composable<WeeklyReviewRoute> { entry ->
                val r = entry.toRoute<WeeklyReviewRoute>()
                WeeklyReviewScreen(
                    container, r.week.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, r.auto,
                    onBack = { nav.popBackStack() }, onOpenKeys = { nav.navigate(KeysRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(container, onOpenKeys = { nav.navigate(KeysRoute) }, onOpenProfile = { nav.navigate(ProfileRoute) })
            }
            composable<KeysRoute> { KeysScreen(container, onBack = { nav.popBackStack() }) }
            composable<ProfileRoute> { ProfileScreen(container, onBack = { nav.popBackStack() }) }
            composable<FoodEditRoute> { entry ->
                val r = entry.toRoute<FoodEditRoute>()
                FoodEditScreen(container, r.id.takeIf { it > 0 }, r.barcode.ifEmpty { null }, onBack = { nav.popBackStack() })
            }
            composable<RecipeEditRoute> { entry ->
                val r = entry.toRoute<RecipeEditRoute>()
                RecipeEditScreen(container, r.id.takeIf { it > 0 }, onBack = { nav.popBackStack() })
            }
            composable<ReviewRoute> { ReviewRoot(container, onBack = { nav.popBackStack() }) }
            composable<CaptureRoute> { entry ->
                val r = entry.toRoute<CaptureRoute>()
                CaptureScreen(
                    container, LocalDate.parse(r.date), CaptureMode.valueOf(r.mode),
                    onReview = { req ->
                        val meal = r.meal.takeIf { it.isNotEmpty() }?.let { app.grammovka.domain.model.Meal.valueOf(it) }
                        container.reviewInbox.pending = if (req.meal == null) req.copy(meal = meal) else req
                        nav.navigate(ReviewRoute) { popUpTo<CaptureRoute> { inclusive = true } }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
