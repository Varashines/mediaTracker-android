package com.vara.mediatracker.data.db

import androidx.room.*
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items WHERE isSoftDeleted = 0 ORDER BY lastInteractionDate DESC")
    fun getAllActiveMedia(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE state = :state AND isSoftDeleted = 0 ORDER BY lastInteractionDate DESC")
    fun getMediaByState(state: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getMediaById(id: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(items: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(item: MediaItemEntity)

    @Query("UPDATE media_items SET state = :state, lastStateChangeDate = :now WHERE id = :id")
    suspend fun updateState(id: String, state: String, now: String)

    @Query("UPDATE media_items SET taste = :taste WHERE id = :id")
    suspend fun updateTaste(id: String, taste: String)

    // Episodes
    @Query("SELECT * FROM tv_episodes WHERE showId = :showId ORDER BY seasonNumber ASC, episodeNumber ASC")
    fun getEpisodesForShow(showId: String): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Query("UPDATE tv_episodes SET isWatched = :watched, watchedDate = :watchedDate WHERE uniqueId = :uniqueId")
    suspend fun setEpisodeWatched(uniqueId: String, watched: Boolean, watchedDate: String?)
}

@Database(entities = [MediaItemEntity::class, EpisodeEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
}
