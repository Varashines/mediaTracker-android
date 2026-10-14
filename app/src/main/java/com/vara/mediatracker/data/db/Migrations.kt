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

    /**
     * v7 -> v8: discovery facets for the Discovery Hub (genres, language, studios,
     * watch providers). Stored as plain columns and backfilled by the metadata worker.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `genres` TEXT")
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `language` TEXT")
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `studios` TEXT")
            db.execSQL("ALTER TABLE `media_items` ADD COLUMN `providers` TEXT")
        }
    }

    /**
     * v8 -> v9: indices for the hot read paths.
     *
     * `media_items` is read on every screen with `WHERE isSoftDeleted = 0 ORDER BY
     * lastInteractionDate DESC`; `tv_episodes` is filtered by `showId` for per-show
     * lists, eligibility scans, and the enrichment `NOT EXISTS` subqueries. Purely
     * additive — no rows are touched.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_media_items_isSoftDeleted_lastInteractionDate` " +
                    "ON `media_items` (`isSoftDeleted`, `lastInteractionDate`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_media_items_state_isSoftDeleted` " +
                    "ON `media_items` (`state`, `isSoftDeleted`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tv_episodes_showId` ON `tv_episodes` (`showId`)"
            )
        }
    }

    /**
     * v9 -> v10: facet logo paths for networks/studios/providers. Purely additive — the
     * metadata worker fills the table as it enriches.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `facet_logos` (
                    `facet` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `logoPath` TEXT NOT NULL,
                    PRIMARY KEY(`facet`, `name`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * v10 -> v11: force one metadata re-enrichment pass so the new facet logos fill in.
     *
     * `getItemsNeedingMetadataEnrichment` only selects rows whose genres/language/providers
     * are NULL, so an already-enriched library would never re-fetch and the `facet_logos`
     * table would stay empty. Clearing `providers` drops every row back into that query;
     * the worker re-fetches details (region-specific providers included) and writes the
     * logo rows alongside. Self-healing and one-time.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE media_items SET providers = NULL")
        }
    }

    /**
     * v11 -> v12: re-enrich once so stored genres are standardized by the shared
     * GenreMapper (matching macOS `cachedGenres`). Same trigger as v11 — clearing
     * `providers` drops every row back into the enrichment query.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE media_items SET providers = NULL")
        }
    }

    /**
     * v12 -> v13: collections + membership (mirrors macOS's `MediaCollection`).
     *
     * The table/index SQL is copied verbatim from the generated `13.json` — Room validates
     * the migrated schema against it, so any drift fails at open time.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`systemImage` TEXT NOT NULL, `notes` TEXT, `isPinned` INTEGER NOT NULL, " +
                    "`isSmart` INTEGER NOT NULL, `smartRules` TEXT, `completedItemIds` TEXT NOT NULL, " +
                    "`isDeleted` INTEGER NOT NULL, `updatedAt` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collections_name` ON `collections` (`name`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `collection_items` (`collectionId` TEXT NOT NULL, " +
                    "`mediaId` TEXT NOT NULL, `isDeleted` INTEGER NOT NULL, `updatedAt` TEXT, " +
                    "PRIMARY KEY(`collectionId`, `mediaId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_collectionId` ON `collection_items` (`collectionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_mediaId` ON `collection_items` (`mediaId`)")
        }
    }

    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13
        )
}
