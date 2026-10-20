package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.Taste
import com.vara.mediatracker.data.model.WatchEventEntity
import com.vara.mediatracker.util.WireTime

/**
 * The decisions a sync makes, separated from the store and the wire so they can be exercised as
 * scenarios on the JVM (`SyncReconcilerScenarioTests`, the counterpart of macOS's
 * `CloudSyncScenarioTests`).
 *
 * The rules are shared with macOS:
 *
 *  * conflicts are decided on the user-edit time, bounded by the row's write time, so a device
 *    with a fast clock cannot keep its rows permanently newer;
 *  * under a watermark, "absent from the response" means *unchanged*, not missing;
 *  * taste is compared canonically, so a legacy spelling is agreement rather than a conflict;
 *  * the ledger holds one event per viewing, whichever id spelling or clock offset it arrives
 *    with.
 */
object SyncReconciler {

    data class MediaReconcile(
        val outgoing: List<MediaItemEntity>,
        val incoming: List<MediaItemEntity>,
        val conflicts: List<String>
    )

    data class EpisodeReconcile(
        val outgoing: List<EpisodeEntity>,
        val incoming: List<EpisodeEntity>
    )

    data class EventReconcile(
        val outgoing: List<WatchEventEntity>,
        val incoming: List<WatchEventEntity>
    )

    // MARK: - Media

    fun reconcileMedia(
        local: List<MediaItemEntity>,
        remote: List<RemoteMedia>,
        since: String?
    ): MediaReconcile {
        val remoteById = remote.associateBy { it.media_id }
        val localById = local.associateBy { it.id }
        val outgoing = mutableListOf<MediaItemEntity>()
        val incoming = mutableListOf<MediaItemEntity>()
        val conflicts = mutableListOf<String>()

        for (row in local) {
            val cloud = remoteById[row.id]
            if (cloud == null) {
                // Under a watermarked fetch, "absent from the response" means *unchanged*, not
                // "not in the cloud" — so only push when this device actually edited the row
                // since the last sync. (With no watermark this is a full pull, where absent
                // really does mean missing.)
                if (isNewerThanWatermark(row, since)) outgoing.add(row)
                continue
            }
            // A differing state/taste *or* a differing soft-delete is a conflict. Without the
            // soft-delete term a delete on the other device was never seen here, so the title
            // simply stayed put.
            val differs = !row.state.equals(cloud.state, true) ||
                Taste.normalize(row.taste) != Taste.normalize(cloud.taste) ||
                row.isSoftDeleted != cloud.is_soft_deleted
            if (differs) {
                conflicts.add(row.title)
                // Compare on the *user-edit* time, mirroring macOS's `syncEditTime`: whichever
                // device edited last wins, capped by the cloud row's write time so a fast clock
                // cannot make an edit permanently newer.
                val localTime = WireTime.parseInstant(row.lastInteractionDate)
                    ?: WireTime.parseInstant(row.lastStateChangeDate)
                val remoteTime = WireTime.remoteEditTime(
                    lastInteraction = WireTime.parseInstant(cloud.last_interaction_date),
                    updatedAt = WireTime.parseInstant(cloud.updated_at)
                )
                if (localTime == null || remoteTime > localTime) {
                    incoming.add(fromRemote(cloud, row))
                } else {
                    outgoing.add(row)
                }
            } else if (row.dateAdded == null && cloud.date_added != null) {
                // Pure backfill: nothing conflicts, we just never learned when this was added.
                // date_added is immutable and one-way, so there is nothing to resolve — and
                // without this it never arrived, because fromRemote only runs for rows that
                // differ or are new.
                incoming.add(row.copy(dateAdded = cloud.date_added))
            }
        }
        for (cloud in remote) {
            if (!localById.containsKey(cloud.media_id)) incoming.add(fromRemote(cloud, null))
        }
        return MediaReconcile(outgoing, incoming, conflicts)
    }

