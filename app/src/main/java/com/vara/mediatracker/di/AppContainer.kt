package com.vara.mediatracker.di

import android.content.Context
import androidx.room.Room
import com.vara.mediatracker.BuildConfig
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.CloudSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
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

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "mediatracker.db")
            // Destructive fallback is a development convenience only; see MainActivity.
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

    /**
     * Sync manager is a factory, not a singleton: it captures credentials at
     * construction, and the user can edit them in Settings at any time. Caching
     * it would leave it pointing at stale keys after an edit.
     */
    fun syncManager(): CloudSyncManager = CloudSyncManager(
        mediaDao = database.mediaDao(),
        supabaseUrl = preferences.supabaseUrl,
        anonKey = preferences.supabaseAnonKey,
        syncKey = preferences.syncKey,
        tmdbApiKey = preferences.tmdbApiKey,
        client = httpClient
    )
}