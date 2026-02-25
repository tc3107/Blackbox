package com.example.blackbox.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blackbox.R
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.ui.screens.DatabaseScreen
import com.example.blackbox.ui.screens.DataValuesScreen
import com.example.blackbox.ui.screens.LocationEngineScreen
import com.example.blackbox.ui.screens.LocationScreen
import com.example.blackbox.ui.screens.SharingScreen
import com.example.blackbox.ui.screens.ThemeScreen
import kotlinx.coroutines.launch

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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentRoute by rememberSaveable {
        mutableStateOf(
            navigationPrefs.getString(KEY_LAST_ROUTE, null)
                ?.takeIf { stored -> AppDestination.entries.any { it.route == stored } }
                ?: AppDestination.entries.first().route
        )
    }
    val currentDestination = AppDestination.fromRoute(currentRoute)
    val openMenuLabel = stringResource(R.string.menu_open_navigation)

    LaunchedEffect(currentRoute) {
        navigationPrefs.edit().putString(KEY_LAST_ROUTE, currentRoute).apply()
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    text = stringResource(R.string.app_brand_upper),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                AppDestination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(text = stringResource(destination.titleRes)) },
                        selected = destination == currentDestination,
                        onClick = {
                            currentRoute = destination.route
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(currentDestination.titleRes)) },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.semantics {
                                contentDescription = openMenuLabel
                            }
                        ) {
                            DrawerMenuGlyph()
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)

            when (currentDestination) {
                AppDestination.LOCATION_ENGINE -> LocationEngineScreen(modifier = contentModifier)
                AppDestination.LOCATION -> LocationScreen(modifier = contentModifier)
                AppDestination.SHARING -> SharingScreen(modifier = contentModifier)
                AppDestination.DATABASE -> DatabaseScreen(modifier = contentModifier)
                AppDestination.THEME -> ThemeScreen(
                    settings = settings,
                    onCustomAccentSaved = onCustomAccentSaved,
                    modifier = contentModifier
                )
                AppDestination.DATA_VALUES -> DataValuesScreen(modifier = contentModifier)
            }
        }
    }
}

@Composable
private fun DrawerMenuGlyph(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(18.dp)
            .height(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        repeat(3) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }
    }
}

private enum class AppDestination(
    val route: String,
    @StringRes val titleRes: Int
) {
    LOCATION_ENGINE(route = "location_engine", titleRes = R.string.nav_location_engine),
    LOCATION(route = "location", titleRes = R.string.nav_location),
    SHARING(route = "sharing", titleRes = R.string.nav_sharing),
    DATABASE(route = "database", titleRes = R.string.nav_database),
    THEME(route = "theme", titleRes = R.string.nav_theme),
    DATA_VALUES(route = "data_values", titleRes = R.string.nav_data_values);

    companion object {
        fun fromRoute(route: String): AppDestination {
            return entries.firstOrNull { it.route == route } ?: entries.first()
        }
    }
}

private const val NAV_PREFS_NAME = "blackbox_nav_state"
private const val KEY_LAST_ROUTE = "last_route"
