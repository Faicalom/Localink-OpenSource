package com.localbridge.android.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.localbridge.android.core.navigation.AppRoute
import com.localbridge.android.ui.LocalAppStrings

@Composable
fun AppBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    val items = listOf(
        BottomNavItem(AppRoute.Home, strings["nav_home"], Icons.Rounded.Home),
        BottomNavItem(AppRoute.Devices, strings["nav_devices"], Icons.Rounded.Devices),
        BottomNavItem(AppRoute.Chat, strings["nav_chat"], Icons.Rounded.ChatBubbleOutline),
        BottomNavItem(AppRoute.Transfers, strings["nav_transfers"], Icons.Rounded.FolderOpen),
        BottomNavItem(AppRoute.Settings, strings["nav_settings"], Icons.Rounded.Settings)
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 10.dp
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route.route,
                onClick = { onNavigate(item.route.route) },
                label = { Text(item.label) },
                icon = { Icon(item.icon, contentDescription = item.label) }
            )
        }
    }
}

private data class BottomNavItem(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector
)
