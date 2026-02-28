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

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
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
                    engine_mode TEXT NOT NULL,
                    CHECK(lat >= -90.0 AND lat <= 90.0),
                    CHECK(lon >= -180.0 AND lon <= 180.0),
                    CHECK(best_accuracy_m >= 0.0),
                    CHECK(worst_accuracy_m >= best_accuracy_m),
                    CHECK(samples_merged_count >= 1),
                    CHECK(speed_mps IS NULL OR speed_mps >= 0.0),
                    CHECK(speed_accuracy_mps IS NULL OR speed_accuracy_mps >= 0.0),
                    CHECK(bearing_accuracy_deg IS NULL OR bearing_accuracy_deg >= 0.0)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                WITH sanitized AS (
                    SELECT
                        id,
                        CASE WHEN received_at_ms < 0 THEN 0 ELSE received_at_ms END AS received_at_ms,
                        CASE WHEN last_seen_at_ms < 0 THEN 0 ELSE last_seen_at_ms END AS last_seen_raw_ms,
                        CASE WHEN fix_time_ms < 0 THEN 0 ELSE fix_time_ms END AS fix_time_ms,
                        provider,
                        CASE
                            WHEN lat != lat THEN 0.0
                            WHEN lat < -90.0 THEN -90.0
                            WHEN lat > 90.0 THEN 90.0
                            ELSE lat
                        END AS lat,
                        CASE
                            WHEN lon != lon THEN 0.0
                            WHEN lon < -180.0 THEN -180.0
                            WHEN lon > 180.0 THEN 180.0
                            ELSE lon
                        END AS lon,
                        CASE
                            WHEN best_accuracy_m != best_accuracy_m THEN 0.0
                            WHEN best_accuracy_m < 0.0 THEN 0.0
                            ELSE best_accuracy_m
                        END AS best_accuracy_m,
                        CASE
                            WHEN worst_accuracy_m != worst_accuracy_m
                                THEN CASE WHEN best_accuracy_m < 0.0 OR best_accuracy_m != best_accuracy_m THEN 0.0 ELSE best_accuracy_m END
                            WHEN worst_accuracy_m < CASE
                                WHEN best_accuracy_m < 0.0 OR best_accuracy_m != best_accuracy_m THEN 0.0
                                ELSE best_accuracy_m
                            END
                                THEN CASE
                                    WHEN best_accuracy_m < 0.0 OR best_accuracy_m != best_accuracy_m THEN 0.0
                                    ELSE best_accuracy_m
                                END
                            ELSE worst_accuracy_m
                        END AS worst_accuracy_m,
                        CASE
                            WHEN samples_merged_count < 1 THEN 1
                            ELSE samples_merged_count
                        END AS samples_merged_count,
                        altitude_m,
                        CASE
                            WHEN speed_mps IS NULL THEN NULL
                            WHEN speed_mps != speed_mps THEN NULL
                            WHEN speed_mps < 0.0 THEN 0.0
                            ELSE speed_mps
                        END AS speed_mps,
                        bearing_deg,
                        CASE
                            WHEN speed_accuracy_mps IS NULL THEN NULL
                            WHEN speed_accuracy_mps != speed_accuracy_mps THEN NULL
                            WHEN speed_accuracy_mps < 0.0 THEN 0.0
                            ELSE speed_accuracy_mps
                        END AS speed_accuracy_mps,
                        CASE
                            WHEN bearing_accuracy_deg IS NULL THEN NULL
                            WHEN bearing_accuracy_deg != bearing_accuracy_deg THEN NULL
                            WHEN bearing_accuracy_deg < 0.0 THEN 0.0
                            ELSE bearing_accuracy_deg
                        END AS bearing_accuracy_deg,
                        CASE
                            WHEN engine_mode IN ('LowPower', 'Active', 'Off') THEN engine_mode
                            ELSE 'Off'
                        END AS engine_mode
                    FROM location_samples
                )
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
                    CASE
                        WHEN last_seen_raw_ms < received_at_ms THEN received_at_ms
                        ELSE last_seen_raw_ms
                    END,
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
                FROM sanitized
                """.trimIndent()
            )

            db.execSQL("DROP TABLE location_samples")
            db.execSQL("ALTER TABLE location_samples_new RENAME TO location_samples")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_location_samples_received_at_ms ON location_samples(received_at_ms)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_location_samples_last_seen_at_ms ON location_samples(last_seen_at_ms)"
            )

            ensureDbMetadataSchema(db)
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

    private fun ensureDbMetadataSchema(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "db_metadata")) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS db_metadata (
                    id INTEGER NOT NULL PRIMARY KEY,
                    key_id TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    db_uuid TEXT NOT NULL,
                    day_utc TEXT NOT NULL,
                    row_count INTEGER NOT NULL,
                    min_received_at_ms INTEGER,
                    max_last_seen_at_ms INTEGER,
                    min_lat REAL,
                    max_lat REAL,
                    min_lon REAL,
                    max_lon REAL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        ensureMetadataColumn(db, "db_uuid", "TEXT NOT NULL DEFAULT ''")
        ensureMetadataColumn(db, "day_utc", "TEXT NOT NULL DEFAULT ''")
        ensureMetadataColumn(db, "row_count", "INTEGER NOT NULL DEFAULT 0")
        ensureMetadataColumn(db, "min_received_at_ms", "INTEGER")
        ensureMetadataColumn(db, "max_last_seen_at_ms", "INTEGER")
        ensureMetadataColumn(db, "min_lat", "REAL")
        ensureMetadataColumn(db, "max_lat", "REAL")
        ensureMetadataColumn(db, "min_lon", "REAL")
        ensureMetadataColumn(db, "max_lon", "REAL")
        ensureMetadataColumn(db, "updated_at_ms", "INTEGER NOT NULL DEFAULT 0")

        val nowMs = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR IGNORE INTO db_metadata (
                id,
                key_id,
                created_at_ms,
                db_uuid,
                day_utc,
                row_count,
                min_received_at_ms,
                max_last_seen_at_ms,
                min_lat,
                max_lat,
                min_lon,
                max_lon,
                updated_at_ms
            )
            VALUES (
                ${DbMetadataEntity.METADATA_ROW_ID},
                'unknown',
                $nowMs,
                lower(hex(randomblob(16))),
                '',
                0,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                $nowMs
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            UPDATE db_metadata
            SET
                key_id = CASE WHEN key_id = '' THEN 'unknown' ELSE key_id END,
                row_count = (SELECT COUNT(*) FROM location_samples),
                min_received_at_ms = (SELECT MIN(received_at_ms) FROM location_samples),
                max_last_seen_at_ms = (SELECT MAX(last_seen_at_ms) FROM location_samples),
                min_lat = (SELECT MIN(lat) FROM location_samples),
                max_lat = (SELECT MAX(lat) FROM location_samples),
                min_lon = (SELECT MIN(lon) FROM location_samples),
                max_lon = (SELECT MAX(lon) FROM location_samples),
                db_uuid = CASE WHEN db_uuid = '' THEN lower(hex(randomblob(16))) ELSE db_uuid END,
                day_utc = CASE
                    WHEN day_utc != '' THEN day_utc
                    WHEN (SELECT MAX(last_seen_at_ms) FROM location_samples) IS NOT NULL THEN
                        strftime('%Y-%m-%d', (SELECT MAX(last_seen_at_ms) FROM location_samples) / 1000, 'unixepoch')
                    ELSE strftime('%Y-%m-%d', created_at_ms / 1000, 'unixepoch')
                END,
                updated_at_ms = CASE
                    WHEN updated_at_ms <= 0 THEN $nowMs
                    ELSE updated_at_ms
                END
            WHERE id = ${DbMetadataEntity.METADATA_ROW_ID}
            """.trimIndent()
        )
    }

    private fun ensureMetadataColumn(db: SupportSQLiteDatabase, columnName: String, definition: String) {
        if (hasColumn(db, "db_metadata", columnName)) {
            return
        }
        db.execSQL("ALTER TABLE db_metadata ADD COLUMN $columnName $definition")
    }

    private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        db.query("PRAGMA table_info($tableName)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'").use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
