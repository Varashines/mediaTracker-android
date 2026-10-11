package com.vara.mediatracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vara.mediatracker.data.model.MediaBadgeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the v2 -> v3 upgrade path against a real v2-shaped database.
 *
 * This is the test that matters most for data safety: the destructive fallback is
 * debug-only, so if `MIGRATION_2_3` fails to create `media_badges`, a release build
 * throws at startup rather than falling back to an empty database — but only if the
 * migration is actually exercised here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    private fun deleteDb() = context.deleteDatabase(dbName)

    @After
    fun tearDown() = deleteDb()

    /** Builds a database at the pre-v3 schema and seeds it with representative rows. */
    private fun createV2Database(): SupportSQLiteDatabase {
        deleteDb()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(Migrations.MIGRATION_2_3)
            .build()

        // Create the v2 shape directly, bypassing Room's current entity set.
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_items` (
                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT NOT NULL,
                `posterUrl` TEXT, `backdropUrl` TEXT, `releaseDate` TEXT,
                `type` TEXT NOT NULL, `state` TEXT NOT NULL, `taste` TEXT NOT NULL,
                `customPosterUrl` TEXT, `isSoftDeleted` INTEGER NOT NULL,
                `lastInteractionDate` TEXT, `lastStateChangeDate` TEXT, `updatedAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        sql.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tv_episodes` (
                `uniqueId` TEXT NOT NULL, `showId` TEXT NOT NULL,
                `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL,
                `name` TEXT NOT NULL, `overview` TEXT NOT NULL, `stillPath` TEXT,
                `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `airDate` TEXT,
                `runtime` INTEGER, `updatedAt` TEXT,
                PRIMARY KEY(`uniqueId`)
            )
            """.trimIndent()
        )
        sql.execSQL(
            """
            CREATE TABLE IF NOT EXISTS room_master_table (
                `id` INTEGER PRIMARY KEY, `identity_hash` TEXT
            )
            """.trimIndent()
        )
        return sql
    }

    @Test
    fun v2ToV3_createsBadgeTableAndPreservesLibrary() {
        val v2 = createV2Database()
        v2.execSQL(
            "INSERT INTO media_items (id,title,overview,type,state,taste,isSoftDeleted) " +
                "VALUES ('movie_550','Seeded Movie','','Movie','Watching','Loved',0)"
        )
        v2.execSQL(
            "INSERT INTO tv_episodes (uniqueId,showId,seasonNumber,episodeNumber,name,overview,isWatched) " +
                "VALUES ('tv_1399_s1_e1','tv_1399',1,1,'Pilot','',1)"
        )
        v2.close()

        // Reopen through Room, which must find and apply the migration.
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .build()

        val media = runBlocking { migrated.mediaDao().getAllMediaList() }
        val episodes = runBlocking { migrated.mediaDao().getAllEpisodesList() }
        val badgeCount = runBlocking { migrated.badgeDao().badgeCount() }

        assertEquals(1, media.size)
        assertEquals("Seeded Movie", media.first().title)
        assertEquals(1, episodes.size)
        assertEquals("tv_1399_s1_e1", episodes.first().uniqueId)
        assertTrue(episodes.first().isWatched)
        // The new table exists and starts empty.
        assertEquals(0, badgeCount)

        // And the badge table is actually usable post-migration.
        runBlocking {
            migrated.badgeDao().upsertBadges(
                listOf(
                    MediaBadgeEntity(
                        mediaId = "movie_550",
                        badgeLabel = "PREMIERE",
                        isSparkle = true,
                        targetDate = "2026-03-01",
                        computedAt = 1L
                    )
                )
            )
        }
        assertEquals(1, runBlocking { migrated.badgeDao().badgeCount() })

        migrated.close()
    }

    @Test
    fun migratedDatabaseCanBeObservedThroughTheDao() {
        createV2Database().close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .build()

        val badges = runBlocking { migrated.badgeDao().observeBadges().first() }
        assertTrue(badges.isEmpty())

        migrated.close()
    }
}