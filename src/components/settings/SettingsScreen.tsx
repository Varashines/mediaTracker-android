import React, { useState } from 'react';
import {
  Cloud,
  CloudSync,
  Key,
  Film,
  Globe,
  Palette,
  Sun,
  Moon,
  Smartphone,
  Check,
  Eye,
  EyeOff,
  ChevronDown,
  ChevronUp,
  Download,
  Upload,
  Trash2,
  RefreshCw
} from 'lucide-react';
import { useTheme, THEME_PRESETS } from '../../context/ThemeContext';
import { useLibrary } from '../../context/LibraryContext';
import { Header } from '../common/Header';
import { Storage } from '../../services/storage';
import { CloudSyncManager, SyncImpact } from '../../services/supabaseSync';

const PROVIDER_REGIONS = [
  { code: '', label: 'Auto (Default)' },
  { code: 'US', label: 'United States (US)' },
  { code: 'GB', label: 'United Kingdom (GB)' },
  { code: 'IN', label: 'India (IN)' },
  { code: 'CA', label: 'Canada (CA)' },
  { code: 'AU', label: 'Australia (AU)' },
  { code: 'DE', label: 'Germany (DE)' },
  { code: 'FR', label: 'France (FR)' },
  { code: 'ES', label: 'Spain (ES)' },
  { code: 'IT', label: 'Italy (IT)' },
  { code: 'JP', label: 'Japan (JP)' },
  { code: 'KR', label: 'South Korea (KR)' }
];

