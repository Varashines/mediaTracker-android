# MediaTracker Android Companion

Native Android companion app for [MediaTracker](https://github.com/Varashines/mediaTracker).

## Stack
- **UI**: Jetpack Compose (Material 3)
- **Language**: Kotlin 2.x
- **Local Persistence**: Room Database (SQLite)
- **Image Loading**: Coil 3
- **Sync**: Supabase PostgREST (cross-device sync with macOS MediaTracker)
- **Metadata**: The Movie Database (TMDB) API

## Testing

```bash
./gradlew :app:testDebugUnitTest        # JVM unit tests — safe, no device
./gradlew :app:connectedDebugAndroidTest # instrumented tests — READ THIS FIRST
```

> ⚠️ **Never run `connectedDebugAndroidTest` against a device holding real data.**
> The Android Gradle Plugin **installs the app onto every connected device and
> uninstalls it when the run ends**. That deletes `/data/data/<applicationId>`
> outright — the Room database, all SharedPreferences, and any Supabase/TMDB
> credentials entered in Settings are gone, and `firstInstallTime` resets so the
> loss is easy to mistake for a bad install rather than an uninstall.
>
> This has already destroyed a synced 809-title library once. The data is
> recoverable from Supabase, but only by re-entering the four credentials by hand.
>
> Instrumented tests are meant for CI emulators. Before running them against a
> personal device, either pull a backup first:
>
> ```bash
> adb shell "run-as com.vara.mediatracker tar cf - databases shared_prefs" > backup.tar
> ```
>
> or plan on re-syncing afterwards.
