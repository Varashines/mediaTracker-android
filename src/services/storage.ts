import { MediaItem, SyncSettings, EpisodeEntity } from '../types';
import { INITIAL_SAMPLE_MEDIA } from './sampleData';

const MEDIA_STORAGE_KEY = 'mediatracker_items_v2';
const SETTINGS_STORAGE_KEY = 'mediatracker_settings_v2';
const EPISODES_STORAGE_KEY = 'mediatracker_episodes_v2';
const COLLECTIONS_STORAGE_KEY = 'mediatracker_collections_v2';
const COLLECTION_ITEMS_STORAGE_KEY = 'mediatracker_collection_items_v2';

export const DEFAULT_SETTINGS: SyncSettings = {
  supabaseUrl: '',
  supabaseAnonKey: '',
  supabaseTable: 'media_items',
  tmdbApiKey: '',
  autoSync: false,
  syncKey: '',
  lastSyncTimestamp: 0,
};

export function loadStoredMedia(): MediaItem[] {
  try {
    const raw = localStorage.getItem(MEDIA_STORAGE_KEY);
    if (!raw) {
      localStorage.setItem(MEDIA_STORAGE_KEY, JSON.stringify(INITIAL_SAMPLE_MEDIA));
      return INITIAL_SAMPLE_MEDIA;
    }
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed) && parsed.length > 0) {
      return parsed;
    }
    return INITIAL_SAMPLE_MEDIA;
  } catch (e) {
    console.error('Failed to load media from storage, using initial sample:', e);
    return INITIAL_SAMPLE_MEDIA;
  }
}

export function saveStoredMedia(items: MediaItem[]): void {
  try {
    localStorage.setItem(MEDIA_STORAGE_KEY, JSON.stringify(items));
  } catch (e) {
    console.error('Failed to save media to storage:', e);
  }
}

export function loadStoredSettings(): SyncSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) return DEFAULT_SETTINGS;
    return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
  } catch (e) {
    console.error('Failed to load settings:', e);
    return DEFAULT_SETTINGS;
  }
}

export function saveStoredSettings(settings: Partial<SyncSettings>): void {
  try {
    const current = loadStoredSettings();
    const updated = { ...current, ...settings };
    localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(updated));
  } catch (e) {
    console.error('Failed to save settings:', e);
  }
}

export function loadStoredEpisodes(): EpisodeEntity[] {
  try {
    const raw = localStorage.getItem(EPISODES_STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function saveStoredEpisodes(episodes: EpisodeEntity[]): void {
  try {
    localStorage.setItem(EPISODES_STORAGE_KEY, JSON.stringify(episodes));
  } catch (e) {
    console.error('Failed to save episodes:', e);
  }
}

export const Storage = {
  getPreferences(): SyncSettings {
    return loadStoredSettings();
  },
  savePreferences(prefs: Partial<SyncSettings>): void {
    saveStoredSettings(prefs);
  },
  saveMediaItems(items: MediaItem[]): void {
    saveStoredMedia(items);
  },
  saveEpisodes(episodes: EpisodeEntity[]): void {
    saveStoredEpisodes(episodes);
  },
  saveCollections(collections: any[]): void {
    try {
      localStorage.setItem(COLLECTIONS_STORAGE_KEY, JSON.stringify(collections));
    } catch {}
  },
  saveCollectionItems(items: any[]): void {
    try {
      localStorage.setItem(COLLECTION_ITEMS_STORAGE_KEY, JSON.stringify(items));
    } catch {}
  },
  clearAllData(): void {
    try {
      localStorage.removeItem(MEDIA_STORAGE_KEY);
      localStorage.removeItem(EPISODES_STORAGE_KEY);
      localStorage.removeItem(COLLECTIONS_STORAGE_KEY);
      localStorage.removeItem(COLLECTION_ITEMS_STORAGE_KEY);
      localStorage.setItem(MEDIA_STORAGE_KEY, JSON.stringify(INITIAL_SAMPLE_MEDIA));
    } catch {}
  }
};

export function exportLibraryJson(items: MediaItem[]): string {
  return JSON.stringify(
    {
      version: '1.0',
      exportedAt: new Date().toISOString(),
      itemCount: items.length,
      library: items,
    },
    null,
    2
  );
}

export function parseImportedJson(jsonText: string): MediaItem[] {
  const parsed = JSON.parse(jsonText);
  if (Array.isArray(parsed)) {
    return parsed;
  }
  if (parsed && Array.isArray(parsed.library)) {
    return parsed.library;
  }
  throw new Error('Invalid MediaTracker backup JSON structure.');
}
