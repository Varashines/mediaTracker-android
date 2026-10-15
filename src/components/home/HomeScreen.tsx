import React from 'react';
import { useLibrary } from '../../context/LibraryContext';
import { computeSmartInsights } from '../../utils/smartCategoryMatcher';
import { SmartBadgePill } from '../common/SmartBadgePill';
import { formatRelativeRelease } from '../../utils/airTimeResolver';
import { Play, Plus, Star, Heart, Check, Flame, Clock, Sparkles } from 'lucide-react';

export const HomeScreen: React.FC = () => {
  const {
    items,
    setSelectedItem,
    incrementProgress,
    toggleFavorite,
    setActiveTab,
    setIsSearchOpen,
  } = useLibrary();

  const insights = computeSmartInsights(items);
  const featured = insights.upNextItems[0] || items[0];

  return (
    <div className="space-y-10 pb-16">
      {/* Featured Hero Banner */}
      {featured && (
        <div className="relative rounded-3xl overflow-hidden border border-zinc-800 bg-zinc-900 group shadow-2xl">
          <div className="absolute inset-0 z-0">
            <img
              src={featured.backdropUrl || featured.posterUrl}
              alt={featured.title}
              className="w-full h-full object-cover opacity-25 group-hover:opacity-30 group-hover:scale-105 transition-all duration-700"
            />
            <div className="absolute inset-0 bg-gradient-to-t from-zinc-950 via-zinc-950/80 to-transparent" />
            <div className="absolute inset-0 bg-gradient-to-r from-zinc-950 via-zinc-950/60 to-transparent" />
          </div>

          <div className="relative z-10 p-6 sm:p-10 flex flex-col md:flex-row gap-6 items-start md:items-end justify-between">
            <div className="max-w-2xl space-y-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 text-xs font-semibold uppercase tracking-wider">
                  <Flame size={12} className="text-amber-400" /> Active Focus
                </span>
                <SmartBadgePill type={featured.type} size="sm" />
                {featured.network && <SmartBadgePill network={featured.network} size="sm" />}
              </div>

              <h1 className="text-3xl sm:text-5xl font-extrabold text-white tracking-tight leading-tight">
                {featured.title}
              </h1>

              {featured.originalTitle && featured.originalTitle !== featured.title && (
                <p className="text-zinc-400 text-sm italic">{featured.originalTitle}</p>
              )}

              <p className="text-zinc-300 text-sm sm:text-base line-clamp-2 leading-relaxed">
                {featured.overview}
              </p>

              {/* Progress & Next Air date */}
              <div className="flex flex-wrap items-center gap-4 pt-2 text-xs text-zinc-400">
                <div className="flex items-center gap-2 bg-zinc-900/80 px-3 py-1.5 rounded-lg border border-zinc-800">
                  <span className="text-zinc-400">Progress:</span>
                  <span className="font-semibold text-white">
                    {featured.progress.current} {featured.progress.total ? `/ ${featured.progress.total}` : ''}{' '}
                    {featured.progress.unit || (featured.type === 'game' ? 'hrs' : 'eps')}
                  </span>
                </div>

                {featured.nextAirDate && (
                  <div className="flex items-center gap-2 bg-zinc-900/80 px-3 py-1.5 rounded-lg border border-zinc-800 text-amber-300">
                    <Clock size={13} />
                    <span>Next: {formatRelativeRelease(featured.nextAirDate).label}</span>
                  </div>
                )}
              </div>
            </div>

            {/* Hero Quick Actions */}
            <div className="flex flex-wrap items-center gap-3 w-full md:w-auto">
              <button
                onClick={() => incrementProgress(featured.id)}
                className="flex-1 md:flex-initial flex items-center justify-center gap-2 px-5 py-3 rounded-2xl bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-sm shadow-xl shadow-indigo-600/30 transition-all active:scale-95"
              >
                <Plus size={16} />
                <span>
                  +1 {featured.type === 'game' ? 'Hour' : featured.type === 'book' ? 'Page' : 'Episode'}
                </span>
              </button>

              <button
                onClick={() => setSelectedItem(featured)}
                className="flex-1 md:flex-initial flex items-center justify-center gap-2 px-5 py-3 rounded-2xl bg-zinc-800/90 hover:bg-zinc-750 text-zinc-200 border border-zinc-700 font-semibold text-sm transition-all"
              >
                <Play size={16} />
                <span>Details & Log</span>
              </button>

              <button
                onClick={() => toggleFavorite(featured.id)}
                className={`p-3 rounded-2xl border transition-all ${
                  featured.isFavorite
                    ? 'border-rose-500/40 bg-rose-950/40 text-rose-400'
                    : 'border-zinc-800 bg-zinc-900/80 text-zinc-400 hover:text-zinc-200'
                }`}
                title="Toggle favorite"
              >
                <Heart size={18} fill={featured.isFavorite ? 'currentColor' : 'none'} />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Up Next / Continue Watching Carousel */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-white tracking-tight">Continue Watching & Playing</h2>
            <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-zinc-800 text-zinc-400">
              {insights.upNextItems.length}
            </span>
          </div>
          <button
            onClick={() => {
              setActiveTab('library');
            }}
            className="text-xs font-medium text-indigo-400 hover:text-indigo-300 transition-colors"
          >
            View all library →
          </button>
        </div>

        {insights.upNextItems.length === 0 ? (
          <div className="p-8 rounded-2xl border border-dashed border-zinc-800 bg-zinc-900/30 text-center space-y-3">
            <p className="text-zinc-400 text-sm">No items currently in progress.</p>
            <button
              onClick={() => setIsSearchOpen(true)}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600 text-white text-xs font-semibold hover:bg-indigo-500"
            >
              <Plus size={14} /> Add new media
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {insights.upNextItems.map((item) => {
              const progressPct = item.progress.total
                ? Math.min(100, Math.round((item.progress.current / item.progress.total) * 100))
                : null;

              return (
                <div
                  key={item.id}
                  className="group relative flex gap-4 p-3.5 rounded-2xl border border-zinc-800/80 bg-zinc-900/60 hover:bg-zinc-850 hover:border-zinc-700/80 transition-all cursor-pointer shadow-lg hover:shadow-xl"
                  onClick={() => setSelectedItem(item)}
                >
                  {/* Poster thumbnail */}
                  <div className="relative w-20 h-28 rounded-xl overflow-hidden shrink-0 bg-zinc-950 border border-zinc-800">
                    <img
                      src={item.posterUrl}
                      alt={item.title}
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                      loading="lazy"
                    />
                    {item.isFavorite && (
                      <div className="absolute top-1 right-1 p-1 rounded-md bg-zinc-950/80 text-rose-500">
                        <Heart size={10} fill="currentColor" />
                      </div>
                    )}
                  </div>

                  {/* Info */}
                  <div className="flex-1 flex flex-col justify-between overflow-hidden">
                    <div>
                      <div className="flex items-center gap-1.5 mb-1">
                        <SmartBadgePill type={item.type} size="sm" />
                        {item.network && (
                          <span className="text-[10px] text-zinc-400 truncate max-w-[100px]">
                            {item.network}
                          </span>
                        )}
                      </div>

                      <h3 className="font-bold text-sm text-zinc-100 truncate group-hover:text-indigo-400 transition-colors">
                        {item.title}
                      </h3>

                      <div className="flex items-center gap-2 mt-1 text-xs text-zinc-400">
                        <span>
                          {item.progress.season ? `S${item.progress.season} • ` : ''}
                          {item.progress.current} {item.progress.total ? `/ ${item.progress.total}` : ''}{' '}
                          {item.progress.unit || (item.type === 'game' ? 'hrs' : 'eps')}
                        </span>
                        {item.rating && (
                          <span className="flex items-center gap-0.5 text-amber-400 text-xs">
                            <Star size={11} fill="currentColor" />
                            {item.userRating ?? item.rating}
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Progress Bar & Quick +1 */}
                    <div className="space-y-2 pt-2">
                      {progressPct !== null && (
                        <div className="w-full bg-zinc-800 h-1.5 rounded-full overflow-hidden">
                          <div
                            className="bg-indigo-500 h-full rounded-full transition-all duration-300"
                            style={{ width: `${progressPct}%` }}
                          />
                        </div>
                      )}

                      <div className="flex items-center justify-between">
                        {item.nextAirDate ? (
                          <span className="text-[10px] text-amber-400/90 font-medium truncate max-w-[140px]">
                            Next: {formatRelativeRelease(item.nextAirDate).label}
                          </span>
                        ) : (
                          <span className="text-[10px] text-zinc-500">
                            {progressPct !== null ? `${progressPct}% complete` : 'In progress'}
                          </span>
                        )}

                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            incrementProgress(item.id);
                          }}
                          className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-indigo-600/20 hover:bg-indigo-600 text-indigo-300 hover:text-white border border-indigo-500/30 text-[11px] font-semibold transition-all active:scale-95"
                          title="Increment progress by 1"
                        >
                          <Plus size={12} /> +1
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* Upcoming Release Highlights */}
      {insights.upcomingAiringItems.length > 0 && (
        <section className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold text-white tracking-tight">Upcoming Releases & Airings</h2>
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-zinc-800 text-zinc-400">
                {insights.upcomingAiringItems.length}
              </span>
            </div>
            <button
              onClick={() => setActiveTab('calendar')}
              className="text-xs font-medium text-indigo-400 hover:text-indigo-300 transition-colors"
            >
              Open release calendar →
            </button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {insights.upcomingAiringItems.slice(0, 3).map((item) => {
              const airMeta = formatRelativeRelease(item.nextAirDate || item.releaseDate);
              return (
                <div
                  key={item.id}
                  onClick={() => setSelectedItem(item)}
                  className="p-4 rounded-2xl border border-zinc-800 bg-zinc-900/50 hover:border-zinc-700 transition-all cursor-pointer flex items-center justify-between gap-4 group"
                >
                  <div className="flex items-center gap-3 overflow-hidden">
                    <img
                      src={item.posterUrl}
                      alt={item.title}
                      className="w-12 h-16 rounded-lg object-cover shrink-0 border border-zinc-800 group-hover:scale-105 transition-transform"
                    />
                    <div className="overflow-hidden">
                      <div className="flex items-center gap-1.5 mb-0.5">
                        <SmartBadgePill type={item.type} size="sm" />
                      </div>
                      <h4 className="font-semibold text-sm text-zinc-100 truncate group-hover:text-indigo-400 transition-colors">
                        {item.title}
                      </h4>
                      <p className="text-xs text-zinc-400 truncate">
                        {item.nextEpisodeTitle || (item.nextEpisodeNumber ? `Ep ${item.nextEpisodeNumber}` : item.network || 'Release')}
                      </p>
                    </div>
                  </div>

                  <div className="text-right shrink-0">
                    <span className="inline-block px-2.5 py-1 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 font-semibold text-xs">
                      {airMeta.label}
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {/* Library Summary Stats Bar */}
      <section className="p-6 rounded-2xl border border-zinc-800/80 bg-zinc-900/40 grid grid-cols-2 sm:grid-cols-4 gap-4 text-center">
        <div>
          <p className="text-xs text-zinc-400 font-medium">Total Media</p>
          <p className="text-2xl font-black text-white mt-1">{insights.totalItems}</p>
        </div>
        <div>
          <p className="text-xs text-zinc-400 font-medium">Currently In Progress</p>
          <p className="text-2xl font-black text-emerald-400 mt-1">{insights.watchingCount}</p>
        </div>
        <div>
          <p className="text-xs text-zinc-400 font-medium">Completed</p>
          <p className="text-2xl font-black text-indigo-400 mt-1">{insights.completedCount}</p>
        </div>
        <div>
          <p className="text-xs text-zinc-400 font-medium">Estimated Time Tracked</p>
          <p className="text-2xl font-black text-amber-400 mt-1">{insights.estimatedHoursSpent} hrs</p>
        </div>
      </section>
    </div>
  );
};
