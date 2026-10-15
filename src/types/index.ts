export type MediaType = 'movie' | 'tv' | 'anime' | 'game' | 'book';

export type MediaStatus = 'watching' | 'plan_to_watch' | 'completed' | 'on_hold' | 'dropped';

export interface MediaProgress {
  current: number;
  total?: number;
  season?: number;
  totalSeasons?: number;
  unit?: string; // 'episodes' | 'hours' | 'pages' | '%'
}

export interface MediaItem {
  id: string;
  tmdbId?: number;
  title: string;
  originalTitle?: string;
  type: MediaType;
  status: MediaStatus;
  posterUrl: string;
  backdropUrl?: string;
  rating?: number; // 1 to 10
  userRating?: number; // 1 to 10
  releaseDate: string; // YYYY-MM-DD
  genres: string[];
  overview: string;
  network?: string; // e.g. "HBO", "Netflix", "Crunchyroll", "Steam", "Nintendo Switch"
  progress: MediaProgress;
  nextAirDate?: string; // YYYY-MM-DD or ISO
  nextEpisodeTitle?: string;
  nextEpisodeNumber?: number;
  nextSeasonNumber?: number;
  isFavorite?: boolean;
  notes?: string;
  tags?: string[];
  runtimeMinutes?: number;
  createdAt: string;
  updatedAt: string;
  // Compatibility fields for Android sync / DetailScreen
  state?: string;
  taste?: string;
  isSoftDeleted?: boolean;
  lastInteractionDate?: string;
  lastStateChangeDate?: string;
}

export interface MediaItemEntity {
  id: string;
  title: string;
  overview?: string;
  posterUrl?: string;
  backdropUrl?: string;
  releaseDate?: string;
  type: string;
  state: string;
  taste: string;
  lastInteractionDate?: string;
  lastStateChangeDate?: string;
  updatedAt: string;
  isSoftDeleted?: boolean;
  genres?: string;
  network?: string;
}

export interface EpisodeEntity {
  uniqueId: string;
  showId: string;
  seasonNumber: number;
  episodeNumber: number;
  title?: string;
  overview?: string;
  airDate?: string;
  isWatched: boolean;
  watchedDate?: string;
}

export interface TMDBSearchItem {
  id: number;
  media_type?: string;
  title?: string;
  name?: string;
  overview?: string;
  poster_path?: string;
  backdrop_path?: string;
  release_date?: string;
  first_air_date?: string;
  vote_average?: number;
}

export interface TMDBCastMember {
  id: number;
  name: string;
  character: string;
  profile_path?: string | null;
}

export interface SyncSettings {
  supabaseUrl: string;
  supabaseAnonKey: string;
  supabaseTable: string;
  tmdbApiKey: string;
  autoSync: boolean;
  syncKey?: string;
  providerRegion?: string;
  lastSyncTimestamp?: number;
  lastSyncedAt?: string;
}

export interface SyncImpact {
  incomingAdds: number;
  incomingUpdates: number;
  outgoingAdds: number;
  outgoingUpdates: number;
  conflicts: number;
  summaryText: string;
  incomingTitles: string[];
  outgoingTitles: string[];
  conflictTitles: string[];
  incomingEpisodes: number;
  outgoingEpisodes: number;
}

export type SortOption = 'updated' | 'title' | 'rating' | 'release' | 'progress';

export interface FilterState {
  type: MediaType | 'all';
  status: MediaStatus | 'all';
  genre: string | 'all';
  searchQuery: string;
  sortBy: SortOption;
  sortDirection: 'asc' | 'desc';
  onlyFavorites: boolean;
}

export interface ToastMessage {
  id: string;
  type: 'success' | 'info' | 'warning' | 'error';
  message: string;
}
