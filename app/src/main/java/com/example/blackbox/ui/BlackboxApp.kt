package com.example.blackbox.ui

import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.example.blackbox.R
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.ui.components.NeoButton
import com.example.blackbox.ui.screens.DatabaseScreen
import com.example.blackbox.ui.screens.DataValuesScreen
import com.example.blackbox.ui.screens.MainViewScreen
import com.example.blackbox.ui.screens.SettingsScreen
import com.example.blackbox.ui.theme.neomorphicShadow

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
    val appBarShape = RoundedCornerShape(22.dp)
    val navShape = RoundedCornerShape(26.dp)

    LaunchedEffect(currentRoute) {
        navigationPrefs.edit().putString(KEY_LAST_ROUTE, currentRoute).apply()
    }

    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    NeoButton(
                        onClick = {},
                        latched = true,
                        shape = appBarShape,
                        modifier = Modifier
                            .fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = stringResource(currentDestination.titleRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        )
                    }
                }
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(navShape)
                            .neomorphicShadow(
                                shape = navShape,
                                pressed = false,
                                addBorder = false,
                                depth = 2.dp,
                                blurRadius = 4.dp
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppDestination.entries.forEach { destination ->
                            val selected = destination == currentDestination
                            NeoButton(
                                onClick = { currentRoute = destination.route },
                                latched = selected,
                                shape = CircleShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            ) {
                                Icon(
                                    painter = painterResource(id = destination.iconRes),
                                    contentDescription = stringResource(destination.titleRes),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
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
}

@Composable
private fun AutoFitTitleText(
    text: String,
    modifier: Modifier = Modifier
) {
    val base = MaterialTheme.typography.titleLarge
    val baseSize = if (base.fontSize.isSpecified) base.fontSize else 22.sp

    BoxWithConstraints(modifier = modifier) {
        var scaledSize by remember(text, maxWidth) { mutableStateOf(baseSize) }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = base.copy(fontSize = scaledSize),
            onTextLayout = { result ->
                if (result.hasVisualOverflow && scaledSize > 13.sp) {
                    val next = (scaledSize.value - 0.5f).coerceAtLeast(13f).sp
                    if (next < scaledSize) scaledSize = next
                }
            }
        )
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
