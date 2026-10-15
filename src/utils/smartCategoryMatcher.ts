import { MediaItem } from '../types';

export interface SmartInsights {
  totalItems: number;
  watchingCount: number;
  completedCount: number;
  planToWatchCount: number;
  typeDistribution: Record<string, number>;
  averageRating: number;
  estimatedHoursSpent: number;
  upNextItems: MediaItem[];
  upcomingAiringItems: MediaItem[];
  backlogItems: MediaItem[];
  recentlyCompleted: MediaItem[];
}

export function computeSmartInsights(items: MediaItem[]): SmartInsights {
  const totalItems = items.length;
  let watchingCount = 0;
  let completedCount = 0;
  let planToWatchCount = 0;
  let totalRatingSum = 0;
  let ratedCount = 0;
  let estimatedHoursSpent = 0;

  const typeDistribution: Record<string, number> = {
    movie: 0,
    tv: 0,
    anime: 0,
    game: 0,
    book: 0,
  };

  const now = new Date();

  items.forEach((item) => {
    // Type distribution
    typeDistribution[item.type] = (typeDistribution[item.type] || 0) + 1;

    // Status counts
    if (item.status === 'watching') watchingCount++;
    if (item.status === 'completed') completedCount++;
    if (item.status === 'plan_to_watch') planToWatchCount++;

    // Ratings
    const effectiveRating = item.userRating ?? item.rating;
    if (effectiveRating) {
      totalRatingSum += effectiveRating;
      ratedCount++;
    }

    // Rough hours estimation
    if (item.type === 'movie') {
      estimatedHoursSpent += (item.runtimeMinutes || 120) / 60;
    } else if (item.type === 'tv' || item.type === 'anime') {
      const epCount = item.progress.current || 0;
      const epLength = item.type === 'anime' ? 24 : 48; // minutes
      estimatedHoursSpent += (epCount * epLength) / 60;
    } else if (item.type === 'game') {
      estimatedHoursSpent += item.progress.current || 0;
    } else if (item.type === 'book') {
      // 1 hour per 50 pages
      const pages = item.progress.current || 0;
      estimatedHoursSpent += pages / 50;
    }
  });

  const averageRating = ratedCount > 0 ? parseFloat((totalRatingSum / ratedCount).toFixed(1)) : 0;

  // Up Next: actively watching items, sorted by recent updates
  const upNextItems = items
    .filter((i) => i.status === 'watching')
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());

  // Upcoming Airing: items with nextAirDate in future or releaseDate in future
  const upcomingAiringItems = items
    .filter((i) => {
      const airDate = i.nextAirDate || (i.status === 'plan_to_watch' ? i.releaseDate : null);
      if (!airDate) return false;
      const d = new Date(airDate);
      return d.getTime() >= now.getTime() - 24 * 60 * 60 * 1000;
    })
    .sort((a, b) => {
      const dateA = new Date(a.nextAirDate || a.releaseDate).getTime();
      const dateB = new Date(b.nextAirDate || b.releaseDate).getTime();
      return dateA - dateB;
    });

  // Backlog: plan_to_watch items
  const backlogItems = items
    .filter((i) => i.status === 'plan_to_watch')
    .sort((a, b) => (b.rating || 0) - (a.rating || 0));

  // Recently Completed
  const recentlyCompleted = items
    .filter((i) => i.status === 'completed')
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
    .slice(0, 8);

  return {
    totalItems,
    watchingCount,
    completedCount,
    planToWatchCount,
    typeDistribution,
    averageRating,
    estimatedHoursSpent: Math.round(estimatedHoursSpent),
    upNextItems,
    upcomingAiringItems,
    backlogItems,
    recentlyCompleted,
  };
}
