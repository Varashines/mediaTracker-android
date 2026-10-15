import React, { createContext, useContext, useState, useEffect } from 'react';

export type AccentColor = 'indigo' | 'violet' | 'emerald' | 'rose' | 'amber' | 'cyan';

export interface ThemePreset {
  name: string;
  accentHex: string;
  surfaceContainerHighColor: string;
  surfaceContainerHighestColor: string;
  backgroundColor: string;
  surfaceColor: string;
  darkSurfaceHex: string;
  onSurfaceColor: string;
  onPrimaryColor: string;
  outlineColor: string;
}

export const THEME_PRESETS: ThemePreset[] = [
  {
    name: 'Expressive Indigo',
    accentHex: '#6366F1',
    surfaceContainerHighColor: '#1E1E2E',
    surfaceContainerHighestColor: '#28283E',
    backgroundColor: '#0F0F17',
    surfaceColor: '#1E1E2E',
    darkSurfaceHex: '#1E1E2E',
    onSurfaceColor: '#F3F4F6',
    onPrimaryColor: '#FFFFFF',
    outlineColor: '#4B5563',
  },
  {
    name: 'Vibrant Violet',
    accentHex: '#8B5CF6',
    surfaceContainerHighColor: '#211B32',
    surfaceContainerHighestColor: '#2C2443',
    backgroundColor: '#100D1C',
    surfaceColor: '#211B32',
    darkSurfaceHex: '#211B32',
    onSurfaceColor: '#F3F4F6',
    onPrimaryColor: '#FFFFFF',
    outlineColor: '#6B7280',
  },
  {
    name: 'Emerald Cinema',
    accentHex: '#10B981',
    surfaceContainerHighColor: '#122520',
    surfaceContainerHighestColor: '#1A332C',
    backgroundColor: '#081310',
    surfaceColor: '#122520',
    darkSurfaceHex: '#122520',
    onSurfaceColor: '#F3F4F6',
    onPrimaryColor: '#FFFFFF',
    outlineColor: '#374151',
  },
  {
    name: 'Crimson Rose',
    accentHex: '#F43F5E',
    surfaceContainerHighColor: '#28151D',
    surfaceContainerHighestColor: '#361D27',
    backgroundColor: '#140A0F',
    surfaceColor: '#28151D',
    darkSurfaceHex: '#28151D',
    onSurfaceColor: '#F3F4F6',
    onPrimaryColor: '#FFFFFF',
    outlineColor: '#4B5563',
  },
  {
    name: 'Amber Sunset',
    accentHex: '#F59E0B',
    surfaceContainerHighColor: '#282015',
    surfaceContainerHighestColor: '#372B1B',
    backgroundColor: '#140F08',
    surfaceColor: '#282015',
    darkSurfaceHex: '#282015',
    onSurfaceColor: '#F3F4F6',
    onPrimaryColor: '#000000',
    outlineColor: '#4B5563',
  },
];

interface ThemeContextType {
  accentColor: AccentColor;
  setAccentColor: (accent: AccentColor) => void;
  amoledMode: boolean;
  setAmoledMode: (val: boolean) => void;
  preset: ThemePreset;
  presetIndex: number;
  setPresetIndex: (idx: number) => void;
  themeMode: 'dark' | 'light' | 'system';
  setThemeMode: (mode: 'dark' | 'light' | 'system') => void;
  isDark: boolean;
  semantic: {
    success: string;
    warning: string;
    error: string;
    info: string;
  };
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [accentColor, setAccentColor] = useState<AccentColor>(() => {
    return (localStorage.getItem('mediatracker_accent') as AccentColor) || 'indigo';
  });
  const [amoledMode, setAmoledMode] = useState<boolean>(() => {
    return localStorage.getItem('mediatracker_amoled') === 'true';
  });
  const [presetIndex, setPresetIndex] = useState<number>(() => {
    const saved = localStorage.getItem('mediatracker_preset_idx');
    return saved !== null ? parseInt(saved, 10) : 0;
  });
  const [themeMode, setThemeMode] = useState<'dark' | 'light' | 'system'>('dark');

  const preset = THEME_PRESETS[presetIndex] || THEME_PRESETS[0];

  useEffect(() => {
    localStorage.setItem('mediatracker_accent', accentColor);
  }, [accentColor]);

  useEffect(() => {
    localStorage.setItem('mediatracker_preset_idx', String(presetIndex));
  }, [presetIndex]);

  useEffect(() => {
    localStorage.setItem('mediatracker_amoled', String(amoledMode));
    if (amoledMode) {
      document.documentElement.classList.add('amoled-mode');
    } else {
      document.documentElement.classList.remove('amoled-mode');
    }
  }, [amoledMode]);

  const isDark = themeMode === 'dark' || themeMode === 'system';

  const semantic = {
    success: '#10B981',
    warning: '#F59E0B',
    error: '#EF4444',
    info: '#3B82F6',
  };

  return (
    <ThemeContext.Provider
      value={{
        accentColor,
        setAccentColor,
        amoledMode,
        setAmoledMode,
        preset,
        presetIndex,
        setPresetIndex,
        themeMode,
        setThemeMode,
        isDark,
        semantic,
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
};

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within ThemeProvider');
  }
  return context;
}
