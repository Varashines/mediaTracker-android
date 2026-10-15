export function formatRelativeRelease(dateString?: string): {
  label: string;
  isUpcoming: boolean;
  daysDiff: number;
} {
  if (!dateString) {
    return { label: 'TBA', isUpcoming: false, daysDiff: 0 };
  }

  const targetDate = new Date(dateString);
  const now = new Date();

  // Reset hours to compare purely by calendar day
  const targetMidnight = new Date(targetDate.getFullYear(), targetDate.getMonth(), targetDate.getDate()).getTime();
  const nowMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();

  const diffMs = targetMidnight - nowMidnight;
  const daysDiff = Math.round(diffMs / (1000 * 60 * 60 * 24));

  if (daysDiff === 0) {
    return { label: 'Airing Today', isUpcoming: true, daysDiff: 0 };
  }
  if (daysDiff === 1) {
    return { label: 'Tomorrow', isUpcoming: true, daysDiff: 1 };
  }
  if (daysDiff > 1 && daysDiff <= 7) {
    return { label: `In ${daysDiff} days`, isUpcoming: true, daysDiff };
  }
  if (daysDiff > 7 && daysDiff <= 30) {
    const weeks = Math.round(daysDiff / 7);
    return { label: `In ${weeks} ${weeks === 1 ? 'week' : 'weeks'}`, isUpcoming: true, daysDiff };
  }
  if (daysDiff > 30) {
    const formatted = targetDate.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: targetDate.getFullYear() !== now.getFullYear() ? 'numeric' : undefined,
    });
    return { label: formatted, isUpcoming: true, daysDiff };
  }

  if (daysDiff === -1) {
    return { label: 'Yesterday', isUpcoming: false, daysDiff: -1 };
  }

  const formatted = targetDate.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
  return { label: formatted, isUpcoming: false, daysDiff };
}

export function formatDuration(minutes?: number): string {
  if (!minutes || minutes <= 0) return '';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}