    private fun fromRemote(
        remote: RemoteMedia,
        existing: MediaItemEntity?
    ): MediaItemEntity = MediaItemEntity(
        id = remote.media_id,
        title = existing?.title?.takeIf { it != remote.media_id } ?: remote.media_id,
        overview = existing?.overview ?: "",
        posterUrl = existing?.posterUrl,
        backdropUrl = existing?.backdropUrl,
        releaseDate = existing?.releaseDate,
        type = remote.type,
        state = remote.state,
        // Canonical, so a legacy spelling pulled from the cloud heals locally.
        taste = Taste.normalize(remote.taste),
        // Preserve locally-derived discovery metadata: the remote schema carries none of it, and
        // reconstructing without these wiped the TVMaze instant/network and the facets.
        nextAiringInstant = existing?.nextAiringInstant,
        timezone = existing?.timezone,
        network = existing?.network,
        genres = existing?.genres,
        language = existing?.language,
        studios = existing?.studios,
        providers = existing?.providers,
        customPosterUrl = remote.custom_poster_url,
        isSoftDeleted = remote.is_soft_deleted,
        lastInteractionDate = remote.last_interaction_date,
        lastStateChangeDate = remote.last_state_change_date,
        // Keep what we already know; take the cloud's (the Mac's dateAdded) when we don't —
        // that is how rows that predate the column get a real "added" time.
        dateAdded = existing?.dateAdded ?: remote.date_added,
        updatedAt = remote.updated_at,
        // The Mac's palette. Read-only here, and per field: a null remote must not clear a colour
        // this device already holds, and an older Mac path wrote only the primary.
        themeColorHex = remote.theme_color_hex ?: existing?.themeColorHex,
        themeMutedColorHex = remote.theme_muted_color_hex ?: existing?.themeMutedColorHex,
        themeSecondaryColorHex = remote.theme_secondary_color_hex ?: existing?.themeSecondaryColorHex
    )

    // MARK: - Episodes

