package com.example.blackbox.sharing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SharingLogic {
    fun computeBackoffDelayMs(attemptCount: Int): Long {
        val base = 10_000.0
        val exponential = base * 2.0.pow((attemptCount - 1).coerceAtLeast(0).toDouble())
        return exponential.toLong().coerceAtMost(10 * 60_000L)
    }

    fun isValidClaim(claim: LocationClaimV1): Boolean {
        if (claim.version != SharingVersions.PAYLOAD_VERSION) return false
        if (claim.timestampMs <= 0L) return false
        if (!claim.lat.isFinite() || !claim.lon.isFinite()) return false
        if (claim.lat < -90.0 || claim.lat > 90.0) return false
        if (claim.lon < -180.0 || claim.lon > 180.0) return false
        return true
    }

    fun matchingZoneTags(lat: Double, lon: Double, zones: List<ShareZone>): List<String> {
        return zones
            .mapNotNull { zone ->
                val distance = haversineMeters(lat, lon, zone.centerLat, zone.centerLon)
                if (distance <= zone.radiusM.toDouble()) {
                    zone to distance
                } else {
                    null
                }
            }
            .sortedWith(compareBy<Pair<ShareZone, Double>> { it.second }.thenBy { it.first.name.lowercase() })
            .take(ZONE_TAG_LIMIT)
            .map { it.first.name }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) +
            cos(radLat1) * cos(radLat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }
}
