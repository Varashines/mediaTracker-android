import React, { useState, useMemo, useEffect } from 'react';
import {
  X,
  Star,
  Heart,
  ThumbsUp,
  ThumbsDown,
  Check,
  Film,
  Tv,
  Calendar,
  Clock,
  Trash2,
  ExternalLink,
  ChevronRight,
  Sparkles,
  Info
} from 'lucide-react';
import { useTheme } from '../../context/ThemeContext';
import { useLibrary } from '../../context/LibraryContext';
import { SmartBadgePill } from '../common/SmartBadgePill';
import { NetworkResolver } from '../../utils/networkResolver';
import { TMDBClient } from '../../services/tmdbClient';
import { Storage } from '../../services/storage';
import { MediaItemEntity, EpisodeEntity, TMDBCastMember } from '../../types';

interface DetailScreenProps {
  mediaId: string;
  onClose: () => void;
}

export const DetailScreen: React.FC<DetailScreenProps> = ({ mediaId, onClose }) => {
  const { preset, isDark, semantic } = useTheme();
  const {
    items,
    episodes,
    badges,
    updateMediaState,
    updateMediaTaste,
    setEpisodeWatched,
    deleteMediaItem,
    showToast
  } = useLibrary();

  const item = items.find((it) => it.id === mediaId);
  const badgeRes = badges.get(mediaId);

  // Show episodes for TV shows
  const showEpisodes = useMemo(() => {
    return episodes
      .filter((ep) => ep.showId === mediaId)
      .sort((a, b) => {
        if (a.seasonNumber !== b.seasonNumber) return a.seasonNumber - b.seasonNumber;
        return a.episodeNumber - b.episodeNumber;
      });
  }, [episodes, mediaId]);

  // Group episodes by season
  const seasons = useMemo(() => {
    const map = new Map<number, EpisodeEntity[]>();
    for (const ep of showEpisodes) {
      const list = map.get(ep.seasonNumber) || [];
      list.push(ep);
      map.set(ep.seasonNumber, list);
    }
    return Array.from(map.entries()).sort((a, b) => a[0] - b[0]);
  }, [showEpisodes]);

  const [selectedSeason, setSelectedSeason] = useState<number>(() => {
    if (seasons.length > 0) return seasons[0][0];
    return 1;
  });

  // Keep selectedSeason updated if seasons load
  useEffect(() => {
    if (seasons.length > 0 && !seasons.some(([s]) => s === selectedSeason)) {
      setSelectedSeason(seasons[0][0]);
    }
  }, [seasons, selectedSeason]);

  const currentSeasonEpisodes = useMemo(() => {
    const found = seasons.find(([s]) => s === selectedSeason);
    return found ? found[1] : [];
  }, [seasons, selectedSeason]);

  const isTv = item?.type.toLowerCase().includes('tv') ?? false;
  const netInfo = item ? NetworkResolver.resolve(item) : null;

  // TMDB credits fetch for cast
  const [cast, setCast] = useState<TMDBCastMember[]>([]);

  useEffect(() => {
    if (!item) return;
    const prefs = Storage.getPreferences();
    const tmdbClient = new TMDBClient(prefs.tmdbApiKey);
    const rawId = item.id.replace('movie_', '').replace('tv_', '');
    const tmdbId = parseInt(rawId, 10);
    if (isNaN(tmdbId)) return;

    if (item.type.toLowerCase().includes('movie')) {
      tmdbClient.getMovieCredits(tmdbId).then((res: any) => setCast((res.cast || res || []).slice(0, 10)));
    } else {
      tmdbClient.getTVCredits(tmdbId).then((res: any) => setCast((res.cast || res || []).slice(0, 10)));
    }
  }, [item]);

  if (!item) return null;

  const handleMarkSeasonWatched = (watched: boolean) => {
    for (const ep of currentSeasonEpisodes) {
      setEpisodeWatched(ep.uniqueId, watched);
    }
    showToast(`Season ${selectedSeason} marked ${watched ? 'watched' : 'unwatched'}`);
  };

  const handleMarkAllPreviousWatched = (targetEp: EpisodeEntity) => {
    for (const ep of showEpisodes) {
      if (
        ep.seasonNumber < targetEp.seasonNumber ||
        (ep.seasonNumber === targetEp.seasonNumber && ep.episodeNumber <= targetEp.episodeNumber)
      ) {
        setEpisodeWatched(ep.uniqueId, true);
      }
    }
    showToast(`Marked watched through S${targetEp.seasonNumber} E${targetEp.episodeNumber}`);
  };

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-black/80 backdrop-blur-md flex flex-col justify-start">
      <div
        className="relative w-full max-w-2xl mx-auto min-h-screen border-x shadow-2xl flex flex-col pb-24"
        style={{
          backgroundColor: isDark ? preset.surfaceColor : '#FFFFFF',
          borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
        }}
      >
        {/* Floating Close Button */}
        <button
          onClick={onClose}
          className="fixed top-4 right-4 sm:absolute sm:top-5 sm:right-5 z-40 w-10 h-10 rounded-full bg-black/70 text-white flex items-center justify-center backdrop-blur-md border border-white/20 transition-transform active:scale-90"
        >
          <X className="w-5 h-5" />
        </button>

        {/* Hero Backdrop Banner */}
        <div className="relative w-full h-[280px] sm:h-[340px] bg-zinc-900 shrink-0 overflow-hidden">
          {item.backdropUrl || item.posterUrl ? (
            <img
              src={item.backdropUrl || item.posterUrl!}
              alt={item.title}
              className="w-full h-full object-cover object-center"
            />
          ) : (
            <div className="w-full h-full flex items-center justify-center opacity-30">
              {isTv ? <Tv className="w-16 h-16" /> : <Film className="w-16 h-16" />}
            </div>
          )}

          {/* Gradient Scrim */}
          <div className="absolute inset-0 bg-gradient-to-t from-black via-black/40 to-transparent pointer-events-none" />

          {/* Top Badge */}
          <div className="absolute top-5 left-5 flex items-center gap-2">
            {item.status && <SmartBadgePill status={item.status} />}
          </div>

          {/* Bottom Title Area in Scrim */}
          <div className="absolute bottom-4 left-5 right-5 flex items-end justify-between gap-4">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1 flex-wrap">
                {netInfo && (
                  <span
                    className={`px-2 py-0.5 rounded text-[10px] font-black uppercase tracking-wider border ${netInfo.badgeClass}`}
                  >
                    {netInfo.name}
                  </span>
                )}
                <span className="text-[11px] font-bold text-gray-300 uppercase tracking-wider">
                  {item.type} {item.releaseDate ? `• ${item.releaseDate.slice(0, 4)}` : ''}
                </span>
              </div>
              <h1 className="text-2xl sm:text-3xl font-black text-white leading-tight drop-shadow-md">
                {item.title}
              </h1>
            </div>

            {/* Poster Thumbnail floating on right */}
            {item.posterUrl && (
              <div className="w-18 h-26 sm:w-20 sm:h-30 rounded-[14px] overflow-hidden border-2 border-white/40 shadow-2xl shrink-0 hidden sm:block">
                <img src={item.posterUrl} alt={item.title} className="w-full h-full object-cover" />
              </div>
            )}
          </div>
        </div>

        {/* Content Body */}
        <div className="p-5 flex flex-col gap-6">
          {/* Action Row: State Switcher + Taste Selector */}
          <div
            className="p-3.5 rounded-[22px] border flex flex-col sm:flex-row items-center justify-between gap-3 shadow-sm"
            style={{
              backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
              borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
            }}
          >
            {/* State Picker */}
            <div className="flex p-1 rounded-[16px] bg-black/10 dark:bg-black/30 w-full sm:w-auto">
              {(['Watching', 'Wishlist', 'Completed', 'Dropped'] as const).map((st) => {
                const isActive = (item.state || '').toLowerCase() === st.toLowerCase();
                return (
                  <button
                    key={st}
                    onClick={() => updateMediaState(item.id, st)}
                    className={`flex-1 sm:flex-none px-3 py-1.5 rounded-[12px] text-xs font-bold transition-all ${
                      isActive ? 'shadow-sm text-white' : 'opacity-60'
                    }`}
                    style={{
                      backgroundColor: isActive ? preset.accentHex : 'transparent'
                    }}
                  >
                    {st}
                  </button>
                );
              })}
            </div>

            {/* Taste Picker */}
            <div className="flex items-center gap-1.5">
              <span className="text-[11px] font-bold text-gray-400 uppercase tracking-wider mr-1">Rating:</span>
              <button
                onClick={() => updateMediaTaste(item.id, item.taste === 'Loved' ? 'None' : 'Loved')}
                className={`p-2 rounded-[12px] border transition-all active:scale-95 ${
                  item.taste === 'Loved' ? 'border-pink-500 bg-pink-500/20 text-pink-500' : 'opacity-60'
                }`}
                title="Loved"
              >
                <Heart className={`w-4 h-4 ${item.taste === 'Loved' ? 'fill-current' : ''}`} />
              </button>
              <button
                onClick={() => updateMediaTaste(item.id, item.taste === 'Liked' ? 'None' : 'Liked')}
                className={`p-2 rounded-[12px] border transition-all active:scale-95 ${
                  item.taste === 'Liked' ? 'border-emerald-500 bg-emerald-500/20 text-emerald-500' : 'opacity-60'
                }`}
                title="Liked"
              >
                <ThumbsUp className={`w-4 h-4 ${item.taste === 'Liked' ? 'fill-current' : ''}`} />
              </button>
              <button
                onClick={() => updateMediaTaste(item.id, item.taste === 'Disliked' ? 'None' : 'Disliked')}
                className={`p-2 rounded-[12px] border transition-all active:scale-95 ${
                  item.taste === 'Disliked' ? 'border-amber-500 bg-amber-500/20 text-amber-500' : 'opacity-60'
                }`}
                title="Disliked"
              >
                <ThumbsDown className={`w-4 h-4 ${item.taste === 'Disliked' ? 'fill-current' : ''}`} />
              </button>
            </div>
          </div>

          {/* Overview Section */}
          {item.overview && (
            <div className="flex flex-col gap-1.5">
              <h3 className="text-xs font-black uppercase tracking-wider text-gray-400">Overview</h3>
              <p className="text-sm leading-relaxed text-gray-300">{item.overview}</p>
            </div>
          )}

          {/* Genres Chips */}
          {item.genres && (
            <div className="flex flex-wrap gap-1.5">
              {(Array.isArray(item.genres) ? item.genres : (item.genres as string).split(',')).map((g: string) => (
                <span
                  key={g.trim()}
                  className="px-2.5 py-1 rounded-[10px] text-xs font-semibold border"
                  style={{
                    backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5',
                    borderColor: isDark ? preset.outlineColor + '20' : '#E5E7EB'
                  }}
                >
                  {g.trim()}
                </span>
              ))}
            </div>
          )}

          {/* Cast Members */}
          {cast.length > 0 && (
            <div className="flex flex-col gap-2.5">
              <h3 className="text-xs font-black uppercase tracking-wider text-gray-400">Cast</h3>
              <div className="flex gap-3 overflow-x-auto pb-2 scrollbar-none -mx-5 px-5">
                {cast.map((actor) => (
                  <div key={actor.id} className="w-20 shrink-0 flex flex-col items-center text-center gap-1">
                    <div className="w-16 h-16 rounded-full overflow-hidden bg-zinc-800 border border-zinc-700 shadow-inner">
                      {actor.profile_path ? (
                        <img
                          src={TMDBClient.buildProfileUrl(actor.profile_path)!}
                          alt={actor.name}
                          className="w-full h-full object-cover"
                        />
                      ) : (
                        <div className="w-full h-full flex items-center justify-center text-xs font-black text-gray-500">
                          {actor.name.slice(0, 2)}
                        </div>
                      )}
                    </div>
                    <span className="text-[11px] font-bold line-clamp-1 leading-tight">{actor.name}</span>
                    <span className="text-[10px] text-gray-400 line-clamp-1 leading-tight">{actor.character}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* TV Shows: Episode Guide & Progress */}
          {isTv && (
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-black uppercase tracking-wider text-gray-400">Episodes & Seasons</h3>
                <div className="flex gap-2">
                  <button
                    onClick={() => handleMarkSeasonWatched(true)}
                    className="text-[11px] font-bold text-emerald-400 hover:underline"
                  >
                    Mark Season Watched
                  </button>
                </div>
              </div>

              {/* Seasons Rail */}
              {seasons.length > 1 && (
                <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-none">
                  {seasons.map(([sNum, eps]) => {
                    const isSelected = selectedSeason === sNum;
                    const watchedCount = eps.filter((e) => e.isWatched).length;
                    return (
                      <button
                        key={sNum}
                        onClick={() => setSelectedSeason(sNum)}
                        className={`px-3 py-1.5 rounded-[12px] text-xs font-bold shrink-0 border transition-all ${
                          isSelected ? 'shadow-sm text-white' : 'opacity-70'
                        }`}
                        style={{
                          backgroundColor: isSelected ? preset.accentHex : isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
                          borderColor: isSelected ? preset.accentHex : isDark ? preset.outlineColor + '30' : '#E5E7EB'
                        }}
                      >
                        Season {sNum} ({watchedCount}/{eps.length})
                      </button>
                    );
                  })}
                </div>
              )}

              {/* Current Season Episodes List */}
              <div className="flex flex-col gap-2">
                {currentSeasonEpisodes.length === 0 ? (
                  <div className="text-center py-6 text-xs text-gray-400">No episodes available for this season.</div>
                ) : (
                  currentSeasonEpisodes.map((ep) => (
                    <div
                      key={ep.uniqueId}
                      className={`p-3 rounded-[18px] border flex items-center justify-between gap-3 transition-colors ${
                        ep.isWatched ? 'opacity-65' : ''
                      }`}
                      style={{
                        backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
                        borderColor: isDark ? preset.outlineColor + '20' : '#E5E7EB'
                      }}
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <button
                          onClick={() => setEpisodeWatched(ep.uniqueId, !ep.isWatched)}
                          className={`w-7 h-7 rounded-[9px] border flex items-center justify-center transition-all ${
                            ep.isWatched ? 'bg-emerald-500 border-emerald-500 text-white' : 'border-gray-500'
                          }`}
                        >
                          {ep.isWatched && <Check className="w-4 h-4 stroke-[3]" />}
                        </button>

                        <div className="min-w-0">
                          <h4 className="text-xs font-bold truncate">
                            {ep.episodeNumber}. {ep.title || `Episode ${ep.episodeNumber}`}
                          </h4>
                          {ep.airDate && (
                            <p className="text-[10px] text-gray-400">
                              Aired {ep.airDate}
                            </p>
                          )}
                        </div>
                      </div>

                      <button
                        onClick={() => handleMarkAllPreviousWatched(ep)}
                        className="text-[10px] font-bold text-blue-400 hover:underline shrink-0"
                        title="Mark all episodes up to here as watched"
                      >
                        Watched Up To
                      </button>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

          {/* Delete Danger Zone */}
          <div className="pt-4 border-t border-zinc-700/30 flex justify-between items-center">
            <span className="text-xs text-gray-500">Item ID: {item.id}</span>
            <button
              onClick={() => {
                if (window.confirm(`Remove "${item.title}" from your library?`)) {
                  deleteMediaItem(item.id);
                  onClose();
                  showToast(`Removed "${item.title}"`);
                }
              }}
              className="px-3.5 py-2 rounded-[12px] border border-red-500/30 text-red-500 hover:bg-red-500/10 text-xs font-bold flex items-center gap-1.5 transition-transform active:scale-95"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>Remove from Library</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
