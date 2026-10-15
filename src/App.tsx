import React from 'react';
import { ThemeProvider, useTheme } from './context/ThemeContext';
import { LibraryProvider, useLibrary } from './context/LibraryContext';
import { Header } from './components/common/Header';
import { HomeScreen } from './components/home/HomeScreen';
import { SmartHubScreen } from './components/smarthub/SmartHubScreen';
import { ReleaseCalendarScreen } from './components/calendar/ReleaseCalendarScreen';
import { LibraryScreen } from './components/library/LibraryScreen';
import { SettingsScreen } from './components/settings/SettingsScreen';
import { ExpressiveNavigationBar } from './components/common/ExpressiveNavigationBar';
import { ToastHost } from './components/common/ToastHost';
import { SearchModal } from './components/search/SearchModal';
import { DetailScreen } from './components/detail/DetailScreen';

const MainLayout: React.FC = () => {
  const { activeTab, activeDetailMediaId, closeDetail, isSearchActive, closeSearch } = useLibrary();
  const { preset, isDark } = useTheme();

  return (
    <div
      className="min-h-screen text-slate-100 transition-colors duration-200 selection:bg-blue-500/30"
      style={{
        backgroundColor: isDark ? preset.backgroundColor : '#F6F7FA',
        color: isDark ? preset.onSurfaceColor : '#16171C'
      }}
    >
      <Header />

      {/* Floating/Desktop Navigation */}
      <ExpressiveNavigationBar />

      {/* Screen Content Container */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 pt-4 pb-28">
        {(activeTab === 'home' || activeTab === 0) && <HomeScreen />}
        {(activeTab === 'smarthub' || activeTab === 1) && <SmartHubScreen />}
        {(activeTab === 'calendar' || activeTab === 2) && <ReleaseCalendarScreen />}
        {(activeTab === 'library' || activeTab === 3) && <LibraryScreen />}
        {(activeTab === 'settings' || activeTab === 4) && <SettingsScreen />}
      </main>

      {/* App-wide Toast notifications */}
      <ToastHost />

      {/* Global Search Dialog */}
      <SearchModal isOpen={isSearchActive} onClose={closeSearch} />

      {/* Detail Screen Modal / Sheet */}
      {activeDetailMediaId && (
        <DetailScreen mediaId={activeDetailMediaId} onClose={closeDetail} />
      )}
    </div>
  );
};

export const App: React.FC = () => {
  return (
    <ThemeProvider>
      <LibraryProvider>
        <MainLayout />
      </LibraryProvider>
    </ThemeProvider>
  );
};

export default App;
