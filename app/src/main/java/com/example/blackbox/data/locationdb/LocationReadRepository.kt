package com.example.blackbox.data.locationdb

interface LocationReadRepository {
    suspend fun queryRange(startInclusiveMs: Long, endInclusiveMs: Long): List<LocationSampleEntity>
}
