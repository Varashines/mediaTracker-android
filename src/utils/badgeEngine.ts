import { MediaStatus, MediaType } from '../types';

export function getStatusBadge(status: MediaStatus): { label: string; bg: string; text: string; border: string; dot: string } {
  switch (status) {
    case 'watching':
      return {
        label: 'Watching / In Progress',
        bg: 'bg-emerald-500/10',
        text: 'text-emerald-400',
        border: 'border-emerald-500/20',
        dot: 'bg-emerald-400',
      };
    case 'plan_to_watch':
      return {
        label: 'Plan to Watch',
        bg: 'bg-sky-500/10',
        text: 'text-sky-400',
        border: 'border-sky-500/20',
        dot: 'bg-sky-400',
      };
    case 'completed':
      return {
        label: 'Completed',
        bg: 'bg-indigo-500/10',
        text: 'text-indigo-400',
        border: 'border-indigo-500/20',
        dot: 'bg-indigo-400',
      };
    case 'on_hold':
      return {
        label: 'On Hold',
        bg: 'bg-amber-500/10',
        text: 'text-amber-400',
        border: 'border-amber-500/20',
        dot: 'bg-amber-400',
      };
    case 'dropped':
      return {
        label: 'Dropped',
        bg: 'bg-rose-500/10',
        text: 'text-rose-400',
        border: 'border-rose-500/20',
        dot: 'bg-rose-400',
      };
    default:
      return {
        label: status,
        bg: 'bg-zinc-800',
        text: 'text-zinc-300',
        border: 'border-zinc-700',
        dot: 'bg-zinc-400',
      };
  }
}

export function getTypeBadge(type: MediaType): { label: string; iconName: string; color: string } {
  switch (type) {
    case 'movie':
      return { label: 'Movie', iconName: 'Film', color: 'text-rose-400 bg-rose-500/10 border-rose-500/20' };
    case 'tv':
      return { label: 'TV Show', iconName: 'Tv', color: 'text-blue-400 bg-blue-500/10 border-blue-500/20' };
    case 'anime':
      return { label: 'Anime', iconName: 'Sparkles', color: 'text-purple-400 bg-purple-500/10 border-purple-500/20' };
    case 'game':
      return { label: 'Game', iconName: 'Gamepad2', color: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20' };
    case 'book':
      return { label: 'Book / Manga', iconName: 'BookOpen', color: 'text-amber-400 bg-amber-500/10 border-amber-500/20' };
  }
}
