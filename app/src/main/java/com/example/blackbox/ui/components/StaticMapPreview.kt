package com.example.blackbox.ui.components

import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.cos
import kotlin.math.log2
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val OpenFreeMapStyleUrl = "https://tiles.openfreemap.org/styles/dark"
private const val EarthCircumferenceMeters = 40_075_016.686
private const val TileSizePx = 512.0
private const val MinRadiusMeters = 5.0
private const val MinVerticalSpanMeters = 900.0
private const val ExtraZoomOutFactor = 1.8
private const val MaxZoomLevel = 22.0
private const val TargetAreaSourceId = "bbx_target_area_source"
private const val TargetCenterSourceId = "bbx_target_center_source"
private const val TargetAreaFillLayerId = "bbx_target_area_fill_layer"
private const val TargetAreaStrokeLayerId = "bbx_target_area_stroke_layer"
private const val TargetCenterOuterLayerId = "bbx_target_center_outer_layer"
private const val TargetCenterInnerLayerId = "bbx_target_center_inner_layer"

enum class MapTargetType {
    USER,
    CONTACT,
    ZONE
}

private data class MapCirclePalette(
    val fillHex: String,
    val strokeHex: String,
    val centerOuterHex: String
)

private fun paletteForTarget(targetType: MapTargetType): MapCirclePalette = when (targetType) {
    MapTargetType.USER -> MapCirclePalette(
        fillHex = "#4785FF",
        strokeHex = "#3B82F6",
        centerOuterHex = "#4DA3FF"
    )
    MapTargetType.CONTACT -> MapCirclePalette(
        fillHex = "#2ECC71",
        strokeHex = "#27AE60",
        centerOuterHex = "#36D67A"
    )
    MapTargetType.ZONE -> MapCirclePalette(
        fillHex = "#9AA0A6",
        strokeHex = "#7D858C",
        centerOuterHex = "#B0B6BC"
    )
}

@Composable
fun StaticRadiusMapPreview(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    targetType: MapTargetType = MapTargetType.USER,
    modifier: Modifier = Modifier
) {
    val mapView = rememberMapViewWithLifecycle()
    val mapFallbackBackground = lerp(
        MaterialTheme.colorScheme.background,
        Color.Black,
        0.20f
    )
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var disposed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        disposed = false
        onDispose {
            disposed = true
            mapLibreMap = null
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val mapHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val boundedRadiusMeters = radiusMeters.coerceAtLeast(MinRadiusMeters)
        val zoomLevel = remember(latitude, boundedRadiusMeters, mapHeightPx) {
            // Fit vertically so map height spans 6x the radius (circle takes 1/3 of height).
            zoomForRadiusMeters(
                latitude = latitude,
                radiusMeters = boundedRadiusMeters,
                mapHeightPx = mapHeightPx
            )
        }

        LaunchedEffect(mapLibreMap, latitude, longitude, zoomLevel, targetType) {
            mapLibreMap?.let { map ->
                map.getStyle { style ->
                    upsertTargetCircleLayers(
                        style = style,
                        latitude = latitude,
                        longitude = longitude,
                        radiusMeters = boundedRadiusMeters,
                        targetType = targetType
                    )
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(latitude, longitude))
                    .zoom(zoomLevel)
                    .build()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(mapFallbackBackground)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(mapFallbackBackground),
                factory = {
                    mapView.apply {
                        setBackgroundColor(mapFallbackBackground.toArgb())
                        setOnTouchListener { _, _ -> true }
                        disableMapOrnamentsForPreview()
                        getMapAsync { map ->
                            if (disposed) return@getMapAsync
                            map.uiSettings.setAllGesturesEnabled(false)
                            map.uiSettings.isCompassEnabled = false
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isAttributionEnabled = false
                            map.uiSettings.isRotateGesturesEnabled = false
                            map.uiSettings.isScrollGesturesEnabled = false
                            map.uiSettings.isTiltGesturesEnabled = false
                            map.uiSettings.isZoomGesturesEnabled = false
                            map.setStyle(OpenFreeMapStyleUrl) { style ->
                                if (disposed) return@setStyle
                                upsertTargetCircleLayers(
                                    style = style,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMeters = boundedRadiusMeters,
                                    targetType = targetType
                                )
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(LatLng(latitude, longitude))
                                    .zoom(zoomLevel)
                                    .build()
                            }
                            mapLibreMap = map
                        }
                    }
                },
                update = {
                    mapView.setBackgroundColor(mapFallbackBackground.toArgb())
                    mapLibreMap?.uiSettings?.apply {
                        setAllGesturesEnabled(false)
                        isCompassEnabled = false
                        isLogoEnabled = false
                        isAttributionEnabled = false
                        isRotateGesturesEnabled = false
                        isScrollGesturesEnabled = false
                        isTiltGesturesEnabled = false
                        isZoomGesturesEnabled = false
                    }
                }
            )
        }
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            id = View.generateViewId()
            onCreate(null)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        var mapDestroyed = false

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!mapDestroyed) mapView.onStart()
                Lifecycle.Event.ON_RESUME -> if (!mapDestroyed) mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> if (!mapDestroyed) mapView.onPause()
                Lifecycle.Event.ON_STOP -> if (!mapDestroyed) mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    if (!mapDestroyed) {
                        mapView.onDestroy()
                        mapDestroyed = true
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

        onDispose {
            lifecycle.removeObserver(observer)
            if (!mapDestroyed) {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
                mapDestroyed = true
            }
        }
    }

    return mapView
}

