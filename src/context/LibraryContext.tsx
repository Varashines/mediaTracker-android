import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import {
  MediaItem,
  FilterState,
  SyncSettings,
  ToastMessage,
  MediaStatus,
  EpisodeEntity,
} from '../types';
import {
  loadStoredMedia,
  saveStoredMedia,
  loadStoredSettings,
  saveStoredSettings,
  loadStoredEpisodes,
  saveStoredEpisodes,
} from '../services/storage';
import { INITIAL_SAMPLE_MEDIA } from '../services/sampleData';
import { syncWithSupabase } from '../services/supabaseSync';

export type AppTab = 'home' | 'library' | 'calendar' | 'smarthub' | 'settings' | 0 | 1 | 2 | 3 | 4;

interface LibraryContextType {
  items: MediaItem[];
  filteredItems: MediaItem[];
  selectedItem: MediaItem | null;
  setSelectedItem: (item: MediaItem | null) => void;
  activeDetailMediaId: string | null;
  openDetail: (id: string) => void;
  closeDetail: () => void;
  addItem: (item: any) => void;
  addMediaItem: (item: any) => void;
  updateItem: (id: string, updates: Partial<MediaItem>) => void;
  updateMediaState: (id: string, state: string) => void;
  updateMediaTaste: (id: string, taste: string) => void;
  deleteItem: (id: string) => void;
  deleteMediaItem: (id: string) => void;
  incrementProgress: (id: string) => void;
  decrementProgress: (id: string) => void;
  toggleFavorite: (id: string) => void;
  changeStatus: (id: string, status: MediaStatus) => void;
  
  // Episode Tracking
  episodes: EpisodeEntity[];
  setEpisodeWatched: (uniqueId: string, watched: boolean) => void;

  // Collections
  collections: any[];
  collectionItems: any[];

  // Smart Badges
  badges: Map<string, any>;

  // Navigation & Search
  activeTab: AppTab;
  setActiveTab: (tab: any) => void;
  isSearchOpen: boolean;
  setIsSearchOpen: (open: boolean) => void;
  isSearchActive: boolean;
  closeSearch: () => void;

  // Filtering
  filterState: FilterState;
  setFilterState: React.Dispatch<React.SetStateAction<FilterState>>;
  resetFilters: () => void;

  // Supabase Sync
  syncSettings: SyncSettings;
  updateSyncSettings: (settings: Partial<SyncSettings>) => void;
  syncNow: () => Promise<void>;
  isSyncing: boolean;
  syncStatusMessage: string | null;

  // Utilities
  toasts: ToastMessage[];
  addToast: (message: string, type?: ToastMessage['type']) => void;
  showToast: (message: string, type?: ToastMessage['type']) => void;
  removeToast: (id: string) => void;
  refreshFromStorage: () => void;
  resetToSampleLibrary: () => void;
  importLibrary: (newItems: MediaItem[]) => void;
}

const DEFAULT_FILTER: FilterState = {
  type: 'all',
  status: 'all',
  genre: 'all',
  searchQuery: '',
  sortBy: 'updated',
  sortDirection: 'desc',
  onlyFavorites: false,
};

const LibraryContext = createContext<LibraryContextType | undefined>(undefined);

