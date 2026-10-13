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

    /** v3 -> v4: per-season metadata so FINALE can use TMDB's declared season length. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `tv_seasons` (
                    `showId` TEXT NOT NULL,
                    `seasonNumber` INTEGER NOT NULL,
                    `episodeCount` INTEGER NOT NULL,
                    `name` TEXT,
                    `airDate` TEXT,
                    `updatedAt` TEXT,
                    PRIMARY KEY(`showId`, `seasonNumber`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tv_seasons_showId` ON `tv_seasons` (`showId`)"
            )
        }
    }

    /** v4 -> v5: episode air time of day, so air dates resolve to real instants. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `tv_episodes` ADD COLUMN `airTime` TEXT")
        }
    }

    /** v5 -> v6: precise next-airing instant sourced from TVMaze. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `nextAiringInstant` TEXT")
        }
    }

    /**
     * v6 -> v7: TVMaze air-instant parity with macOS.
     *
     * `tv_episodes.airstamp` carries the exact instant; `media_items.timezone` and
     * `media_items.network` let the shared [com.vara.mediatracker.util.AirTimeResolver]
     * interpret a real broadcast airtime and pick the right streaming rule.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `tv_episodes` ADD COLUMN `airstamp` TEXT")
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `timezone` TEXT")
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `network` TEXT")
        }
    }

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
