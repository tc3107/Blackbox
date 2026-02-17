package com.example.blackbox.data.locationdb

import com.example.blackbox.location.LocationSampleEvent

interface LocationWriteRepository {
    suspend fun ingest(event: LocationSampleEvent)
}