private fun MapView.disableMapOrnamentsForPreview() {
    post {
        // Defensive pass: disable ornament interactions even if the SDK changes internal ids.
        (this as ViewGroup).forEachChildRecursive { child ->
            val name = child.javaClass.simpleName
            if (
                name.contains("Attribution", ignoreCase = true) ||
                name.contains("Logo", ignoreCase = true) ||
                name.contains("Compass", ignoreCase = true)
            ) {
                child.isEnabled = false
                child.isClickable = false
                child.visibility = View.GONE
            }
        }
    }
}

private fun ViewGroup.forEachChildRecursive(block: (View) -> Unit) {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        block(child)
        if (child is ViewGroup) child.forEachChildRecursive(block)
    }
}

private fun upsertTargetCircleLayers(
    style: org.maplibre.android.maps.Style,
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    targetType: MapTargetType
) {
    val palette = paletteForTarget(targetType)
    val centerPoint = Point.fromLngLat(longitude, latitude)
    val areaPolygon = buildCirclePolygon(
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters
    )

    val areaSource = (style.getSource(TargetAreaSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(TargetAreaSourceId, Feature.fromGeometry(areaPolygon)).also(style::addSource)
    areaSource.setGeoJson(Feature.fromGeometry(areaPolygon))

    val centerSource = (style.getSource(TargetCenterSourceId) as? GeoJsonSource)
        ?: GeoJsonSource(TargetCenterSourceId, Feature.fromGeometry(centerPoint)).also(style::addSource)
    centerSource.setGeoJson(Feature.fromGeometry(centerPoint))

    val areaFillLayer = (style.getLayer(TargetAreaFillLayerId) as? FillLayer)
        ?: FillLayer(TargetAreaFillLayerId, TargetAreaSourceId).also(style::addLayer)
    areaFillLayer.setProperties(
        fillColor(AndroidColor.parseColor(palette.fillHex)),
        fillOpacity(0.20f)
    )

    val areaStrokeLayer = (style.getLayer(TargetAreaStrokeLayerId) as? LineLayer)
        ?: LineLayer(TargetAreaStrokeLayerId, TargetAreaSourceId).also(style::addLayer)
    areaStrokeLayer.setProperties(
        lineColor(AndroidColor.parseColor(palette.strokeHex)),
        lineOpacity(0.90f),
        lineWidth(2f)
    )

    val centerOuterLayer = (style.getLayer(TargetCenterOuterLayerId) as? CircleLayer)
        ?: CircleLayer(TargetCenterOuterLayerId, TargetCenterSourceId).also(style::addLayer)
    centerOuterLayer.setProperties(
        circleColor(AndroidColor.parseColor(palette.centerOuterHex)),
        circleOpacity(1.0f),
        circleRadius(5f)
    )

    val centerInnerLayer = (style.getLayer(TargetCenterInnerLayerId) as? CircleLayer)
        ?: CircleLayer(TargetCenterInnerLayerId, TargetCenterSourceId).also(style::addLayer)
    centerInnerLayer.setProperties(
        circleColor(AndroidColor.WHITE),
        circleOpacity(1.0f),
        circleRadius(2f)
    )
}

private fun buildCirclePolygon(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    segments: Int = 72
): Polygon {
    val earthRadius = 6_371_000.0
    val angularDistance = radiusMeters / earthRadius
    val latRad = Math.toRadians(latitude)
    val lonRad = Math.toRadians(longitude)
    val points = ArrayList<Point>(segments + 1)

    for (i in 0..segments) {
        val bearing = (2.0 * Math.PI * i) / segments.toDouble()
        val sinLat = kotlin.math.sin(latRad)
        val cosLat = kotlin.math.cos(latRad)
        val sinAng = kotlin.math.sin(angularDistance)
        val cosAng = kotlin.math.cos(angularDistance)

        val lat2 = kotlin.math.asin(
            sinLat * cosAng + cosLat * sinAng * kotlin.math.cos(bearing)
        )
        val lon2 = lonRad + kotlin.math.atan2(
            kotlin.math.sin(bearing) * sinAng * cosLat,
            cosAng - sinLat * kotlin.math.sin(lat2)
        )
        points.add(Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2)))
    }
    return Polygon.fromLngLats(listOf(points))
}

private fun zoomForRadiusMeters(
    latitude: Double,
    radiusMeters: Double,
    mapHeightPx: Float
): Double {
    val verticalSpanMeters = (radiusMeters * 6.0 * ExtraZoomOutFactor)
        .coerceAtLeast(MinVerticalSpanMeters)
    val metersPerPixel = verticalSpanMeters / mapHeightPx.coerceAtLeast(1f)
    val latitudeCos = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
    val zoom = log2((latitudeCos * EarthCircumferenceMeters) / (TileSizePx * metersPerPixel))
    return zoom.coerceIn(0.0, MaxZoomLevel)
}
