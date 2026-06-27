package com.phoenix.citizen

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phoenix.citizen.ui.screens.AreaAlertsScreen
import com.phoenix.citizen.ui.screens.HistoryScreen
import com.phoenix.citizen.ui.screens.MapScreen
import com.phoenix.citizen.ui.screens.QuickReportScreen
import com.phoenix.citizen.ui.screens.ReportFormScreen
import com.phoenix.citizen.ui.screens.SettingsScreen
import com.phoenix.citizen.ui.theme.PhoenixTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Force Italian for Sicily audience — applied at Activity level so
        // Compose's stringResource() picks values-it/strings.xml.
        val locale = Locale("it")
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        setContent {
            PhoenixTheme {
                PhoenixAppScaffold(
                    requestedRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(EXTRA_OPEN_ROUTE)
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "open_route"
        const val ROUTE_MAP = "map"
    }
}

sealed class NavRoute(val route: String) {
    data object Quick : NavRoute("quick")
    data object Map : NavRoute("map")
    data object Alerts : NavRoute("alerts")
    data object History : NavRoute("history")
    data object Settings : NavRoute("settings")
    data object Form : NavRoute("form?lat={lat}&lon={lon}") {
        const val ARG_LAT = "lat"
        const val ARG_LON = "lon"
        fun build(lat: Double? = null, lon: Double? = null): String {
            val l1 = lat?.toString() ?: ""
            val l2 = lon?.toString() ?: ""
            return "form?lat=$l1&lon=$l2"
        }
    }
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val bottomTabs = listOf(
    BottomTab(NavRoute.Quick.route, R.string.nav_quick, Icons.Filled.Whatshot),
    BottomTab(NavRoute.Map.route, R.string.nav_map, Icons.Filled.Map),
    BottomTab(NavRoute.Alerts.route, R.string.nav_alerts, Icons.Filled.NotificationsActive),
    BottomTab(NavRoute.History.route, R.string.nav_history, Icons.Filled.History),
    BottomTab(NavRoute.Settings.route, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun PhoenixAppScaffold(
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    // Honor a route requested by a notification tap (e.g. open the map on a fire alert).
    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            navController.navigate(requestedRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onRouteConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            NavigationBar {
                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute?.startsWith(tab.route.substringBefore("?")) == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Quick.route,
            modifier = Modifier.padding(inner)
        ) {
            composable(NavRoute.Quick.route) {
                QuickReportScreen(
                    onMoreDetails = { navController.navigate(NavRoute.Form.build()) }
                )
            }
            composable(NavRoute.Map.route) {
                MapScreen(
                    onReportHere = { lat, lon ->
                        navController.navigate(NavRoute.Form.build(lat, lon))
                    }
                )
            }
            composable(NavRoute.Alerts.route) { AreaAlertsScreen() }
            composable(NavRoute.History.route) { HistoryScreen() }
            composable(NavRoute.Settings.route) { SettingsScreen() }
            composable(NavRoute.Form.route) { backStack ->
                val lat = backStack.arguments?.getString(NavRoute.Form.ARG_LAT)?.toDoubleOrNull()
                val lon = backStack.arguments?.getString(NavRoute.Form.ARG_LON)?.toDoubleOrNull()
                ReportFormScreen(
                    initialLat = lat,
                    initialLon = lon,
                    onSubmitted = { navController.popBackStack() }
                )
            }
        }
    }
}
