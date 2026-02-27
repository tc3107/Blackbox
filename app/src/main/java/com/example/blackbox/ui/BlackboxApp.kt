package com.example.blackbox.ui

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.example.blackbox.R
import com.example.blackbox.debug.MainThreadBlockTracker
import com.example.blackbox.data.settings.UiSettings
import com.example.blackbox.sharing.LocationSharingController
import com.example.blackbox.ui.components.NeoButton
import com.example.blackbox.ui.screens.DatabaseScreen
import com.example.blackbox.ui.screens.DataValuesScreen
import com.example.blackbox.ui.screens.MainViewScreen
import com.example.blackbox.ui.screens.SettingsScreen
import com.example.blackbox.ui.perf.ObserveUiPerformanceFrames
import com.example.blackbox.ui.perf.ProvideUiPerformanceTracking
import com.example.blackbox.ui.perf.UiPerfSection
import com.example.blackbox.ui.perf.UiPerformanceMonitor
import com.example.blackbox.ui.perf.UiPerformanceOverlay
import com.example.blackbox.ui.perf.uiPerfDraw
import com.example.blackbox.ui.theme.neomorphicShadow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackboxApp(
    settings: UiSettings,
    onCustomAccentSaved: (String?) -> Unit
) {
    val context = LocalContext.current
    var navStateRestored by rememberSaveable { mutableStateOf(false) }
    var currentRoute by rememberSaveable { mutableStateOf(AppDestination.entries.first().route) }
    val currentDestination = AppDestination.fromRoute(currentRoute)
    val navShape = RoundedCornerShape(26.dp)
    val uiPerfMonitor = remember { UiPerformanceMonitor() }
    var uiPerfOverlayVisible by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var appVisible by remember {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appVisible = true
                Lifecycle.Event.ON_STOP -> appVisible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        appVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(appVisible, currentDestination) {
        LocationSharingController.onMainViewVisible(
            appVisible && currentDestination == AppDestination.MAIN_VIEW
        )
    }

    LaunchedEffect(context) {
        val restored = withContext(Dispatchers.IO) {
            val prefs = context.applicationContext.getSharedPreferences(
                NAV_PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            )
            prefs.getString(KEY_LAST_ROUTE, null)
        }?.takeIf { stored -> AppDestination.entries.any { it.route == stored } }
        if (restored != null) {
            currentRoute = restored
        }
        navStateRestored = true
    }

    LaunchedEffect(currentRoute, navStateRestored, context) {
        if (!navStateRestored) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val prefs = context.applicationContext.getSharedPreferences(
                NAV_PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            )
            prefs.edit().putString(KEY_LAST_ROUTE, currentRoute).apply()
        }
    }

    fun toggleUiPerformanceOverlay() {
        val wasVisible = uiPerfOverlayVisible
        if (wasVisible) {
            val snapshot = uiPerfMonitor.snapshot()
            Log.d(
                "UiPerfOverlay",
                "overlay_close session_s=${snapshot.elapsedMs / 1000} fps=${String.format(java.util.Locale.US, "%.1f", snapshot.estimatedFps)} jank_pct=${String.format(java.util.Locale.US, "%.1f", snapshot.jankPercent)} route=$currentRoute"
            )
            snapshot.sections.take(3).forEachIndexed { index, section ->
                Log.d(
                    "UiPerfOverlay",
                    "overlay_close hotspot_${index + 1} name=${section.name} score=${String.format(java.util.Locale.US, "%.2f", section.score)} recomp=${section.recompositions} cAvgMs=${String.format(java.util.Locale.US, "%.2f", section.composeAvgMs)} dAvgMs=${String.format(java.util.Locale.US, "%.2f", section.drawAvgMs)}"
                )
            }
            MainThreadBlockTracker.top(limit = 3).forEachIndexed { index, block ->
                Log.d(
                    "UiPerfOverlay",
                    "overlay_close block_${index + 1} action=${block.signature} count=${block.count} maxMs=${block.maxMs} avgMs=${String.format(java.util.Locale.US, "%.1f", block.avgMs)}"
                )
            }
        }
        uiPerfOverlayVisible = !wasVisible
        Log.d(
            "UiPerfOverlay",
            "toggleUiPerformanceOverlay -> visible=$uiPerfOverlayVisible route=$currentRoute"
        )
        if (uiPerfOverlayVisible) {
            uiPerfMonitor.reset()
            MainThreadBlockTracker.reset()
        }
    }

    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        ProvideUiPerformanceTracking(
            monitor = uiPerfMonitor,
            enabled = uiPerfOverlayVisible
        ) {
            ObserveUiPerformanceFrames(
                monitor = uiPerfMonitor,
                enabled = uiPerfOverlayVisible
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        UiPerfSection("Top Menu Bar") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f))
                                    .uiPerfDraw("Top Menu Bar")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .height(56.dp)
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(currentDestination.titleRes),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.96f)
                                    )
                                    Text(
                                        text = "BLACKBOX",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .background(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.14f),
                                                    Color.White.copy(alpha = 0.05f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                )
                            }
                        }
                    },
                    bottomBar = {
                        UiPerfSection("Bottom Navigation") {
                            Box(
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(70.dp)
                                        .clip(navShape)
                                        .neomorphicShadow(
                                            shape = navShape,
                                            pressed = false,
                                            addBorder = false,
                                            depth = 2.dp,
                                            blurRadius = 4.dp
                                        )
                                        .uiPerfDraw("Bottom Navigation")
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
                                                .fillMaxHeight(0.9f),
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
                    }
                ) { innerPadding ->
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())

                    when (currentDestination) {
                        AppDestination.MAIN_VIEW -> UiPerfSection("Screen Main View") {
                            MainViewScreen(
                                modifier = contentModifier.uiPerfDraw("Screen Main View"),
                                onOpenSettings = { currentRoute = AppDestination.SETTINGS.route }
                            )
                        }
                        AppDestination.DATABASE -> UiPerfSection("Screen Database") {
                            DatabaseScreen(modifier = contentModifier.uiPerfDraw("Screen Database"))
                        }
                        AppDestination.SETTINGS -> UiPerfSection("Screen Settings") {
                            SettingsScreen(
                                settings = settings,
                                onCustomAccentSaved = onCustomAccentSaved,
                                modifier = contentModifier.uiPerfDraw("Screen Settings")
                            )
                        }
                        AppDestination.DATA_VALUES -> UiPerfSection("Screen Data Values") {
                            DataValuesScreen(
                                modifier = contentModifier.uiPerfDraw("Screen Data Values"),
                                perfOverlayVisible = uiPerfOverlayVisible,
                                onTogglePerfOverlay = { toggleUiPerformanceOverlay() }
                            )
                        }
                    }
                }
                UiPerformanceOverlay(
                    monitor = uiPerfMonitor,
                    visible = uiPerfOverlayVisible
                )
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
