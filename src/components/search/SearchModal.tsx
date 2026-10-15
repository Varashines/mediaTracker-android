import React, { useState, useMemo, useEffect } from 'react';
import { Search, X, Film, Tv, Check, Plus, Globe } from 'lucide-react';
import { useTheme } from '../../context/ThemeContext';
import { useLibrary } from '../../context/LibraryContext';
import { SmartBadgePill } from '../common/SmartBadgePill';
import { TMDBClient } from '../../services/tmdbClient';
import { Storage } from '../../services/storage';
import { MediaItemEntity, TMDBSearchItem } from '../../types';

interface SearchModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const SearchModal: React.FC<SearchModalProps> = ({ isOpen, onClose }) => {
  const { preset, isDark } = useTheme();
  const { items, badges, openDetail, addMediaItem, showToast } = useLibrary();

  const [query, setQuery] = useState('');
  const [isTmdbMode, setIsTmdbMode] = useState(false);
  const [onlineResults, setOnlineResults] = useState<TMDBSearchItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const prefs = useMemo(() => Storage.getPreferences(), []);
  const tmdbClient = useMemo(() => new TMDBClient(prefs.tmdbApiKey), [prefs.tmdbApiKey]);

  // Local matching items
  const localResults = useMemo(() => {
    if (!query.trim() || isTmdbMode) return [];
    const q = query.toLowerCase();
    return items.filter((it) => {
      if (it.isSoftDeleted) return false;
      const t = it.title.toLowerCase();
      const o = (it.overview || '').toLowerCase();
      const n = (it.network || '').toLowerCase();
      const g = (Array.isArray(it.genres) ? it.genres.join(' ') : String(it.genres || '')).toLowerCase();
      return t.includes(q) || o.includes(q) || n.includes(q) || g.includes(q);
    });
  }, [query, isTmdbMode, items]);

  // Online TMDB search effect
  useEffect(() => {
    if (!isTmdbMode || query.trim().length < 2) {
      setOnlineResults([]);
      return;
    }

    const timer = setTimeout(async () => {
      setIsLoading(true);
      try {
        const res = await tmdbClient.searchMulti(query.trim());
        setOnlineResults(res);
      } catch {
        setOnlineResults([]);
      } finally {
        setIsLoading(false);
      }
    }, 350);

    return () => clearTimeout(timer);
  }, [query, isTmdbMode, tmdbClient]);

  if (!isOpen) return null;