export const LibraryProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [items, setItems] = useState<MediaItem[]>(() => loadStoredMedia());
  const [episodes, setEpisodes] = useState<EpisodeEntity[]>(() => loadStoredEpisodes());
  const [collections, setCollections] = useState<any[]>([]);
  const [collectionItems, setCollectionItems] = useState<any[]>([]);
  const [selectedItem, setSelectedItem] = useState<MediaItem | null>(null);
  const [activeTab, setActiveTabState] = useState<AppTab>('home');
  const [isSearchOpen, setIsSearchOpen] = useState<boolean>(false);
  const [filterState, setFilterState] = useState<FilterState>(DEFAULT_FILTER);
  const [syncSettings, setSyncSettings] = useState<SyncSettings>(() => loadStoredSettings());
  const [isSyncing, setIsSyncing] = useState<boolean>(false);
  const [syncStatusMessage, setSyncStatusMessage] = useState<string | null>(null);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  // Persist items
  useEffect(() => {
    saveStoredMedia(items);
  }, [items]);

  // Persist episodes
  useEffect(() => {
    saveStoredEpisodes(episodes);
  }, [episodes]);

  // Persist settings
  useEffect(() => {
    saveStoredSettings(syncSettings);
  }, [syncSettings]);

  // Keep selected item synchronized if modified
  useEffect(() => {
    if (selectedItem) {
      const refreshed = items.find((i) => i.id === selectedItem.id);
      if (refreshed) {
        setSelectedItem(refreshed);
      }
    }
  }, [items]);

  const addToast = useCallback((message: string, type: ToastMessage['type'] = 'info') => {
    const id = `${Date.now()}-${Math.random().toString(36).substr(2, 5)}`;
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
  }, []);

  const showToast = addToast;

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const openDetail = useCallback((id: string) => {
    const it = items.find((i) => i.id === id);
    if (it) setSelectedItem(it);
  }, [items]);

  const closeDetail = useCallback(() => {
    setSelectedItem(null);
  }, []);

  const closeSearch = useCallback(() => {
    setIsSearchOpen(false);
  }, []);

  const setActiveTab = useCallback((tab: any) => {
    if (typeof tab === 'number') {
      const map: Record<number, AppTab> = { 0: 'home', 1: 'smarthub', 2: 'calendar', 3: 'library', 4: 'settings' };
      setActiveTabState(map[tab] || 'home');
    } else {
      setActiveTabState(tab);
    }
  }, []);

  const addItem = useCallback((itemData: any) => {
    const now = new Date().toISOString();
    const newItem: MediaItem = {
      id: itemData.id || `mt-${Date.now()}-${Math.floor(Math.random() * 1000)}`,
      title: itemData.title,
      originalTitle: itemData.originalTitle,
      type: (itemData.type || 'movie').toLowerCase() === 'tv' ? 'tv' : 'movie',
      status: itemData.status || (itemData.state === 'Active' ? 'watching' : itemData.state === 'Done' ? 'completed' : 'plan_to_watch'),
      posterUrl: itemData.posterUrl || '',
      backdropUrl: itemData.backdropUrl,
      rating: itemData.rating || 8.0,
      releaseDate: itemData.releaseDate || now.slice(0, 10),
      genres: Array.isArray(itemData.genres) ? itemData.genres : typeof itemData.genres === 'string' ? itemData.genres.split(', ') : ['Entertainment'],
      overview: itemData.overview || '',
      network: itemData.network || 'Catalog',
      progress: itemData.progress || { current: 0, total: 10 },
      isFavorite: Boolean(itemData.isFavorite),
      state: itemData.state || 'Wishlist',
      taste: itemData.taste || 'None',
      createdAt: now,
      updatedAt: now,
    };
    setItems((prev) => [newItem, ...prev.filter((x) => x.id !== newItem.id)]);
    addToast(`Added "${newItem.title}" to library!`, 'success');
  }, [addToast]);

  const addMediaItem = addItem;

  const updateItem = useCallback((id: string, updates: Partial<MediaItem>) => {
    const now = new Date().toISOString();
    setItems((prev) =>
      prev.map((item) => (item.id === id ? { ...item, ...updates, updatedAt: now } : item))
    );
  }, []);

  const updateMediaState = useCallback((id: string, state: string) => {
    const statusMap: Record<string, MediaStatus> = {
      'Wishlist': 'plan_to_watch',
      'Active': 'watching',
      'Done': 'completed',
      'Dropped': 'dropped'
    };
    const mappedStatus = statusMap[state] || 'watching';
    setItems((prev) =>
      prev.map((item) =>
        item.id === id
          ? {
              ...item,
              state,
              status: mappedStatus,
              lastStateChangeDate: new Date().toISOString(),
              lastInteractionDate: new Date().toISOString(),
              updatedAt: new Date().toISOString()
            }
          : item
      )
    );
    addToast(`Moved to ${state}`, 'info');
  }, [addToast]);

  const updateMediaTaste = useCallback((id: string, taste: string) => {
    setItems((prev) =>
      prev.map((item) =>
        item.id === id
          ? {
              ...item,
              taste,
              lastInteractionDate: new Date().toISOString(),
              updatedAt: new Date().toISOString()
            }
          : item
      )
    );
    addToast(`Rating updated: ${taste}`, 'info');
  }, [addToast]);

  const deleteItem = useCallback((id: string) => {
    const target = items.find((i) => i.id === id);
    setItems((prev) => prev.filter((item) => item.id !== id));
    if (selectedItem?.id === id) {
      setSelectedItem(null);
    }
    if (target) {
      addToast(`Removed "${target.title}" from library`, 'info');
    }
  }, [items, selectedItem, addToast]);

  const deleteMediaItem = deleteItem;

  const setEpisodeWatched = useCallback((uniqueId: string, watched: boolean) => {
    const now = new Date().toISOString();
    setEpisodes((prev) => {
      const idx = prev.findIndex((e) => e.uniqueId === uniqueId);
      if (idx !== -1) {
        return prev.map((e) =>
          e.uniqueId === uniqueId
            ? { ...e, isWatched: watched, watchedDate: watched ? now : undefined }
            : e
        );
      }
      return [
        ...prev,
        {
          uniqueId,
          showId: uniqueId.split('_')[0],
          seasonNumber: 1,
          episodeNumber: 1,
          isWatched: watched,
          watchedDate: watched ? now : undefined
        }
      ];
    });
  }, []);

  const incrementProgress = useCallback((id: string) => {
    setItems((prev) =>
      prev.map((item) => {
        if (item.id !== id) return item;
        const current = item.progress.current + 1;
        const total = item.progress.total;
        let newStatus = item.status;

        if (total && current >= total && item.status !== 'completed') {
          newStatus = 'completed';
          addToast(`🎉 Completed "${item.title}"!`, 'success');
        } else {
          addToast(`Updated progress for "${item.title}" (${current}${total ? `/${total}` : ''})`, 'info');
        }

        return {
          ...item,
          status: newStatus,
          progress: {
            ...item.progress,
            current,
          },
          updatedAt: new Date().toISOString(),
        };
      })
    );
  }, [addToast]);

  const decrementProgress = useCallback((id: string) => {
    setItems((prev) =>
      prev.map((item) => {
        if (item.id !== id) return item;
        const current = Math.max(0, item.progress.current - 1);
        return {
          ...item,
          progress: {
            ...item.progress,
            current,
          },
          updatedAt: new Date().toISOString(),
        };
      })
    );
  }, []);

  const toggleFavorite = useCallback((id: string) => {
    setItems((prev) =>
      prev.map((item) => {
        if (item.id !== id) return item;
        const isFavorite = !item.isFavorite;
        addToast(
          isFavorite ? `Marked "${item.title}" as favorite ❤️` : `Removed "${item.title}" from favorites`,
          'info'
        );
        return {
          ...item,
          isFavorite,
          updatedAt: new Date().toISOString(),
        };
      })
    );
  }, [addToast]);

  const changeStatus = useCallback((id: string, status: MediaStatus) => {
    setItems((prev) =>
      prev.map((item) => {
        if (item.id !== id) return item;
        return {
          ...item,
          status,
          updatedAt: new Date().toISOString(),
        };
      })
    );
  }, []);

  const resetFilters = useCallback(() => {
    setFilterState(DEFAULT_FILTER);
  }, []);

  const updateSyncSettings = useCallback((updates: Partial<SyncSettings>) => {
    setSyncSettings((prev) => ({ ...prev, ...updates }));
    addToast('Sync settings updated', 'info');
  }, [addToast]);

  const syncNow = useCallback(async () => {
    setIsSyncing(true);
    setSyncStatusMessage('Connecting to Supabase...');
    try {
      const result = await syncWithSupabase(items, syncSettings);
      if (result.success && result.items) {
        setItems(result.items);
        const now = new Date().toISOString();
        setSyncSettings((prev) => ({ ...prev, lastSyncedAt: now }));
        addToast(result.message, 'success');
        setSyncStatusMessage(result.message);
      } else {
        addToast(result.message, 'warning');
        setSyncStatusMessage(result.message);
      }
    } catch (e: any) {
      const err = e.message || 'Sync encountered an error';
      addToast(err, 'error');
      setSyncStatusMessage(err);
    } finally {
      setIsSyncing(false);
    }
  }, [items, syncSettings, addToast]);

  const refreshFromStorage = useCallback(() => {
    setItems(loadStoredMedia());
    setEpisodes(loadStoredEpisodes());
    setSyncSettings(loadStoredSettings());
  }, []);

  const resetToSampleLibrary = useCallback(() => {
    setItems(INITIAL_SAMPLE_MEDIA);
    saveStoredMedia(INITIAL_SAMPLE_MEDIA);
    addToast('Library reset to curated default collection', 'info');
  }, [addToast]);

  const importLibrary = useCallback((newItems: MediaItem[]) => {
    setItems(newItems);
    saveStoredMedia(newItems);
    addToast(`Successfully imported ${newItems.length} media items!`, 'success');
  }, [addToast]);

  // Dynamic Badges map for items
  const badges = useMemo(() => {
    const map = new Map<string, any>();
    for (const item of items) {
      map.set(item.id, {
        label: item.status.toUpperCase().replace(/_/g, ' '),
        color: item.status === 'completed' ? '#10B981' : item.status === 'watching' ? '#6366F1' : '#F59E0B'
      });
    }
    return map;
  }, [items]);

  // Filtered & Sorted items
  const filteredItems = useMemo(() => {
    return items
      .filter((item) => {
        if (filterState.type !== 'all' && item.type !== filterState.type) {
          return false;
        }
        if (filterState.status !== 'all' && item.status !== filterState.status) {
          return false;
        }
        if (filterState.genre !== 'all' && !item.genres.includes(filterState.genre)) {
          return false;
        }
        if (filterState.onlyFavorites && !item.isFavorite) {
          return false;
        }
        if (filterState.searchQuery.trim()) {
          const q = filterState.searchQuery.toLowerCase();
          const matchTitle = item.title.toLowerCase().includes(q);
          const matchOverview = item.overview.toLowerCase().includes(q);
          const matchNetwork = (item.network || '').toLowerCase().includes(q);
          const matchGenre = item.genres.some((g) => g.toLowerCase().includes(q));
          if (!matchTitle && !matchOverview && !matchNetwork && !matchGenre) {
            return false;
          }
        }
        return true;
      })
      .sort((a, b) => {
        let diff = 0;
        switch (filterState.sortBy) {
          case 'title':
            diff = a.title.localeCompare(b.title);
            break;
          case 'rating':
            diff = (b.rating || 0) - (a.rating || 0);
            break;
          case 'release':
            diff = new Date(b.releaseDate).getTime() - new Date(a.releaseDate).getTime();
            break;
          case 'progress': {
            const pA = a.progress.total ? a.progress.current / a.progress.total : 0;
            const pB = b.progress.total ? b.progress.current / b.progress.total : 0;
            diff = pB - pA;
            break;
          }
          case 'updated':
          default:
            diff = new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime();
            break;
        }
        return filterState.sortDirection === 'asc' ? -diff : diff;
      });
  }, [items, filterState]);

  return (
    <LibraryContext.Provider
      value={{
        items,
        filteredItems,
        selectedItem,
        setSelectedItem,
        activeDetailMediaId: selectedItem ? selectedItem.id : null,
        openDetail,
        closeDetail,
        addItem,
        addMediaItem,
        updateItem,
        updateMediaState,
        updateMediaTaste,
        deleteItem,
        deleteMediaItem,
        incrementProgress,
        decrementProgress,
        toggleFavorite,
        changeStatus,
        episodes,
        setEpisodeWatched,
        collections,
        collectionItems,
        badges,
        activeTab,
        setActiveTab,
        isSearchOpen,
        setIsSearchOpen,
        isSearchActive: isSearchOpen,
        closeSearch,
        filterState,
        setFilterState,
        resetFilters,
        syncSettings,
        updateSyncSettings,
        syncNow,
        isSyncing,
        syncStatusMessage,
        toasts,
        addToast,
        showToast,
        removeToast,
        refreshFromStorage,
        resetToSampleLibrary,
        importLibrary,
      }}
    >
      {children}
    </LibraryContext.Provider>
  );
};

export function useLibrary() {
  const context = useContext(LibraryContext);
  if (!context) {
    throw new Error('useLibrary must be used within LibraryProvider');
  }
  return context;
}
