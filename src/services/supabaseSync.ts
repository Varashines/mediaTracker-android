import { MediaItem, SyncSettings, SyncImpact, EpisodeEntity } from '../types';

export type { SyncImpact };

export interface SyncResult {
  success: boolean;
  message: string;
  syncedCount?: number;
  items?: MediaItem[];
}

export class CloudSyncManager {
  private url: string;
  private key: string;
  private syncKey: string;

  constructor(url: string, key: string, syncKey: string = '') {
    this.url = url ? url.replace(/\/+$/, '') : '';
    this.key = key;
    this.syncKey = syncKey;
  }

  async computeImpact(items: MediaItem[], episodes: EpisodeEntity[] = [], collections: any[] = []): Promise<SyncImpact> {
    return {
      incomingAdds: 0,
      incomingUpdates: 0,
      outgoingAdds: items.length,
      outgoingUpdates: 0,
      conflicts: 0,
      incomingTitles: [],
      outgoingTitles: items.slice(0, 5).map((i) => i.title),
      conflictTitles: [],
      incomingEpisodes: 0,
      outgoingEpisodes: episodes.length,
      summaryText: `Ready to synchronize ${items.length} titles and ${episodes.length} episodes with Supabase cloud.`
    };
  }

  async sync(items: MediaItem[], episodes: EpisodeEntity[] = [], collections: any[] = [], collectionItems: any[] = []) {
    const res = await syncWithSupabase(items, {
      supabaseUrl: this.url,
      supabaseAnonKey: this.key,
      supabaseTable: 'media_items',
      tmdbApiKey: '',
      autoSync: false,
      syncKey: this.syncKey
    });

    return {
      updatedMedia: res.items || items,
      updatedEpisodes: episodes,
      updatedCollections: collections,
      updatedCollectionItems: collectionItems,
      summary: {
        outgoing: items.length,
        incoming: (res.items ? res.items.length : 0)
      }
    };
  }
}

export async function testSupabaseConnection(settings: SyncSettings): Promise<{ ok: boolean; message: string }> {
  if (!settings.supabaseUrl || !settings.supabaseAnonKey) {
    return { ok: false, message: 'Supabase URL and Anon Key are required.' };
  }

  try {
    const cleanUrl = settings.supabaseUrl.replace(/\/+$/, '');
    const tableName = settings.supabaseTable || 'media_items';
    const endpoint = `${cleanUrl}/rest/v1/${tableName}?select=count&limit=1`;

    const res = await fetch(endpoint, {
      method: 'GET',
      headers: {
        'apikey': settings.supabaseAnonKey,
        'Authorization': `Bearer ${settings.supabaseAnonKey}`,
        'Range': '0-0',
      },
    });

    if (res.ok) {
      return { ok: true, message: `Connected to Supabase table '${tableName}' successfully.` };
    }

    if (res.status === 404) {
      return { ok: false, message: `Table '${tableName}' was not found. Please ensure the table exists or create it in Supabase.` };
    }

    const errText = await res.text();
    return { ok: false, message: `Supabase returned ${res.status}: ${errText}` };
  } catch (err: any) {
    return { ok: false, message: `Connection error: ${err.message || 'Network unreachable'}` };
  }
}

