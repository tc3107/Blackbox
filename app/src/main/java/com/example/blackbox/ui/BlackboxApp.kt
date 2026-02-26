package com.example.blackbox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.blackbox.R
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.ui.screens.DatabaseScreen
import com.example.blackbox.ui.screens.DataValuesScreen
import com.example.blackbox.ui.screens.MainViewScreen
import com.example.blackbox.ui.screens.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackboxApp(
    settings: UiSettings,
    onCustomAccentSaved: (String?) -> Unit
) {
    val context = LocalContext.current
    val navigationPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(NAV_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    var currentRoute by rememberSaveable {
        mutableStateOf(
            navigationPrefs.getString(KEY_LAST_ROUTE, null)
                ?.takeIf { stored -> AppDestination.entries.any { it.route == stored } }
                ?: AppDestination.entries.first().route
        )
    }
    val currentDestination = AppDestination.fromRoute(currentRoute)

    LaunchedEffect(currentRoute) {
        navigationPrefs.edit().putString(KEY_LAST_ROUTE, currentRoute).apply()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(currentDestination.titleRes)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == currentDestination,
                        onClick = { currentRoute = destination.route },
                        icon = {
                            Icon(
                                painter = painterResource(id = destination.iconRes),
                                contentDescription = stringResource(destination.titleRes)
                            )
                        },
                        label = { Text(text = stringResource(destination.shortTitleRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (currentDestination) {
            AppDestination.MAIN_VIEW -> MainViewScreen(
                modifier = contentModifier,
                onOpenSettings = { currentRoute = AppDestination.SETTINGS.route }
            )
            AppDestination.DATABASE -> DatabaseScreen(modifier = contentModifier)
            AppDestination.SETTINGS -> SettingsScreen(
                settings = settings,
                onCustomAccentSaved = onCustomAccentSaved,
                modifier = contentModifier
            )
            AppDestination.DATA_VALUES -> DataValuesScreen(modifier = contentModifier)
        }
    }
}

private enum class AppDestination(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val shortTitleRes: Int,
    val iconRes: Int
) {
    MAIN_VIEW(
        route = "main_view",
        titleRes = R.string.nav_main_view,
        shortTitleRes = R.string.nav_short_main,
        iconRes = android.R.drawable.ic_menu_view
    ),
    DATABASE(
        route = "database",
        titleRes = R.string.nav_database,
        shortTitleRes = R.string.nav_short_db,
        iconRes = android.R.drawable.ic_menu_manage
    ),
    SETTINGS(
        route = "settings",
        titleRes = R.string.nav_settings,
        shortTitleRes = R.string.nav_short_set,
        iconRes = android.R.drawable.ic_menu_preferences
    ),
    DATA_VALUES(
        route = "data_values",
        titleRes = R.string.nav_data_values,
        shortTitleRes = R.string.nav_short_dbg,
        iconRes = android.R.drawable.ic_menu_info_details
    );

    companion object {
        fun fromRoute(route: String): AppDestination {
            if (route == "theme") return SETTINGS
            return entries.firstOrNull { it.route == route } ?: entries.first()
        }
    }
}

private const val NAV_PREFS_NAME = "blackbox_nav_state"
private const val KEY_LAST_ROUTE = "last_route"
