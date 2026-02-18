package com.example.blackbox.data.locationdb

import com.example.blackbox.location.LocationSampleEvent

fun LocationSampleEvent.toEntity(): LocationSampleEntity {
    return LocationSampleEntity(
        receivedAtMs = receivedAtMs,
        lastSeenAtMs = receivedAtMs,
        fixTimeMs = fixTimeMs,
        provider = provider,
        lat = lat,
        lon = lon,
        bestAccuracyM = accuracyM,
        worstAccuracyM = accuracyM,
        samplesMergedCount = 1,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        speedAccuracyMps = speedAccuracyMps,
        bearingAccuracyDeg = bearingAccuracyDeg,
        engineMode = engineMode
    )
}
