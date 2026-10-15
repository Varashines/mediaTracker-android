import React, { useState } from 'react';
import { useLibrary } from '../../context/LibraryContext';
import { MediaType, MediaStatus, SortOption } from '../../types';
import { SmartBadgePill } from '../common/SmartBadgePill';
import {
  Search,
  Filter,
  Grid,
  List,
  Heart,
  Star,
  Plus,
  ArrowUpDown,
  X,
  PlusCircle,
} from 'lucide-react';

const MEDIA_TYPES: { id: MediaType | 'all'; label: string }[] = [
  { id: 'all', label: 'All Media' },
  { id: 'movie', label: 'Movies' },
  { id: 'tv', label: 'TV Shows' },
  { id: 'anime', label: 'Anime' },
  { id: 'game', label: 'Games' },
  { id: 'book', label: 'Books' },
];

const STATUS_FILTERS: { id: MediaStatus | 'all'; label: string }[] = [
  { id: 'all', label: 'All Status' },
  { id: 'watching', label: 'In Progress' },
  { id: 'plan_to_watch', label: 'Plan to Watch' },
  { id: 'completed', label: 'Completed' },
  { id: 'on_hold', label: 'On Hold' },
  { id: 'dropped', label: 'Dropped' },
];

export const LibraryScreen: React.FC = () => {
  const {
    filteredItems,
    filterState,
    setFilterState,
    resetFilters,
    setSelectedItem,
    incrementProgress,
    toggleFavorite,
    setIsSearchOpen,
  } = useLibrary();

  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');

  return (
    <div className="space-y-6 pb-20">
      {/* Control & Filter Bar */}
      <div className="space-y-4 p-5 rounded-2xl border border-zinc-800 bg-zinc-900/60 backdrop-blur-md">
        {/* Search & Top Action */}
        <div className="flex flex-col sm:flex-row gap-3 items-stretch sm:items-center justify-between">
          <div className="relative flex-1">
            <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-zinc-400" />
            <input
              type="text"
              placeholder="Filter by title, network, tags..."
              value={filterState.searchQuery}
              onChange={(e) => setFilterState((prev) => ({ ...prev, searchQuery: e.target.value }))}
              className="w-full pl-10 pr-9 py-2 rounded-xl bg-zinc-950 border border-zinc-800 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-indigo-500 transition-colors"
            />
            {filterState.searchQuery && (
              <button
                onClick={() => setFilterState((prev) => ({ ...prev, searchQuery: '' }))}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 hover:text-zinc-300"
              >
                <X size={14} />
              </button>
            )}
          </div>

          <div className="flex items-center gap-2">
            {/* Sort Dropdown */}
            <div className="flex items-center gap-1.5 px-3 py-2 rounded-xl bg-zinc-950 border border-zinc-800 text-xs text-zinc-300">
              <ArrowUpDown size={13} className="text-zinc-400" />
              <select
                value={filterState.sortBy}
                onChange={(e) =>
                  setFilterState((prev) => ({ ...prev, sortBy: e.target.value as SortOption }))
                }
                className="bg-transparent text-xs text-zinc-200 focus:outline-none cursor-pointer"
              >
                <option value="updated">Recently Updated</option>
                <option value="title">Title (A-Z)</option>
                <option value="rating">Highest Rated</option>
                <option value="release">Release Date</option>
                <option value="progress">Progress %</option>
              </select>
            </div>

            {/* Favorite Filter Toggle */}
            <button
              onClick={() =>
                setFilterState((prev) => ({ ...prev, onlyFavorites: !prev.onlyFavorites }))
              }
              className={`p-2 rounded-xl border transition-all ${
                filterState.onlyFavorites
                  ? 'border-rose-500/50 bg-rose-950/40 text-rose-400'
                  : 'border-zinc-800 bg-zinc-950 text-zinc-400 hover:text-zinc-200'
              }`}
              title="Show only favorites"
            >
              <Heart size={16} fill={filterState.onlyFavorites ? 'currentColor' : 'none'} />
            </button>

            {/* Grid vs List Mode */}
            <div className="flex items-center rounded-xl border border-zinc-800 bg-zinc-950 p-1">
              <button
                onClick={() => setViewMode('grid')}
                className={`p-1.5 rounded-lg transition-colors ${
                  viewMode === 'grid' ? 'bg-zinc-800 text-indigo-400' : 'text-zinc-500 hover:text-zinc-300'
                }`}
                title="Grid View"
              >
                <Grid size={15} />
              </button>
              <button
                onClick={() => setViewMode('list')}
                className={`p-1.5 rounded-lg transition-colors ${
                  viewMode === 'list' ? 'bg-zinc-800 text-indigo-400' : 'text-zinc-500 hover:text-zinc-300'
                }`}
                title="List View"
              >
                <List size={15} />
              </button>
            </div>
          </div>
        </div>

        {/* Media Type Filter Pills */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 no-scrollbar text-xs">
          {MEDIA_TYPES.map((t) => {
            const isSelected = filterState.type === t.id;
            return (
              <button
                key={t.id}
                onClick={() => setFilterState((prev) => ({ ...prev, type: t.id }))}
                className={`px-3 py-1.5 rounded-xl whitespace-nowrap font-medium transition-all ${
                  isSelected
                    ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                    : 'bg-zinc-950 text-zinc-400 hover:text-zinc-200 border border-zinc-850'
                }`}
              >
                {t.label}
              </button>
            );
          })}
        </div>

        {/* Status Filter Pills */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 no-scrollbar text-xs">
          {STATUS_FILTERS.map((s) => {
            const isSelected = filterState.status === s.id;
            return (
              <button
                key={s.id}
                onClick={() => setFilterState((prev) => ({ ...prev, status: s.id }))}
                className={`px-3 py-1.5 rounded-xl whitespace-nowrap font-medium transition-all ${
                  isSelected
                    ? 'bg-zinc-100 text-zinc-950 font-semibold'
                    : 'bg-zinc-950/80 text-zinc-400 hover:text-zinc-200 border border-zinc-850'
                }`}
              >
                {s.label}
              </button>
            );
          })}

          {(filterState.type !== 'all' ||
            filterState.status !== 'all' ||
            filterState.onlyFavorites ||
            filterState.searchQuery) && (
            <button
              onClick={resetFilters}
              className="px-2.5 py-1 text-xs text-rose-400 hover:underline flex items-center gap-1 shrink-0"
            >
              Reset
            </button>
          )}
        </div>
      </div>

      {/* Items Section */}
      <div className="flex items-center justify-between text-xs text-zinc-400 px-1">
        <span>
          Showing <strong className="text-zinc-200">{filteredItems.length}</strong> items
        </span>
      </div>

      {filteredItems.length === 0 ? (
        <div className="p-12 rounded-2xl border border-dashed border-zinc-800 bg-zinc-900/30 text-center space-y-4">
          <p className="text-zinc-400 text-sm">No items found matching the current filters.</p>
          <div className="flex items-center justify-center gap-3">
            <button
              onClick={resetFilters}
              className="px-4 py-2 rounded-xl bg-zinc-800 text-zinc-200 text-xs font-semibold hover:bg-zinc-700 transition-colors"
            >
              Reset Filters
            </button>
            <button
              onClick={() => setIsSearchOpen(true)}
              className="px-4 py-2 rounded-xl bg-indigo-600 text-white text-xs font-semibold hover:bg-indigo-500 transition-colors flex items-center gap-1.5"
            >
              <PlusCircle size={14} /> Add new media
            </button>
          </div>
        </div>
      ) : viewMode === 'grid' ? (
        /* GRID VIEW */
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
          {filteredItems.map((item) => {
            const progressPct = item.progress.total
              ? Math.min(100, Math.round((item.progress.current / item.progress.total) * 100))
              : null;

            return (
              <div
                key={item.id}
                onClick={() => setSelectedItem(item)}
                className="group relative flex flex-col rounded-2xl border border-zinc-800/80 bg-zinc-900/60 overflow-hidden hover:border-zinc-700 hover:bg-zinc-850 transition-all cursor-pointer shadow-lg hover:shadow-2xl"
              >
                {/* Poster image container */}
                <div className="relative aspect-[2/3] w-full bg-zinc-950 overflow-hidden">
                  <img
                    src={item.posterUrl}
                    alt={item.title}
                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    loading="lazy"
                  />

                  {/* Top-right favorite button */}
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      toggleFavorite(item.id);
                    }}
                    className="absolute top-2 right-2 p-1.5 rounded-full bg-zinc-950/70 backdrop-blur-md text-zinc-300 hover:text-white transition-colors"
                    title="Toggle Favorite"
                  >
                    <Heart
                      size={14}
                      className={item.isFavorite ? 'text-rose-500 fill-rose-500' : ''}
                    />
                  </button>

                  {/* Rating badge */}
                  {(item.userRating || item.rating) && (
                    <div className="absolute bottom-2 left-2 flex items-center gap-1 px-2 py-0.5 rounded-md bg-zinc-950/80 backdrop-blur-md text-amber-400 text-xs font-bold border border-zinc-800/80">
                      <Star size={11} fill="currentColor" />
                      <span>{item.userRating ?? item.rating}</span>
                    </div>
                  )}

                  {/* Type badge */}
                  <div className="absolute top-2 left-2">
                    <SmartBadgePill type={item.type} size="sm" />
                  </div>
                </div>

                {/* Progress bar overlay on poster bottom */}
                {progressPct !== null && (
                  <div className="w-full bg-zinc-800 h-1">
                    <div
                      className="bg-indigo-500 h-full transition-all duration-300"
                      style={{ width: `${progressPct}%` }}
                    />
                  </div>
                )}

                {/* Info & Content */}
                <div className="p-3 flex-1 flex flex-col justify-between space-y-2">
                  <div>
                    <h3 className="font-semibold text-sm text-zinc-100 line-clamp-1 group-hover:text-indigo-400 transition-colors">
                      {item.title}
                    </h3>
                    <div className="flex items-center gap-1.5 mt-1 text-[11px] text-zinc-400">
                      <span>{item.releaseDate ? item.releaseDate.substring(0, 4) : 'TBA'}</span>
                      {item.genres[0] && (
                        <>
                          <span>•</span>
                          <span className="truncate max-w-[80px]">{item.genres[0]}</span>
                        </>
                      )}
                    </div>
                  </div>

                  {/* Status & Quick Increment */}
                  <div className="pt-2 border-t border-zinc-800/60 flex items-center justify-between gap-1">
                    <SmartBadgePill status={item.status} size="sm" />
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        incrementProgress(item.id);
                      }}
                      className="p-1 rounded-md bg-zinc-800 hover:bg-indigo-600 hover:text-white text-zinc-400 transition-colors"
                      title="Quick +1 Progress"
                    >
                      <Plus size={13} />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        /* LIST VIEW */
        <div className="space-y-2">
          {filteredItems.map((item) => {
            const progressPct = item.progress.total
              ? Math.min(100, Math.round((item.progress.current / item.progress.total) * 100))
              : null;

            return (
              <div
                key={item.id}
                onClick={() => setSelectedItem(item)}
                className="group flex items-center justify-between gap-4 p-3 rounded-xl border border-zinc-800 bg-zinc-900/50 hover:bg-zinc-850 hover:border-zinc-700 transition-all cursor-pointer"
              >
                <div className="flex items-center gap-3 overflow-hidden">
                  <img
                    src={item.posterUrl}
                    alt={item.title}
                    className="w-10 h-14 object-cover rounded-lg shrink-0 border border-zinc-800"
                  />
                  <div className="overflow-hidden">
                    <div className="flex items-center gap-2 mb-0.5">
                      <SmartBadgePill type={item.type} size="sm" />
                      <span className="text-xs text-zinc-400">
                        {item.releaseDate ? item.releaseDate.substring(0, 4) : ''}
                      </span>
                      {item.network && (
                        <span className="text-xs text-zinc-500">• {item.network}</span>
                      )}
                    </div>
                    <h4 className="font-bold text-sm text-zinc-100 truncate group-hover:text-indigo-400 transition-colors">
                      {item.title}
                    </h4>
                  </div>
                </div>

                <div className="flex items-center gap-4 shrink-0">
                  <div className="text-right text-xs">
                    <div className="font-medium text-zinc-200">
                      {item.progress.current} {item.progress.total ? `/ ${item.progress.total}` : ''}{' '}
                      {item.progress.unit || (item.type === 'game' ? 'hrs' : 'eps')}
                    </div>
                    {progressPct !== null && (
                      <span className="text-[10px] text-zinc-500">{progressPct}%</span>
                    )}
                  </div>

                  <SmartBadgePill status={item.status} size="sm" />

                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      incrementProgress(item.id);
                    }}
                    className="p-1.5 rounded-lg bg-zinc-800 hover:bg-indigo-600 text-zinc-300 hover:text-white transition-colors"
                    title="+1 progress"
                  >
                    <Plus size={14} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
