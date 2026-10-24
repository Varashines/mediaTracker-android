package com.vara.mediatracker.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole library as one JSON file.
 *
 * This exists for the case cloud sync cannot cover: the phone is lost, the app is uninstalled, or
 * the credentials are gone. Sync restores a library only while a device with the same sync key
 * exists; a file on the user's own storage restores it with nothing else in the loop.
 *
 * It is a *backup*, not a sync format. Ids are the room primary keys and are preserved, so
 * importing into a library that already has the same title replaces it rather than duplicating it —
 * which also makes importing an old backup a safe undo for a bad edit.
 */
@Serializable
data class LibraryBackup(
    val schema: Int = 1,
    val app: String = "MediaTracker",
    val exportedAt: String,
    val media: List<MediaItemEntity> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val collectionItems: List<CollectionItemEntity> = emptyList(),
    val cycles: List<WatchCycleEntity> = emptyList(),
    val events: List<WatchEventEntity> = emptyList()
)

data class ImportSummary(
    val media: Int,
    val episodes: Int,
    val collections: Int,
    val history: Int
)

object LibraryBackupService {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Writes to the cache directory rather than asking for storage permission: the file is handed
     * straight to the system share sheet, which grants the receiving app read access to this one
     * URI. Nothing needs `WRITE_EXTERNAL_STORAGE`, which has not been grantable for years anyway.
     */
    suspend fun export(container: AppContainer, context: Context): File = withContext(Dispatchers.IO) {
        val db = container.database
        val backup = LibraryBackup(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            media = container.mediaDao.getAllActiveMedia().first(),
            episodes = container.mediaDao.getAllEpisodesList(),
            collections = db.collectionDao().getAllCollections(),
            collectionItems = db.collectionDao().getAllCollectionItems(),
            cycles = db.watchHistoryDao().getAllCycles(),
            events = db.watchHistoryDao().getAllEvents()
        )
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val file = File(context.cacheDir, "mediatracker-$stamp.json")
        file.writeText(json.encodeToString(LibraryBackup.serializer(), backup))
        file
    }

    suspend fun import(container: AppContainer, uri: Uri, context: Context): ImportSummary =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                ?: error("Could not read the selected file")
            val backup = json.decodeFromString(LibraryBackup.serializer(), text)
            require(backup.app == "MediaTracker") { "That file is not a MediaTracker backup" }

            // One transaction: a half-applied backup is a library that is neither the old one nor
            // the new one, and the user has no way to tell which rows landed.
            container.database.withTransaction {
                container.mediaDao.upsertMedia(backup.media)
                container.mediaDao.upsertEpisodes(backup.episodes)
                container.database.collectionDao().upsertCollections(backup.collections)
                container.database.collectionDao().upsertCollectionItems(backup.collectionItems)
                container.database.watchHistoryDao().upsertCycles(backup.cycles)
                container.database.watchHistoryDao().upsertEvents(backup.events)
            }
            ImportSummary(
                media = backup.media.size,
                episodes = backup.episodes.size,
                collections = backup.collections.size,
                history = backup.events.size
            )
        }
}