  const handleAddOnlineItem = (res: TMDBSearchItem) => {
    const isMovie = res.media_type === 'movie';
    const mediaId = (isMovie ? 'movie_' : 'tv_') + res.id;
    const title = res.title || res.name || 'Untitled';

    const existing = items.find((it) => it.id === mediaId);
    if (existing) {
      onClose();
      openDetail(existing.id);
      return;
    }

    const newItem: MediaItemEntity = {
      id: mediaId,
      title,
      overview: res.overview || '',
      posterUrl: TMDBClient.buildPosterUrl(res.poster_path),
      backdropUrl: TMDBClient.buildBackdropUrl(res.backdrop_path),
      releaseDate: res.release_date || res.first_air_date,
      type: isMovie ? 'Movie' : 'TV',
      state: 'Wishlist',
      taste: 'None',
      lastInteractionDate: new Date().toISOString(),
      lastStateChangeDate: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    addMediaItem(newItem);
    onClose();
    openDetail(mediaId);
    showToast(`Added "${title}" to library`);
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-start bg-black/75 backdrop-blur-md p-4 sm:pt-16">
      <div
        className="w-full max-w-2xl rounded-[28px] border shadow-2xl overflow-hidden flex flex-col max-h-[85vh]"
        style={{
          backgroundColor: isDark ? preset.surfaceColor : '#FFFFFF',
          borderColor: isDark ? preset.outlineColor + '40' : '#E5E7EB'
        }}
      >
        {/* Top Search Bar */}
        <div className="p-4 border-b flex items-center gap-3" style={{ borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB' }}>
          <Search className="w-5 h-5 text-gray-400 shrink-0" />
          <input
            type="text"
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={isTmdbMode ? 'Search online on TMDB...' : 'Search your library...'}
            className="flex-1 bg-transparent border-none outline-none text-base font-semibold"
            style={{ color: isDark ? preset.onSurfaceColor : '#16171C' }}
          />

          <button
            onClick={() => setIsTmdbMode(!isTmdbMode)}
            className={`px-3 py-1.5 rounded-[12px] text-xs font-bold border transition-all flex items-center gap-1.5 ${
              isTmdbMode ? 'shadow-sm text-white' : 'opacity-70'
            }`}
            style={{
              backgroundColor: isTmdbMode ? preset.accentHex : isDark ? preset.surfaceContainerHighColor : '#F0F1F5',
              borderColor: isTmdbMode ? preset.accentHex : isDark ? preset.outlineColor + '30' : '#E5E7EB'
            }}
          >
            <Globe className="w-3.5 h-3.5" />
            <span>TMDB</span>
          </button>

          <button
            onClick={onClose}
            className="w-8 h-8 rounded-full flex items-center justify-center text-gray-400 hover:text-white"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Results Area */}
        <div className="p-4 overflow-y-auto flex-1 flex flex-col gap-3">
          {isLoading && (
            <div className="flex items-center justify-center py-10">
              <div className="w-6 h-6 border-2 border-current border-t-transparent rounded-full animate-spin text-blue-500" />
            </div>
          )}

          {!isLoading && query.trim() && (
            <>
              {isTmdbMode ? (
                onlineResults.length === 0 ? (
                  <div className="text-center py-12 text-gray-400 text-sm">No TMDB results found for "{query}".</div>
                ) : (
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                    {onlineResults.map((res) => {
                      const isMovie = res.media_type === 'movie';
                      const mediaId = (isMovie ? 'movie_' : 'tv_') + res.id;
                      const inLibrary = items.some((it) => it.id === mediaId);

                      return (
                        <div
                          key={mediaId}
                          onClick={() => handleAddOnlineItem(res)}
                          className="cursor-pointer rounded-[20px] overflow-hidden border aspect-[2/3] relative shadow-md transition-all active:scale-95 group"
                          style={{
                            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
                            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
                          }}
                        >
                          {res.poster_path ? (
                            <img
                              src={TMDBClient.buildPosterUrl(res.poster_path)!}
                              alt={res.title || res.name}
                              className="w-full h-full object-cover"
                            />
                          ) : (
                            <div className="w-full h-full flex items-center justify-center bg-zinc-800 p-2 text-center text-xs font-bold">
                              {res.title || res.name}
                            </div>
                          )}

                          <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent pointer-events-none" />

                          <div className="absolute top-2 left-2 p-1 rounded bg-black/60 text-white">
                            {isMovie ? <Film className="w-3 h-3" /> : <Tv className="w-3 h-3" />}
                          </div>

                          {inLibrary ? (
                            <div
                              className="absolute top-2 right-2 p-1 rounded-full shadow"
                              style={{ backgroundColor: preset.accentHex, color: '#FFFFFF' }}
                            >
                              <Check className="w-3 h-3 stroke-[3]" />
                            </div>
                          ) : (
                            <div className="absolute top-2 right-2 p-1 rounded-full bg-black/60 text-white opacity-0 group-hover:opacity-100 transition-opacity">
                              <Plus className="w-3.5 h-3.5" />
                            </div>
                          )}

                          <div className="absolute bottom-2 left-2 right-2">
                            <h4 className="text-xs font-black text-white truncate leading-tight">
                              {res.title || res.name}
                            </h4>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )
              ) : (
                localResults.length === 0 ? (
                  <div className="text-center py-12 text-gray-400 text-sm">
                    No library titles match "{query}". Switch to TMDB to search online!
                  </div>
                ) : (
                  <div className="flex flex-col gap-2">
                    {localResults.map((item) => {
                      const badgeRes = badges.get(item.id);
                      return (
                        <div
                          key={item.id}
                          onClick={() => {
                            onClose();
                            openDetail(item.id);
                          }}
                          className="p-3 rounded-[18px] border flex items-center gap-3 cursor-pointer transition-all active:scale-[0.99]"
                          style={{
                            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
                            borderColor: isDark ? preset.outlineColor + '25' : '#E5E7EB'
                          }}
                        >
                          <div className="w-12 h-16 rounded-[12px] overflow-hidden shrink-0 bg-zinc-800">
                            {item.posterUrl ? (
                              <img src={item.posterUrl} alt={item.title} className="w-full h-full object-cover" />
                            ) : (
                              <div className="w-full h-full flex items-center justify-center opacity-30">
                                <Film className="w-5 h-5" />
                              </div>
                            )}
                          </div>

                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <h4 className="text-sm font-bold truncate">{item.title}</h4>
                              {item.status && <SmartBadgePill status={item.status} />}
                            </div>
                            <p className="text-xs text-gray-400 line-clamp-1">{item.overview || item.state}</p>
                            <span className="text-[10px] text-gray-500 font-bold uppercase">{item.type}</span>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )
              )}
            </>
          )}

          {!query.trim() && (
            <div className="text-center py-12 text-gray-400 text-sm">
              Type to search movies, TV shows, genres, or streaming networks.
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
