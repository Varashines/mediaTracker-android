package com.vara.mediatracker.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * Added in the same change that introduced `media_badges`, which is the first schema
 * to move beyond v2. The Room schema JSON committed under `app/schemas` is the source
 * of truth for what each version looks like — CI fails if it is regenerated without
 * being committed, so a schema change cannot silently ship without a matching
 * Migration.
 *
 * There is deliberately no fallback here. `fallbackToDestructiveMigration()` is
 * debug-only (see AppContainer), so a missing migration crashes loudly in release
 * instead of discarding a library that was synced from the cloud.
 */
object Migrations {

    /** v2 -> v3: introduces the persisted smart-badge table. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `media_badges` (
                    `mediaId` TEXT NOT NULL,
                    `badgeLabel` TEXT NOT NULL,
                    `isSparkle` INTEGER NOT NULL,
                    `targetDate` TEXT,
                    `computedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`mediaId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_media_badges_badgeLabel` ON `media_badges` (`badgeLabel`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_media_badges_targetDate` ON `media_badges` (`targetDate`)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
