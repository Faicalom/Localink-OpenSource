package com.localbridge.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localbridge.android.LocalBridgeApplication
import com.localbridge.android.core.navigation.AppRoute
import com.localbridge.android.core.navigation.LocalBridgeNavHost
import com.localbridge.android.models.LocalBridgeSettings
import com.localbridge.android.ui.theme.LocalBridgeTheme
import com.localbridge.android.ui.widgets.AppBottomBar

@Composable
fun LocalBridgeAndroidApp() {
    val application = LocalContext.current.applicationContext as LocalBridgeApplication
    val settings by application.container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = LocalBridgeSettings()
    )
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppRoute.Home.route
    val strings = rememberLocalBridgeStrings(settings.language)

    LocalBridgeTheme(darkTheme = settings.darkThemeEnabled) {
        CompositionLocalProvider(
            LocalAppStrings provides strings,
            androidx.compose.ui.platform.LocalLayoutDirection provides if (strings.layoutDirection == LayoutDirection.Rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    AppBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (route != currentRoute) {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    restoreState = true
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                LocalBridgeNavHost(
                    navController = navController,
                    container = application.container,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
