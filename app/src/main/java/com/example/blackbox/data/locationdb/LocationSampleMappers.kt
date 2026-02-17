package com.example.blackbox.data.locationdb

import com.example.blackbox.location.LocationSampleEvent

fun LocationSampleEvent.toEntity(): LocationSampleEntity {
    return LocationSampleEntity(
        receivedAtMs = receivedAtMs,
        fixTimeMs = fixTimeMs,
        provider = provider,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        speedAccuracyMps = speedAccuracyMps,
        bearingAccuracyDeg = bearingAccuracyDeg,
        engineMode = engineMode
    )
}