export const SettingsScreen: React.FC = () => {
  const { preset, presetIndex, setPresetIndex, themeMode, setThemeMode, isDark, semantic } = useTheme();
  const { items, episodes, collections, collectionItems, refreshFromStorage, showToast } = useLibrary();

  const [prefs, setPrefs] = useState(() => Storage.getPreferences());
  const [showAnonKey, setShowAnonKey] = useState(false);
  const [showSyncKey, setShowSyncKey] = useState(false);
  const [showTmdbKey, setShowTmdbKey] = useState(false);

  const [isSupabaseExpanded, setIsSupabaseExpanded] = useState(false);
  const [isTmdbExpanded, setIsTmdbExpanded] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [pendingImpact, setPendingImpact] = useState<SyncImpact | null>(null);

  const isCloudConnected = Boolean(prefs.supabaseUrl && prefs.syncKey);

  const handleSaveCredential = (key: keyof typeof prefs, val: any) => {
    const updated = { ...prefs, [key]: val };
    setPrefs(updated);
    Storage.savePreferences(updated);
  };

  const handleSyncPreview = async () => {
    if (!isCloudConnected) {
      setIsSupabaseExpanded(true);
      showToast('Configure Supabase credentials first');
      return;
    }
    setIsSyncing(true);
    try {
      const syncManager = new CloudSyncManager(prefs.supabaseUrl, prefs.supabaseAnonKey, prefs.syncKey);
      const impact = await syncManager.computeImpact(items, episodes, collections);
      setPendingImpact(impact);
    } catch (err: any) {
      showToast(`Sync preview failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsSyncing(false);
    }
  };

  const handleExecuteSync = async () => {
    setIsSyncing(true);
    try {
      const syncManager = new CloudSyncManager(prefs.supabaseUrl, prefs.supabaseAnonKey, prefs.syncKey);
      const res = await syncManager.sync(items, episodes, collections, collectionItems);
      Storage.saveMediaItems(res.updatedMedia);
      Storage.saveEpisodes(res.updatedEpisodes);
      Storage.saveCollections(res.updatedCollections);
      Storage.saveCollectionItems(res.updatedCollectionItems);
      Storage.savePreferences({ lastSyncTimestamp: Date.now() });
      setPrefs(Storage.getPreferences());
      refreshFromStorage();
      setPendingImpact(null);
      showToast(`Synced: ↑ ${res.summary.outgoing} sent · ↓ ${res.summary.incoming} received`);
    } catch (err: any) {
      showToast(`Sync failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsSyncing(false);
    }
  };

  const handleExportData = () => {
    const data = {
      mediaItems: items,
      episodes,
      collections,
      collectionItems,
      exportedAt: new Date().toISOString()
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `mediatracker-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    showToast('Exported backup JSON');
  };

  const handleImportData = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const parsed = JSON.parse(event.target?.result as string);
        if (parsed.mediaItems) Storage.saveMediaItems(parsed.mediaItems);
        if (parsed.episodes) Storage.saveEpisodes(parsed.episodes);
        if (parsed.collections) Storage.saveCollections(parsed.collections);
        if (parsed.collectionItems) Storage.saveCollectionItems(parsed.collectionItems);
        refreshFromStorage();
        showToast('Backup restored successfully');
      } catch {
        showToast('Failed to parse backup file');
      }
    };
    reader.readAsText(file);
  };

  const handleResetData = () => {
    if (window.confirm('Reset all media and restore default library samples?')) {
      Storage.clearAllData();
      refreshFromStorage();
      showToast('Library reset to defaults');
    }
  };

  return (
    <div className="min-h-screen pb-32">
      <Header
        category="PREFERENCES"
        title="Settings"
        showSearch={false}
        rightAction={
          <div
            className="px-3 py-1 rounded-full text-[10px] font-black uppercase tracking-wider border flex items-center gap-1.5"
            style={{
              backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
              borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB',
              color: isCloudConnected ? semantic.success : semantic.warning
            }}
          >
            <span
              className="w-1.5 h-1.5 rounded-full"
              style={{ backgroundColor: isCloudConnected ? semantic.success : semantic.warning }}
            />
            <span>{isCloudConnected ? 'CONNECTED' : 'STANDALONE'}</span>
          </div>
        }
      />

      <main className="px-4 pt-2 flex flex-col gap-4 max-w-4xl mx-auto">
        {/* Cloud Sync Quick Card */}
        <div
          className="p-4 rounded-[24px] border shadow-sm flex items-center justify-between"
          style={{
            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
          }}
        >
          <div className="flex items-center gap-3">
            <div
              className="w-11 h-11 rounded-[14px] flex items-center justify-center shadow-inner"
              style={{ backgroundColor: preset.accentHex + '20', color: preset.accentHex }}
            >
              <CloudSync className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-sm font-black">Cloud Library Sync</h3>
              <p className="text-xs text-gray-400">
                {prefs.lastSyncTimestamp
                  ? `Last synced: ${new Date(prefs.lastSyncTimestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
                  : 'Two-way cross-device sync with Mac'}
              </p>
            </div>
          </div>

          <button
            onClick={handleSyncPreview}
            disabled={isSyncing}
            className="px-4 py-2 rounded-[14px] text-xs font-bold shadow-md transition-transform active:scale-95 flex items-center gap-1.5"
            style={{
              backgroundColor: preset.accentHex,
              color: isDark ? preset.onPrimaryColor : '#FFFFFF'
            }}
          >
            {isSyncing ? (
              <RefreshCw className="w-4 h-4 animate-spin" />
            ) : (
              <RefreshCw className="w-4 h-4" />
            )}
            <span>Sync</span>
          </button>
        </div>

        {/* Collapsible Supabase Credentials */}
        <div
          className="p-4 rounded-[24px] border shadow-sm flex flex-col gap-3"
          style={{
            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
          }}
        >
          <div
            onClick={() => setIsSupabaseExpanded(!isSupabaseExpanded)}
            className="flex items-center justify-between cursor-pointer"
          >
            <div className="flex items-center gap-3">
              <div
                className="w-9 h-9 rounded-[12px] flex items-center justify-center"
                style={{ backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5', color: preset.accentHex }}
              >
                <Key className="w-4 h-4" />
              </div>
              <div>
                <h4 className="text-sm font-bold">Supabase Credentials</h4>
                <p className="text-[11px] text-gray-400">
                  {isCloudConnected ? 'Credentials configured' : 'Required for cloud sync'}
                </p>
              </div>
            </div>

            {isSupabaseExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </div>

          {isSupabaseExpanded && (
            <div className="flex flex-col gap-3 pt-2 border-t border-zinc-700/30">
              <div className="flex flex-col gap-1">
                <label className="text-[11px] font-bold text-gray-400">Project URL</label>
                <input
                  type="text"
                  value={prefs.supabaseUrl}
                  onChange={(e) => handleSaveCredential('supabaseUrl', e.target.value)}
                  placeholder="https://xyz.supabase.co"
                  className="w-full px-3 py-2 rounded-[12px] border text-xs outline-none"
                  style={{
                    backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F6F7FA',
                    borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB',
                    color: isDark ? preset.onSurfaceColor : '#16171C'
                  }}
                />
              </div>

              <div className="flex flex-col gap-1">
                <label className="text-[11px] font-bold text-gray-400">Anon Public Key</label>
                <div className="relative">
                  <input
                    type={showAnonKey ? 'text' : 'password'}
                    value={prefs.supabaseAnonKey}
                    onChange={(e) => handleSaveCredential('supabaseAnonKey', e.target.value)}
                    placeholder="sb_publishable_..."
                    className="w-full px-3 py-2 pr-10 rounded-[12px] border text-xs outline-none"
                    style={{
                      backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F6F7FA',
                      borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB',
                      color: isDark ? preset.onSurfaceColor : '#16171C'
                    }}
                  />
                  <button
                    onClick={() => setShowAnonKey(!showAnonKey)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400"
                  >
                    {showAnonKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>

              <div className="flex flex-col gap-1">
                <label className="text-[11px] font-bold text-gray-400">Sync Secret Key (User ID)</label>
                <div className="relative">
                  <input
                    type={showSyncKey ? 'text' : 'password'}
                    value={prefs.syncKey}
                    onChange={(e) => handleSaveCredential('syncKey', e.target.value)}
                    placeholder="mt_sync_..."
                    className="w-full px-3 py-2 pr-10 rounded-[12px] border text-xs outline-none"
                    style={{
                      backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F6F7FA',
                      borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB',
                      color: isDark ? preset.onSurfaceColor : '#16171C'
                    }}
                  />
                  <button
                    onClick={() => setShowSyncKey(!showSyncKey)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400"
                  >
                    {showSyncKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Collapsible TMDB API Metadata */}
        <div
          className="p-4 rounded-[24px] border shadow-sm flex flex-col gap-3"
          style={{
            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
          }}
        >
          <div
            onClick={() => setIsTmdbExpanded(!isTmdbExpanded)}
            className="flex items-center justify-between cursor-pointer"
          >
            <div className="flex items-center gap-3">
              <div
                className="w-9 h-9 rounded-[12px] flex items-center justify-center"
                style={{ backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5', color: '#10B981' }}
              >
                <Film className="w-4 h-4" />
              </div>
              <div>
                <h4 className="text-sm font-bold">TMDB API Configuration</h4>
                <p className="text-[11px] text-gray-400">
                  {prefs.tmdbApiKey ? 'Custom API key active' : 'Optional custom v3 key or token'}
                </p>
              </div>
            </div>

            {isTmdbExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </div>

          {isTmdbExpanded && (
            <div className="flex flex-col gap-3 pt-2 border-t border-zinc-700/30">
              <div className="flex flex-col gap-1">
                <label className="text-[11px] font-bold text-gray-400">TMDB API Key or Bearer Token</label>
                <div className="relative">
                  <input
                    type={showTmdbKey ? 'text' : 'password'}
                    value={prefs.tmdbApiKey}
                    onChange={(e) => handleSaveCredential('tmdbApiKey', e.target.value)}
                    placeholder="Enter v3 key or Bearer token..."
                    className="w-full px-3 py-2 pr-10 rounded-[12px] border text-xs outline-none"
                    style={{
                      backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F6F7FA',
                      borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB',
                      color: isDark ? preset.onSurfaceColor : '#16171C'
                    }}
                  />
                  <button
                    onClick={() => setShowTmdbKey(!showTmdbKey)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400"
                  >
                    {showTmdbKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Discovery Region Selector */}
        <div
          className="p-4 rounded-[24px] border shadow-sm flex items-center justify-between"
          style={{
            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
          }}
        >
          <div className="flex items-center gap-3">
            <div
              className="w-9 h-9 rounded-[12px] flex items-center justify-center"
              style={{ backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5', color: '#6366F1' }}
            >
              <Globe className="w-4 h-4" />
            </div>
            <div>
              <h4 className="text-sm font-bold">Discovery Region</h4>
              <p className="text-[11px] text-gray-400">Streaming providers country</p>
            </div>
          </div>

          <select
            value={prefs.providerRegion}
            onChange={(e) => handleSaveCredential('providerRegion', e.target.value)}
            className="px-3 py-1.5 rounded-[12px] border text-xs font-bold outline-none cursor-pointer"
            style={{
              backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F6F7FA',
              borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB',
              color: isDark ? preset.onSurfaceColor : '#16171C'
            }}
          >
            {PROVIDER_REGIONS.map((r) => (
              <option key={r.code} value={r.code} className="bg-zinc-900 text-white">
                {r.label}
              </option>
            ))}
          </select>
        </div>

        {/* Appearance & Theme Selector */}
        <div
          className="p-4 rounded-[24px] border shadow-sm flex flex-col gap-4"
          style={{
            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
          }}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div
                className="w-9 h-9 rounded-[12px] flex items-center justify-center"
                style={{ backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5', color: '#EC4899' }}
              >
                <Palette className="w-4 h-4" />
              </div>
              <div>
                <h4 className="text-sm font-bold">Theme & Appearance</h4>
                <p className="text-[11px] text-gray-400">M3 Expressive Color Presets</p>
              </div>
            </div>

            {/* Mode Switcher */}
            <div
              className="flex p-0.5 rounded-[12px] border"
              style={{
                backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5',
                borderColor: isDark ? preset.outlineColor + '20' : '#E5E7EB'
              }}
            >
              {[
                { mode: 'system', label: 'Auto', icon: Smartphone },
                { mode: 'dark', label: 'Dark', icon: Moon },
                { mode: 'light', label: 'Light', icon: Sun }
              ].map(({ mode, label, icon: IconComponent }) => (
                <button
                  key={mode}
                  onClick={() => setThemeMode(mode as any)}
                  className={`px-2.5 py-1 rounded-[10px] text-xs font-bold flex items-center gap-1 transition-all ${
                    themeMode === mode ? 'shadow-sm text-white' : 'opacity-60'
                  }`}
                  style={{
                    backgroundColor: themeMode === mode ? preset.accentHex : 'transparent'
                  }}
                >
                  <IconComponent className="w-3 h-3" />
                  <span className="hidden sm:inline">{label}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Theme Presets Swatches */}
          <div className="grid grid-cols-3 sm:grid-cols-6 gap-2">
            {THEME_PRESETS.map((p, idx) => {
              const isSelected = presetIndex === idx;
              return (
                <button
                  key={p.name}
                  onClick={() => setPresetIndex(idx)}
                  className={`p-2.5 rounded-[16px] border flex flex-col items-center gap-1.5 transition-all active:scale-95 ${
                    isSelected ? 'ring-2' : 'opacity-80 hover:opacity-100'
                  }`}
                  style={{
                    backgroundColor: isDark ? p.darkSurfaceHex : '#FFFFFF',
                    borderColor: isSelected ? p.accentHex : isDark ? preset.outlineColor + '30' : '#E5E7EB',
                    outlineColor: p.accentHex
                  }}
                >
                  <span className="w-5 h-5 rounded-full shadow" style={{ backgroundColor: p.accentHex }} />
                  <span className="text-[11px] font-bold">{p.name}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Library Backup & Management */}
        <div
          className="p-4 rounded-[24px] border shadow-sm flex flex-col gap-3"
          style={{
            backgroundColor: isDark ? preset.surfaceContainerHighColor : '#FFFFFF',
            borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
          }}
        >
          <h4 className="text-xs font-black uppercase tracking-wider text-gray-400">Library Management</h4>
          <div className="flex flex-wrap gap-2">
            <button
              onClick={handleExportData}
              className="flex-1 min-w-[140px] py-2.5 px-3 rounded-[14px] border text-xs font-bold flex items-center justify-center gap-2 transition-transform active:scale-95"
              style={{
                backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5',
                borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
              }}
            >
              <Download className="w-3.5 h-3.5" />
              <span>Export JSON</span>
            </button>

            <label
              className="flex-1 min-w-[140px] py-2.5 px-3 rounded-[14px] border text-xs font-bold flex items-center justify-center gap-2 cursor-pointer transition-transform active:scale-95"
              style={{
                backgroundColor: isDark ? preset.surfaceContainerHighestColor : '#F0F1F5',
                borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB'
              }}
            >
              <Upload className="w-3.5 h-3.5" />
              <span>Import JSON</span>
              <input type="file" accept=".json" onChange={handleImportData} className="hidden" />
            </label>

            <button
              onClick={handleResetData}
              className="py-2.5 px-3 rounded-[14px] border border-red-500/30 text-red-500 hover:bg-red-500/10 text-xs font-bold flex items-center gap-1.5 transition-transform active:scale-95"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>Reset</span>
            </button>
          </div>
        </div>

        {/* Sync Impact Modal */}
        {pendingImpact && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
            <div
              className="w-full max-w-sm rounded-[28px] p-6 border shadow-2xl flex flex-col gap-4"
              style={{
                backgroundColor: isDark ? preset.surfaceColor : '#FFFFFF',
                borderColor: isDark ? preset.outlineColor + '40' : '#E5E7EB'
              }}
            >
              <h3 className="text-lg font-black">Sync Preview</h3>

              <div className="text-xs flex flex-col gap-2 py-2">
                <div className="flex justify-between">
                  <span className="text-gray-400">Incoming Titles from Cloud:</span>
                  <span className="font-bold text-emerald-500">{pendingImpact.incomingTitles.length}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-400">Outgoing Local Titles to Cloud:</span>
                  <span className="font-bold text-blue-500">{pendingImpact.outgoingTitles.length}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-400">Conflicts (Latest Wins):</span>
                  <span className="font-bold text-amber-500">{pendingImpact.conflictTitles.length}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-400">Episode Status Syncs:</span>
                  <span className="font-bold">{pendingImpact.incomingEpisodes + pendingImpact.outgoingEpisodes}</span>
                </div>
              </div>

              <div className="flex gap-2 pt-2">
                <button
                  onClick={() => setPendingImpact(null)}
                  className="flex-1 py-2.5 rounded-[14px] text-xs font-bold border"
                  style={{ borderColor: isDark ? preset.outlineColor + '30' : '#E5E7EB' }}
                >
                  Cancel
                </button>
                <button
                  onClick={handleExecuteSync}
                  className="flex-1 py-2.5 rounded-[14px] text-xs font-bold shadow-md text-white"
                  style={{ backgroundColor: preset.accentHex }}
                >
                  Confirm & Sync
                </button>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
};
