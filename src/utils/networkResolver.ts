export interface NetworkMeta {
  name: string;
  badgeClass: string;
  icon?: string;
}

export function resolveNetwork(networkName?: string): NetworkMeta {
  if (!networkName) {
    return { name: 'Direct', badgeClass: 'bg-zinc-800 text-zinc-300 border-zinc-700' };
  }

  const lower = networkName.toLowerCase();

  if (lower.includes('hbo') || lower.includes('max')) {
    return { name: 'HBO Max', badgeClass: 'bg-violet-950/80 text-violet-300 border-violet-700/50' };
  }
  if (lower.includes('netflix')) {
    return { name: 'Netflix', badgeClass: 'bg-red-950/80 text-red-300 border-red-700/50' };
  }
  if (lower.includes('apple')) {
    return { name: 'Apple TV+', badgeClass: 'bg-zinc-900 text-zinc-200 border-zinc-600' };
  }
  if (lower.includes('disney')) {
    return { name: 'Disney+', badgeClass: 'bg-sky-950/80 text-sky-300 border-sky-700/50' };
  }
  if (lower.includes('crunchyroll')) {
    return { name: 'Crunchyroll', badgeClass: 'bg-orange-950/80 text-orange-300 border-orange-700/50' };
  }
  if (lower.includes('amazon') || lower.includes('prime')) {
    return { name: 'Prime Video', badgeClass: 'bg-cyan-950/80 text-cyan-300 border-cyan-700/50' };
  }
  if (lower.includes('steam') || lower.includes('pc')) {
    return { name: 'Steam / PC', badgeClass: 'bg-slate-900 text-blue-300 border-blue-700/50' };
  }
  if (lower.includes('playstation') || lower.includes('ps5')) {
    return { name: 'PlayStation', badgeClass: 'bg-blue-950/80 text-blue-300 border-blue-600/50' };
  }
  if (lower.includes('nintendo') || lower.includes('switch')) {
    return { name: 'Nintendo', badgeClass: 'bg-rose-950/80 text-rose-300 border-rose-600/50' };
  }
  if (lower.includes('xbox')) {
    return { name: 'Xbox', badgeClass: 'bg-emerald-950/80 text-emerald-300 border-emerald-600/50' };
  }

  return { name: networkName, badgeClass: 'bg-zinc-800 text-zinc-300 border-zinc-700' };
}

export const NetworkResolver = {
  resolve(item: any): NetworkMeta {
    return resolveNetwork(item?.network);
  }
};
