package com.example.blackbox.data.locationdb

import androidx.room.TypeConverter
import com.example.blackbox.location.LocationEngineMode

class LocationDbConverters {
    @TypeConverter
    fun locationEngineModeToString(value: LocationEngineMode): String {
        return value.name
    }

    @TypeConverter
    fun stringToLocationEngineMode(value: String): LocationEngineMode {
        return runCatching { LocationEngineMode.valueOf(value) }
            .getOrDefault(LocationEngineMode.Off)
    }
}
