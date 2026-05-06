package com.localink.android.ui

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
import com.localink.android.LocalinkApplication
import com.localink.android.core.navigation.AppRoute
import com.localink.android.core.navigation.LocalinkNavHost
import com.localink.android.models.LocalinkSettings
import com.localink.android.ui.theme.LocalinkTheme
import com.localink.android.ui.widgets.AppBottomBar

@Composable
fun LocalinkAndroidApp() {
    val application = LocalContext.current.applicationContext as LocalinkApplication
    val settings by application.container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = LocalinkSettings()
    )
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppRoute.Home.route
    val strings = rememberLocalinkStrings(settings.language)

    LocalinkTheme(darkTheme = settings.darkThemeEnabled) {
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
                LocalinkNavHost(
                    navController = navController,
                    container = application.container,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
