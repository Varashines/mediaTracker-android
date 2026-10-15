import React from 'react';
import { useLibrary, AppTab } from '../../context/LibraryContext';
import { Home, Library, Calendar, Sparkles, Settings } from 'lucide-react';

interface NavItem {
  id: AppTab;
  label: string;
  icon: any;
}

const NAV_ITEMS: NavItem[] = [
  { id: 'home', label: 'Home', icon: Home },
  { id: 'library', label: 'Library', icon: Library },
  { id: 'calendar', label: 'Calendar', icon: Calendar },
  { id: 'smarthub', label: 'SmartHub', icon: Sparkles },
  { id: 'settings', label: 'Settings', icon: Settings },
];

export const ExpressiveNavigationBar: React.FC = () => {
  const { activeTab, setActiveTab } = useLibrary();

  return (
    <>
      {/* Desktop Navigation Tabs (shown below Header) */}
      <nav className="hidden md:block border-b border-zinc-850 bg-zinc-950/60 sticky top-16 z-20 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-6 flex items-center gap-1 py-1">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const isActive = activeTab === item.id;
            return (
              <button
                key={item.id}
                onClick={() => setActiveTab(item.id)}
                className={`relative flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-medium transition-all ${
                  isActive
                    ? 'text-white bg-zinc-900/90 shadow-sm border border-zinc-700/60'
                    : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900/40 border border-transparent'
                }`}
              >
                <Icon
                  size={16}
                  className={`transition-colors ${
                    isActive ? 'text-indigo-400' : 'text-zinc-500'
                  }`}
                />
                <span>{item.label}</span>
                {isActive && (
                  <span className="absolute bottom-0 left-1/2 -translate-x-1/2 w-8 h-0.5 bg-indigo-500 rounded-full" />
                )}
              </button>
            );
          })}
        </div>
      </nav>

      {/* Mobile Floating Bottom Bar */}
      <nav className="md:hidden fixed bottom-3 left-3 right-3 z-40 bg-zinc-900/90 backdrop-blur-xl border border-zinc-800 rounded-2xl shadow-2xl p-1.5 flex items-center justify-around">
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          const isActive = activeTab === item.id;
          return (
            <button
              key={item.id}
              onClick={() => setActiveTab(item.id)}
              className={`flex flex-col items-center justify-center py-1.5 px-3 rounded-xl transition-all ${
                isActive
                  ? 'text-indigo-400 bg-indigo-500/10 font-semibold'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              <Icon size={19} className={isActive ? 'text-indigo-400' : 'text-zinc-400'} />
              <span className="text-[11px] mt-0.5">{item.label}</span>
            </button>
          );
        })}
      </nav>
    </>
  );
};
