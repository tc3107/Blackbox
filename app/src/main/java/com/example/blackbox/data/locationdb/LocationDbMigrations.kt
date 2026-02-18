package com.example.blackbox.data.locationdb

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object LocationDbMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS location_samples_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    received_at_ms INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    fix_time_ms INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    best_accuracy_m REAL NOT NULL,
                    worst_accuracy_m REAL NOT NULL,
                    samples_merged_count INTEGER NOT NULL,
                    altitude_m REAL,
                    speed_mps REAL,
                    bearing_deg REAL,
                    speed_accuracy_mps REAL,
                    bearing_accuracy_deg REAL,
                    engine_mode TEXT NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO location_samples_new (
                    id,
                    received_at_ms,
                    last_seen_at_ms,
                    fix_time_ms,
                    provider,
                    lat,
                    lon,
                    best_accuracy_m,
                    worst_accuracy_m,
                    samples_merged_count,
                    altitude_m,
                    speed_mps,
                    bearing_deg,
                    speed_accuracy_mps,
                    bearing_accuracy_deg,
                    engine_mode
                )
                SELECT
                    id,
                    received_at_ms,
                    received_at_ms,
                    fix_time_ms,
                    provider,
                    lat,
                    lon,
                    accuracy_m,
                    accuracy_m,
                    1,
                    altitude_m,
                    speed_mps,
                    bearing_deg,
                    speed_accuracy_mps,
                    bearing_accuracy_deg,
                    engine_mode
                FROM location_samples
                """.trimIndent()
            )

            db.execSQL("DROP TABLE location_samples")
            db.execSQL("ALTER TABLE location_samples_new RENAME TO location_samples")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_location_samples_received_at_ms ON location_samples(received_at_ms)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_location_samples_fix_time_ms ON location_samples(fix_time_ms)"
            )
        }
    }
}
