package com.phoenix.citizen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
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
import com.phoenix.citizen.ui.screens.HistoryScreen
import com.phoenix.citizen.ui.screens.MapScreen
import com.phoenix.citizen.ui.screens.QuickReportScreen
import com.phoenix.citizen.ui.screens.ReportFormScreen
import com.phoenix.citizen.ui.screens.SettingsScreen
import com.phoenix.citizen.ui.theme.PhoenixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhoenixTheme {
                PhoenixAppScaffold()
            }
        }
    }
}

sealed class NavRoute(val route: String) {
    data object Quick : NavRoute("quick")
    data object Map : NavRoute("map")
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
    BottomTab(NavRoute.History.route, R.string.nav_history, Icons.Filled.History),
    BottomTab(NavRoute.Settings.route, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun PhoenixAppScaffold() {
    val navController = rememberNavController()
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
