import React from 'react';
import { useLibrary } from '../../context/LibraryContext';
import { Search, Plus, RefreshCw, Smartphone, Database } from 'lucide-react';

interface HeaderProps {
  category?: string;
  title?: string;
  showSearch?: boolean;
  rightAction?: React.ReactNode;
}

export const Header: React.FC<HeaderProps> = ({ category, title, showSearch = true, rightAction }) => {
  const {
    syncSettings,
    syncNow,
    isSyncing,
    setIsSearchOpen,
    items,
  } = useLibrary();

  const isSupabaseConfigured = Boolean(syncSettings.supabaseUrl && syncSettings.supabaseAnonKey);

  return (
    <header className="sticky top-0 z-30 border-b border-zinc-800/80 bg-zinc-950/80 backdrop-blur-xl">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
        {/* Brand */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-600 via-indigo-500 to-violet-500 p-0.5 shadow-lg shadow-indigo-500/20 flex items-center justify-center">
            <div className="w-full h-full bg-zinc-950 rounded-[10px] flex items-center justify-center">
              <span className="font-extrabold text-indigo-400 text-lg tracking-tight">MT</span>
            </div>
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-bold text-base text-zinc-100 tracking-tight">MediaTracker</span>
              <span className="hidden sm:inline-flex items-center gap-1 text-[10px] uppercase font-semibold tracking-wider px-2 py-0.5 rounded-full bg-zinc-900 border border-zinc-800 text-zinc-400">
                <Smartphone size={10} className="text-emerald-400" /> Companion
              </span>
            </div>
            <div className="text-xs text-zinc-400 flex items-center gap-2">
              <span>{items.length} titles</span>
              <span className="text-zinc-600">•</span>
              <span className="hidden md:inline text-zinc-500">Cross-device entertainment tracker</span>
            </div>
          </div>
        </div>

        {/* Center / Actions */}
        <div className="flex items-center gap-2 sm:gap-3">
          {/* Quick Search Bar Trigger */}
          <button
            onClick={() => setIsSearchOpen(true)}
            className="flex items-center gap-2.5 px-3.5 py-1.5 rounded-xl bg-zinc-900/90 border border-zinc-800 text-zinc-400 hover:text-zinc-200 hover:border-zinc-700 hover:bg-zinc-850 text-xs sm:text-sm transition-all shadow-inner group"
          >
            <Search size={15} className="group-hover:text-indigo-400 transition-colors" />
            <span className="hidden sm:inline">Search movies, TV, games...</span>
            <span className="sm:hidden">Search</span>
            <kbd className="hidden md:inline-block px-1.5 py-0.5 text-[10px] bg-zinc-800 border border-zinc-700/60 rounded text-zinc-400">
              ⌘K
            </kbd>
          </button>

          {/* Supabase Sync Button */}
          <button
            onClick={syncNow}
            disabled={isSyncing}
            title={
              isSupabaseConfigured
                ? `Sync with Supabase (${syncSettings.lastSyncedAt ? new Date(syncSettings.lastSyncedAt).toLocaleTimeString() : 'Never'})`
                : 'Configure Supabase in Settings'
            }
            className={`hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-xl border text-xs font-medium transition-all ${
              isSupabaseConfigured
                ? 'border-emerald-500/30 bg-emerald-950/20 text-emerald-400 hover:bg-emerald-950/40'
                : 'border-zinc-800 bg-zinc-900/60 text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <RefreshCw size={13} className={isSyncing ? 'animate-spin text-emerald-400' : ''} />
            <span>{isSyncing ? 'Syncing...' : isSupabaseConfigured ? 'Sync' : 'Local'}</span>
            <Database size={12} className={isSupabaseConfigured ? 'text-emerald-500' : 'text-zinc-500'} />
          </button>

          {/* Quick Add */}
          <button
            onClick={() => setIsSearchOpen(true)}
            className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-medium text-xs sm:text-sm shadow-md shadow-indigo-600/30 transition-all active:scale-95"
          >
            <Plus size={16} />
            <span className="hidden sm:inline">Add Media</span>
          </button>
        </div>
      </div>
    </header>
  );
};