export async function syncWithSupabase(localItems: MediaItem[], settings: SyncSettings): Promise<SyncResult> {
  if (!settings.supabaseUrl || !settings.supabaseAnonKey) {
    return {
      success: false,
      message: 'Supabase credentials are not configured. Working in local mode.',
    };
  }

  try {
    const cleanUrl = settings.supabaseUrl.replace(/\/+$/, '');
    const tableName = settings.supabaseTable || 'media_items';

    // 1. Fetch remote items from Supabase
    const pullRes = await fetch(`${cleanUrl}/rest/v1/${tableName}?select=*`, {
      method: 'GET',
      headers: {
        'apikey': settings.supabaseAnonKey,
        'Authorization': `Bearer ${settings.supabaseAnonKey}`,
      },
    });

    if (!pullRes.ok) {
      const errText = await pullRes.text();
      throw new Error(`Failed to read from Supabase (${pullRes.status}): ${errText}`);
    }

    const remoteRecords: any[] = await pullRes.json();

    // Map remote records to MediaItem format
    const remoteItemsMap = new Map<string, MediaItem>();
    remoteRecords.forEach((rec) => {
      const item: MediaItem = {
        id: rec.id || String(rec.tmdb_id),
        tmdbId: rec.tmdb_id,
        title: rec.title,
        originalTitle: rec.original_title,
        type: rec.type || 'movie',
        status: rec.status || 'plan_to_watch',
        posterUrl: rec.poster_url || '',
        backdropUrl: rec.backdrop_url,
        rating: rec.rating,
        userRating: rec.user_rating,
        releaseDate: rec.release_date || '',
        genres: Array.isArray(rec.genres) ? rec.genres : typeof rec.genres === 'string' ? JSON.parse(rec.genres) : [],
        overview: rec.overview || '',
        network: rec.network,
        progress: typeof rec.progress === 'object' && rec.progress !== null ? rec.progress : { current: rec.progress_current || 0, total: rec.progress_total },
        nextAirDate: rec.next_air_date,
        nextEpisodeNumber: rec.next_episode_number,
        nextSeasonNumber: rec.next_season_number,
        nextEpisodeTitle: rec.next_episode_title,
        isFavorite: Boolean(rec.is_favorite),
        notes: rec.notes,
        tags: Array.isArray(rec.tags) ? rec.tags : [],
        runtimeMinutes: rec.runtime_minutes,
        createdAt: rec.created_at || new Date().toISOString(),
        updatedAt: rec.updated_at || new Date().toISOString(),
      };
      remoteItemsMap.set(item.id, item);
    });

    // Merge: most recently updated item wins
    const mergedMap = new Map<string, MediaItem>();

    // Start with remote items
    remoteItemsMap.forEach((val, key) => {
      mergedMap.set(key, val);
    });

    // Merge local items
    const itemsToUpsertToRemote: any[] = [];

    localItems.forEach((local) => {
      const remote = remoteItemsMap.get(local.id);
      if (!remote) {
        // Local only: keep in merged and schedule upload to remote
        mergedMap.set(local.id, local);
        itemsToUpsertToRemote.push(toSupabaseRecord(local));
      } else {
        const localTime = new Date(local.updatedAt).getTime();
        const remoteTime = new Date(remote.updatedAt).getTime();
        if (localTime >= remoteTime) {
          mergedMap.set(local.id, local);
          if (localTime > remoteTime) {
            itemsToUpsertToRemote.push(toSupabaseRecord(local));
          }
        } else {
          // Remote is newer: keep remote in merged
          mergedMap.set(local.id, remote);
        }
      }
    });

    // 2. Upsert newer local items to Supabase
    if (itemsToUpsertToRemote.length > 0) {
      const upsertRes = await fetch(`${cleanUrl}/rest/v1/${tableName}`, {
        method: 'POST',
        headers: {
          'apikey': settings.supabaseAnonKey,
          'Authorization': `Bearer ${settings.supabaseAnonKey}`,
          'Content-Type': 'application/json',
          'Prefer': 'resolution=merge-duplicates',
        },
        body: JSON.stringify(itemsToUpsertToRemote),
      });

      if (!upsertRes.ok) {
        const errText = await upsertRes.text();
        console.warn(`Upsert warning (${upsertRes.status}): ${errText}`);
      }
    }

    const mergedList = Array.from(mergedMap.values());
    return {
      success: true,
      message: `Sync complete! Synced ${mergedList.length} items with Supabase.`,
      syncedCount: mergedList.length,
      items: mergedList,
    };
  } catch (err: any) {
    return {
      success: false,
      message: `Sync failed: ${err.message || 'Unknown network error'}`,
    };
  }
}

function toSupabaseRecord(item: MediaItem): Record<string, any> {
  return {
    id: item.id,
    tmdb_id: item.tmdbId,
    title: item.title,
    original_title: item.originalTitle,
    type: item.type,
    status: item.status,
    poster_url: item.posterUrl,
    backdrop_url: item.backdropUrl,
    rating: item.rating,
    user_rating: item.userRating,
    release_date: item.releaseDate,
    genres: item.genres,
    overview: item.overview,
    network: item.network,
    progress: item.progress,
    next_air_date: item.nextAirDate,
    next_episode_number: item.nextEpisodeNumber,
    next_season_number: item.nextSeasonNumber,
    next_episode_title: item.nextEpisodeTitle,
    is_favorite: item.isFavorite,
    notes: item.notes,
    tags: item.tags,
    runtime_minutes: item.runtimeMinutes,
    created_at: item.createdAt,
    updated_at: item.updatedAt,
  };
}
