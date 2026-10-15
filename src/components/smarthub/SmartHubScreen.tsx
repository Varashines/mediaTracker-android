import React from 'react';
import { useLibrary } from '../../context/LibraryContext';
import { computeSmartInsights } from '../../utils/smartCategoryMatcher';
import { SmartBadgePill } from '../common/SmartBadgePill';
import {
  Sparkles,
  Flame,
  Award,
  Clock,
  CheckCircle2,
  TrendingUp,
  PlayCircle,
  Compass,
} from 'lucide-react';

export const SmartHubScreen: React.FC = () => {
  const { items, setSelectedItem, incrementProgress, changeStatus } = useLibrary();
  const insights = computeSmartInsights(items);

  const completionRate =
    insights.totalItems > 0
      ? Math.round((insights.completedCount / insights.totalItems) * 100)
      : 0;

  return (
    <div className="space-y-8 pb-20">
      {/* SmartHub Header */}
      <div className="p-6 rounded-2xl border border-zinc-800 bg-gradient-to-br from-indigo-950/40 via-zinc-900/60 to-zinc-950 flex flex-col sm:flex-row gap-4 items-start sm:items-center justify-between">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <Sparkles size={20} className="text-indigo-400" />
            <h1 className="text-xl font-bold text-white tracking-tight">SmartHub & Analytics</h1>
          </div>
          <p className="text-xs text-zinc-400">
            Intelligent library insights, backlog optimization, and entertainment metrics.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="px-3 py-1.5 rounded-xl bg-zinc-900 border border-zinc-800 text-xs">
            <span className="text-zinc-400">Completion Rate: </span>
            <span className="font-bold text-emerald-400">{completionRate}%</span>
          </div>
          <div className="px-3 py-1.5 rounded-xl bg-zinc-900 border border-zinc-800 text-xs">
            <span className="text-zinc-400">Avg Rating: </span>
            <span className="font-bold text-amber-400">★ {insights.averageRating}</span>
          </div>
        </div>
      </div>

      {/* Analytics Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="p-5 rounded-2xl border border-zinc-800 bg-zinc-900/50 space-y-2">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-semibold uppercase tracking-wider">Total Time Invested</span>
            <Clock size={16} className="text-indigo-400" />
          </div>
          <p className="text-3xl font-black text-white">{insights.estimatedHoursSpent} <span className="text-sm font-normal text-zinc-400">hours</span></p>
          <p className="text-xs text-zinc-500">Aggregated across all movies, shows & games</p>
        </div>

        <div className="p-5 rounded-2xl border border-zinc-800 bg-zinc-900/50 space-y-2">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-semibold uppercase tracking-wider">Active Watchlist</span>
            <Flame size={16} className="text-amber-400" />
          </div>
          <p className="text-3xl font-black text-amber-400">{insights.watchingCount} <span className="text-sm font-normal text-zinc-400">in progress</span></p>
          <p className="text-xs text-zinc-500">Currently being watched, played or read</p>
        </div>

        <div className="p-5 rounded-2xl border border-zinc-800 bg-zinc-900/50 space-y-2">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-semibold uppercase tracking-wider">Completed Hall of Fame</span>
            <Award size={16} className="text-emerald-400" />
          </div>
          <p className="text-3xl font-black text-emerald-400">{insights.completedCount} <span className="text-sm font-normal text-zinc-400">finished</span></p>
          <p className="text-xs text-zinc-500">{completionRate}% of your total collection</p>
        </div>

        <div className="p-5 rounded-2xl border border-zinc-800 bg-zinc-900/50 space-y-2">
          <div className="flex items-center justify-between text-zinc-400">
            <span className="text-xs font-semibold uppercase tracking-wider">Backlog Stack</span>
            <TrendingUp size={16} className="text-sky-400" />
          </div>
          <p className="text-3xl font-black text-sky-400">{insights.planToWatchCount} <span className="text-sm font-normal text-zinc-400">queued</span></p>
          <p className="text-xs text-zinc-500">Planned for future entertainment</p>
        </div>
      </div>

      {/* Category Breakdown Progress */}
      <div className="p-6 rounded-2xl border border-zinc-800 bg-zinc-900/50 space-y-4">
        <h3 className="font-bold text-base text-zinc-100">Collection Type Distribution</h3>
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          {Object.entries(insights.typeDistribution).map(([type, count]) => {
            const pct = insights.totalItems > 0 ? Math.round((count / insights.totalItems) * 100) : 0;
            return (
              <div key={type} className="p-3 rounded-xl bg-zinc-950 border border-zinc-800/80 space-y-1">
                <div className="flex items-center justify-between text-xs capitalize text-zinc-400">
                  <span>{type}</span>
                  <span className="font-bold text-zinc-200">{count}</span>
                </div>
                <div className="w-full bg-zinc-800 h-1.5 rounded-full overflow-hidden">
                  <div className="bg-indigo-500 h-full rounded-full" style={{ width: `${pct}%` }} />
                </div>
                <div className="text-[10px] text-zinc-500 text-right">{pct}%</div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Backlog Burner Section */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="space-y-0.5">
            <div className="flex items-center gap-2">
              <Compass size={18} className="text-indigo-400" />
              <h2 className="text-lg font-bold text-white">Backlog Burner Recommendations</h2>
            </div>
            <p className="text-xs text-zinc-400">
              Top critically-rated titles sitting in your plan to watch list ready for you to start.
            </p>
          </div>
        </div>

        {insights.backlogItems.length === 0 ? (
          <div className="p-6 rounded-2xl border border-dashed border-zinc-800 text-center text-xs text-zinc-400">
            Your backlog is completely empty! Add some titles in Plan to Watch.
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {insights.backlogItems.slice(0, 3).map((item) => (
              <div
                key={item.id}
                onClick={() => setSelectedItem(item)}
                className="p-4 rounded-2xl border border-zinc-800 bg-zinc-900/60 hover:border-zinc-700 transition-all cursor-pointer flex gap-3 group"
              >
                <img
                  src={item.posterUrl}
                  alt={item.title}
                  className="w-16 h-24 rounded-xl object-cover shrink-0 border border-zinc-800 group-hover:scale-105 transition-transform"
                />
                <div className="flex-1 flex flex-col justify-between overflow-hidden">
                  <div>
                    <div className="flex items-center gap-1.5 mb-1">
                      <SmartBadgePill type={item.type} size="sm" />
                      {item.rating && (
                        <span className="text-xs font-bold text-amber-400">★ {item.rating}</span>
                      )}
                    </div>
                    <h4 className="font-bold text-sm text-zinc-100 truncate group-hover:text-indigo-400 transition-colors">
                      {item.title}
                    </h4>
                    <p className="text-xs text-zinc-400 line-clamp-2 mt-0.5">{item.overview}</p>
                  </div>

                  <div className="pt-2">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        changeStatus(item.id, 'watching');
                      }}
                      className="flex items-center gap-1.5 px-3 py-1 rounded-lg bg-indigo-600/20 hover:bg-indigo-600 text-indigo-300 hover:text-white border border-indigo-500/30 text-xs font-semibold transition-all"
                    >
                      <PlayCircle size={13} />
                      <span>Start Now</span>
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Recently Completed Section */}
      {insights.recentlyCompleted.length > 0 && (
        <section className="space-y-4">
          <div className="flex items-center gap-2">
            <CheckCircle2 size={18} className="text-emerald-400" />
            <h2 className="text-lg font-bold text-white">Recently Finished</h2>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {insights.recentlyCompleted.map((item) => (
              <div
                key={item.id}
                onClick={() => setSelectedItem(item)}
                className="p-3 rounded-xl border border-zinc-800 bg-zinc-900/40 hover:bg-zinc-850 hover:border-zinc-700 transition-all cursor-pointer flex items-center gap-3 group"
              >
                <img
                  src={item.posterUrl}
                  alt={item.title}
                  className="w-10 h-14 object-cover rounded-lg shrink-0 border border-zinc-800"
                />
                <div className="overflow-hidden">
                  <h5 className="font-bold text-xs text-zinc-200 truncate group-hover:text-indigo-400 transition-colors">
                    {item.title}
                  </h5>
                  <div className="text-[11px] text-zinc-500">
                    {item.userRating ? `Rated ★ ${item.userRating}` : 'Completed'}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
};
