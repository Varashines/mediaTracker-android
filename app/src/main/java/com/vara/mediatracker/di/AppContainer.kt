package com.vara.mediatracker.di

import android.content.Context
import androidx.room.Room
import com.vara.mediatracker.BuildConfig
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TVMazeClient
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.db.Migrations
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.CloudSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Manual dependency container.
 *
 * Deliberately a service locator rather than Hilt: the graph is a handful of
 * singletons, and an annotation processor would add build cost and ceremony
 * without buying anything here.
 *
 * The shared [HttpClient] matters more than it looks — previously every
 * `TMDBClient` constructed its own OkHttp engine, so opening one detail screen
 * spun up a fresh connection pool and dispatcher set.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * App-lifetime scope for work that must outlive a screen — e.g. a manual cloud sync.
     * A `rememberCoroutineScope()` would cancel the sync the moment the user navigates
     * away, surfacing "The coroutine scope left the composition" as a sync failure.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "mediatracker.db")
            .addMigrations(*Migrations.ALL)
            // Destructive fallback is a development convenience only, so a missing
            // Migration fails loudly in release rather than discarding a synced library.
            .apply { if (BuildConfig.DEBUG) fallbackToDestructiveMigration() }
            .build()
    }

    val preferences: PreferencesManager by lazy { PreferencesManager(appContext) }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    // Blob uploads must be key-stable: kotlinx omits a property at its default, and
                    // PostgREST rejects a batch whose objects disagree (PGRST102), so a title with a
                    // taste and one without could not be sent in the same request.
                    encodeDefaults = true
                })
            }
            expectSuccess = true
        }
    }

    val mediaDao get() = database.mediaDao()

    /**
     * TMDB clients are cheap but read the API key at call time, so they are
     * rebuilt whenever the stored key changes rather than cached.
     */
    fun tmdbClient(): TMDBClient = TMDBClient(preferences.tmdbApiKey, httpClient)

    val tvmaze: TVMazeClient by lazy { TVMazeClient(httpClient) }

    /**
     * Sync manager is a factory, not a singleton: it captures credentials at
     * construction, and the user can edit them in Settings at any time. Caching
     * it would leave it pointing at stale keys after an edit.
     */
    fun syncManager(): CloudSyncManager = CloudSyncManager(
        db = database,
        mediaDao = database.mediaDao(),
        supabaseUrl = preferences.supabaseUrl,
        anonKey = preferences.supabaseAnonKey,
        syncKey = preferences.syncKey,
        tmdbApiKey = preferences.tmdbApiKey,
        client = httpClient
    )
}