    fun reconcileEpisodes(
        local: List<EpisodeEntity>,
        remote: List<RemoteEpisode>,
        since: String?
    ): EpisodeReconcile {
        val remoteById = remote.associateBy { MediaIds.canonicalEpisodeIdOrNull(it.episode_id) }
        val outgoing = mutableListOf<EpisodeEntity>()
        val incoming = mutableListOf<EpisodeEntity>()

        for (row in local) {
            val cloud = remoteById[MediaIds.canonicalEpisodeIdOrNull(row.uniqueId)]
            if (cloud == null) {
                // Watermarked fetch: absent means "unchanged", so only push a watch this
                // device made since the last sync.
                if (row.isWatched && changedSince(row.watchedDate, since)) outgoing.add(row)
                continue
            }
            // A corrected watch date counts, not just the flag: it drives the streak and the
            // monthly numbers, and it used to change only on the device it was edited on.
            if (WireTime.episodeNeedsUpdate(
                    isWatched = row.isWatched,
                    remoteIsWatched = cloud.is_watched,
                    watchedDate = WireTime.parseInstant(row.watchedDate),
                    remoteWatchedDate = WireTime.parseInstant(cloud.watched_date)
                )
            ) {
                val localTime = WireTime.parseInstant(row.watchedDate)
                val remoteTime = WireTime.parseInstant(cloud.updated_at)
                if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                    incoming.add(row.copy(isWatched = cloud.is_watched, watchedDate = cloud.watched_date))
                } else {
                    outgoing.add(row)
                }
            }
        }
        return EpisodeReconcile(outgoing, incoming)
    }

    // MARK: - Ledger

    fun reconcileEvents(
        local: List<WatchEventEntity>,
        remote: List<RemoteWatchEvent>,
        since: String?
    ): EventReconcile {
        val remoteById = remote.associateBy { it.event_id }
        val localById = local.associateBy { it.id }
        val outgoing = mutableListOf<WatchEventEntity>()
        val incoming = mutableListOf<WatchEventEntity>()

        for (row in local) {
            val cloud = remoteById[row.id]
            // Events are immutable facts: push when it carries a void the cloud doesn't (the
            // only forward change), or — under a full pull — when the cloud lacks it entirely.
            // With a watermark, "missing from the response" means unchanged.
            if (cloud != null) {
                if (row.voidedAt != null && cloud.voided_at == null) outgoing.add(row)
            } else if (changedSince(row.updatedAt, since)) {
                outgoing.add(row)
            }
        }
        for (cloud in remote) {
            val row = localById[cloud.event_id]
            if (row == null) {
                // Don't double-log a watch already covered locally: the Mac synthesises an event
                // for a watch that arrives without history, and that copy carries a different
                // dedup key — and, before the ids were canonicalised, a different id spelling.
                if (!hasEquivalentLocalEvent(local, cloud)) {
                    incoming.add(
                        WatchEventEntity(
                            id = cloud.event_id,
                            cycleId = cloud.cycle_id,
                            mediaId = cloud.media_id,
                            episodeId = cloud.episode_id,
                            watchedAt = cloud.watched_at,
                            source = cloud.source,
                            timezoneIdentifier = cloud.timezone_identifier,
                            runtimeMinutes = cloud.runtime_minutes,
                            voidedAt = cloud.voided_at,
                            deduplicationKey = cloud.deduplication_key,
                            isBackfilled = true,
                            updatedAt = cloud.updated_at
                        )
                    )
                }
            } else if (row.voidedAt == null && cloud.voided_at != null) {
                incoming.add(row.copy(voidedAt = cloud.voided_at, updatedAt = cloud.updated_at))
            }
        }
        return EventReconcile(outgoing, incoming)
    }

    /**
     * True when the ledger already holds this viewing.
     *
     * Episode ids are compared *canonically*: the legacy `{show}_{s}_{e}` and the `tv_…` spellings
     * used to be compared literally, so the same watch — recorded here and pulled back from the
     * Mac — counted as two. Stamps are compared as instants rather than by their date prefix,
     * because a stamp written from local wall-clock time can cross midnight, which is how one
     * viewing came to appear on two days in Year in Review.
     */
    private fun hasEquivalentLocalEvent(
        all: List<WatchEventEntity>,
        remote: RemoteWatchEvent
    ): Boolean {
        val targetEpisode = MediaIds.canonicalEpisodeIdOrNull(remote.episode_id)
        val remoteInstant = WireTime.parseInstant(remote.watched_at) ?: return false
        return all.any { local ->
            local.voidedAt == null && local.mediaId == remote.media_id &&
                MediaIds.canonicalEpisodeIdOrNull(local.episodeId) == targetEpisode &&
                WireTime.parseInstant(local.watchedAt)?.let { WireTime.isSameWatch(it, remoteInstant) } == true
        }
    }

    // MARK: - Shared

    /**
     * Whether a local stamp is newer than the pull watermark.
     *
     * Compares parsed instants, not the raw strings: the watermark comes from
     * `Instant.toString()` (which omits trailing zero fractions) while local stamps carry millis,
     * so a lexicographic compare is wrong.
     */
    fun changedSince(stamp: String?, watermark: String?): Boolean {
        if (watermark.isNullOrBlank()) return true
        val since = WireTime.parseInstant(watermark) ?: return true
        val value = WireTime.parseInstant(stamp) ?: return true
        return value.after(since)
    }

    fun isNewerThanWatermark(local: MediaItemEntity, watermark: String?): Boolean {
        if (watermark.isNullOrBlank()) return true
        val since = WireTime.parseInstant(watermark) ?: return true
        val edited = WireTime.parseInstant(local.lastInteractionDate)
            ?: WireTime.parseInstant(local.lastStateChangeDate)
            ?: WireTime.parseInstant(local.dateAdded)
        // A row with no edit time at all has nothing to say that the cloud is missing. It used to
        // return true, which pushed every undated row on every sync, forever.
        return edited != null && edited.after(since)
    }
}
