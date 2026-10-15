import React, { useState } from 'react';
import { useLibrary } from '../../context/LibraryContext';
import { MediaType, MediaItem } from '../../types';
import { formatRelativeRelease } from '../../utils/airTimeResolver';
import { SmartBadgePill } from '../common/SmartBadgePill';
import { Calendar as CalendarIcon, Clock, Filter, Tv, Film, Gamepad2, Sparkles } from 'lucide-react';

export const ReleaseCalendarScreen: React.FC = () => {
  const { items, setSelectedItem } = useLibrary();
  const [selectedType, setSelectedType] = useState<MediaType | 'all'>('all');

  const now = new Date();
  const todayMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();

  // Extract items that have an upcoming release date or next air date
  const calendarItems = items
    .filter((item) => {
      if (selectedType !== 'all' && item.type !== selectedType) return false;
      return Boolean(item.nextAirDate || item.releaseDate);
    })
    .map((item) => {
      const dateStr = item.nextAirDate || item.releaseDate;
      const targetTime = new Date(dateStr).getTime();
      const relative = formatRelativeRelease(dateStr);
      return {
        item,
        dateStr,
        targetTime,
        relative,
      };
    })
    .sort((a, b) => a.targetTime - b.targetTime);

  // Group into: Today/This Week (0 to 7 days), Next Week (8 to 14 days), Later (15+ days), Past (<0 days)
  const groups: { title: string; subtitle: string; entries: typeof calendarItems }[] = [
    {
      title: 'This Week',
      subtitle: 'Airings and launches happening within 7 days',
      entries: calendarItems.filter((c) => c.relative.daysDiff >= 0 && c.relative.daysDiff <= 7),
    },
    {
      title: 'Next Week',
      subtitle: 'Coming up in 8 to 14 days',
      entries: calendarItems.filter((c) => c.relative.daysDiff > 7 && c.relative.daysDiff <= 14),
    },
    {
      title: 'Later This Year & Beyond',
      subtitle: 'Anticipated upcoming premieres and volume drops',
      entries: calendarItems.filter((c) => c.relative.daysDiff > 14),
    },
    {
      title: 'Recently Aired / Released',
      subtitle: 'Episodes and titles that recently premiered',
      entries: calendarItems
        .filter((c) => c.relative.daysDiff < 0)
        .slice(-6)
        .reverse(),
    },
  ];

  return (
    <div className="space-y-8 pb-20">
      {/* Calendar Header & Type Filter */}
      <div className="flex flex-col sm:flex-row gap-4 items-start sm:items-center justify-between p-6 rounded-2xl border border-zinc-800 bg-zinc-900/60">
        <div>
          <div className="flex items-center gap-2">
            <CalendarIcon size={20} className="text-indigo-400" />
            <h1 className="text-xl font-bold text-white tracking-tight">Release & Airing Calendar</h1>
          </div>
          <p className="text-xs text-zinc-400 mt-1">
            Track episode premieres, season finales, theatrical windows, and game launches.
          </p>
        </div>

        {/* Filter Pills */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 text-xs">
          {[
            { id: 'all', label: 'All' },
            { id: 'tv', label: 'TV' },
            { id: 'anime', label: 'Anime' },
            { id: 'movie', label: 'Movies' },
            { id: 'game', label: 'Games' },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setSelectedType(tab.id as any)}
              className={`px-3 py-1.5 rounded-xl font-medium transition-all ${
                selectedType === tab.id
                  ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                  : 'bg-zinc-950 text-zinc-400 hover:text-zinc-200 border border-zinc-800'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Calendar Groups */}
      <div className="space-y-10">
        {groups.map((group) => {
          if (group.entries.length === 0) return null;

          return (
            <section key={group.title} className="space-y-4">
              <div className="border-b border-zinc-800/80 pb-2">
                <h2 className="text-lg font-bold text-zinc-100">{group.title}</h2>
                <p className="text-xs text-zinc-400">{group.subtitle}</p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {group.entries.map(({ item, dateStr, relative }) => (
                  <div
                    key={`${item.id}-${dateStr}`}
                    onClick={() => setSelectedItem(item)}
                    className="p-4 rounded-2xl border border-zinc-800/80 bg-zinc-900/50 hover:bg-zinc-850 hover:border-zinc-700 transition-all cursor-pointer group flex flex-col justify-between shadow-lg"
                  >
                    <div className="flex items-start gap-3">
                      <img
                        src={item.posterUrl}
                        alt={item.title}
                        className="w-16 h-24 rounded-xl object-cover shrink-0 border border-zinc-800 group-hover:scale-105 transition-transform"
                      />
                      <div className="flex-1 overflow-hidden space-y-1">
                        <div className="flex items-center gap-1.5 flex-wrap">
                          <SmartBadgePill type={item.type} size="sm" />
                          {item.network && (
                            <span className="text-[10px] text-zinc-400 px-1.5 py-0.5 rounded bg-zinc-800 border border-zinc-700">
                              {item.network}
                            </span>
                          )}
                        </div>

                        <h3 className="font-bold text-sm text-zinc-100 truncate group-hover:text-indigo-400 transition-colors">
                          {item.title}
                        </h3>

                        {/* Airing episode info */}
                        {item.nextEpisodeTitle || item.nextEpisodeNumber ? (
                          <p className="text-xs text-zinc-300 font-medium">
                            {item.nextSeasonNumber ? `S${item.nextSeasonNumber} ` : ''}
                            {item.nextEpisodeNumber ? `Ep ${item.nextEpisodeNumber}: ` : ''}
                            <span className="text-zinc-400">{item.nextEpisodeTitle || ''}</span>
                          </p>
                        ) : (
                          <p className="text-xs text-zinc-400 truncate">{item.overview}</p>
                        )}
                      </div>
                    </div>

                    {/* Bottom date badge & status */}
                    <div className="mt-4 pt-3 border-t border-zinc-800/60 flex items-center justify-between text-xs">
                      <div className="flex items-center gap-1.5 text-zinc-400">
                        <Clock size={13} className="text-indigo-400" />
                        <span className="font-mono">{dateStr}</span>
                      </div>

                      <span
                        className={`px-2.5 py-1 rounded-lg font-semibold text-[11px] ${
                          relative.daysDiff === 0
                            ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30 animate-pulse'
                            : relative.daysDiff > 0 && relative.daysDiff <= 3
                            ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                            : 'bg-indigo-500/10 text-indigo-300 border border-indigo-500/20'
                        }`}
                      >
                        {relative.label}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
};
