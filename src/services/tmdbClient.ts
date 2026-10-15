import { MediaItem, MediaType, TMDBSearchItem, TMDBCastMember } from '../types';

export interface SearchResultItem {
  id: string;
  tmdbId?: number;
  title: string;
  originalTitle?: string;
  type: MediaType;
  posterUrl: string;
  backdropUrl?: string;
  rating: number;
  releaseDate: string;
  genres: string[];
  overview: string;
  network?: string;
  runtimeMinutes?: number;
  totalEpisodes?: number;
  totalSeasons?: number;
}

export class TMDBClient {
  private apiKey: string;

  constructor(apiKey: string = '') {
    this.apiKey = apiKey;
  }

  static buildPosterUrl(path?: string | null): string {
    if (!path) return 'https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=800&q=80';
    if (path.startsWith('http')) return path;
    return `https://image.tmdb.org/t/p/w500${path}`;
  }

  static buildBackdropUrl(path?: string | null): string {
    if (!path) return 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=1400&q=80';
    if (path.startsWith('http')) return path;
    return `https://image.tmdb.org/t/p/original${path}`;
  }

  static buildProfileUrl(path?: string | null): string {
    if (!path) return 'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80';
    if (path.startsWith('http')) return path;
    return `https://image.tmdb.org/t/p/w185${path}`;
  }

  async searchMulti(query: string): Promise<TMDBSearchItem[]> {
    if (!query.trim()) return [];
    if (this.apiKey) {
      try {
        const url = `https://api.themoviedb.org/3/search/multi?api_key=${this.apiKey}&query=${encodeURIComponent(query)}&include_adult=false`;
        const res = await fetch(url);
        if (res.ok) {
          const data = await res.json();
          return data.results || [];
        }
      } catch (e) {
        console.warn('TMDB search failed, fallback to mock catalog:', e);
      }
    }
    // Fallback to searching catalog
    return PRELOADED_CATALOG
      .filter((it) => it.title.toLowerCase().includes(query.toLowerCase()))
      .map((it) => ({
        id: it.tmdbId || 1000,
        media_type: it.type === 'movie' ? 'movie' : 'tv',
        title: it.title,
        overview: it.overview,
        poster_path: it.posterUrl,
        backdrop_path: it.backdropUrl,
        release_date: it.releaseDate,
        vote_average: it.rating
      }));
  }

  async getMovieCredits(id: number): Promise<any> {
    const defaultCast: TMDBCastMember[] = [
      { id: 1, name: 'Lead Performer', character: 'Protagonist', profile_path: null },
      { id: 2, name: 'Supporting Star', character: 'Companion', profile_path: null }
    ];
    return { cast: defaultCast };
  }

  async getTvCredits(id: number): Promise<any> {
    const defaultCast: TMDBCastMember[] = [
      { id: 1, name: 'Series Lead', character: 'Main Role', profile_path: null },
      { id: 2, name: 'Co-Star', character: 'Recurring Role', profile_path: null }
    ];
    return { cast: defaultCast };
  }

  async getTVCredits(id: number): Promise<any> {
    return this.getTvCredits(id);
  }
}

// Curated instant catalog for rich offline/mock searching and discovery
const PRELOADED_CATALOG: SearchResultItem[] = [
  {
    id: 'cat-dune-2',
    tmdbId: 693134,
    title: 'Dune: Part Two',
    type: 'movie',
    posterUrl: 'https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=1400&q=80',
    rating: 8.6,
    releaseDate: '2024-03-01',
    genres: ['Science Fiction', 'Adventure', 'Drama'],
    overview: 'Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family.',
    network: 'Warner Bros / Max',
    runtimeMinutes: 166,
  },
  {
    id: 'cat-oppenheimer',
    tmdbId: 872585,
    title: 'Oppenheimer',
    type: 'movie',
    posterUrl: 'https://images.unsplash.com/photo-1440404653325-ab127d49abc1?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1451187580459-43490279c0fa?auto=format&fit=crop&w=1400&q=80',
    rating: 8.9,
    releaseDate: '2023-07-21',
    genres: ['Biography', 'Drama', 'History'],
    overview: 'The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb.',
    network: 'Universal Pictures',
    runtimeMinutes: 180,
  },
  {
    id: 'cat-severance',
    tmdbId: 95557,
    title: 'Severance',
    type: 'tv',
    posterUrl: 'https://images.unsplash.com/photo-1518770660439-4636190af475?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=1400&q=80',
    rating: 8.7,
    releaseDate: '2022-02-18',
    genres: ['Sci-Fi', 'Mystery', 'Thriller'],
    overview: 'Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.',
    network: 'Apple TV+',
    totalEpisodes: 19,
    totalSeasons: 2,
  },
  {
    id: 'cat-shogun',
    tmdbId: 126308,
    title: 'Shōgun',
    type: 'tv',
    posterUrl: 'https://images.unsplash.com/photo-1528164344705-475426879c0d?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1503899036084-c55cdd92da26?auto=format&fit=crop&w=1400&q=80',
    rating: 8.8,
    releaseDate: '2024-02-27',
    genres: ['Drama', 'War', 'Historical'],
    overview: 'In 1600 feudal Japan, Lord Yoshii Toranaga discovers secrets that could tip the scales of power against his formidable enemies.',
    network: 'FX / Hulu',
    totalEpisodes: 10,
    totalSeasons: 1,
  },
  {
    id: 'cat-the-bear',
    tmdbId: 136283,
    title: 'The Bear',
    type: 'tv',
    posterUrl: 'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1414235077428-338989a2e8c0?auto=format&fit=crop&w=1400&q=80',
    rating: 8.6,
    releaseDate: '2022-06-23',
    genres: ['Drama', 'Comedy'],
    overview: 'A young chef from the fine dining world returns home to Chicago to run his family Italian beef sandwich shop.',
    network: 'FX / Hulu',
    totalEpisodes: 28,
    totalSeasons: 3,
  },
  {
    id: 'cat-arcane',
    tmdbId: 94605,
    title: 'Arcane',
    type: 'anime',
    posterUrl: 'https://images.unsplash.com/photo-1563089145-599997674d42?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=1400&q=80',
    rating: 9.0,
    releaseDate: '2021-11-06',
    genres: ['Animation', 'Action', 'Sci-Fi'],
    overview: 'Set in the utopian region of Piltover and the oppressed underground of Zaun, the story follows the origins of two iconic League champions.',
    network: 'Netflix',
    totalEpisodes: 18,
    totalSeasons: 2,
  },
  {
    id: 'cat-frieren',
    tmdbId: 192300,
    title: 'Frieren: Beyond Journey\'s End',
    type: 'anime',
    posterUrl: 'https://images.unsplash.com/photo-1578632767115-351597cf2477?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=1400&q=80',
    rating: 9.1,
    releaseDate: '2023-09-29',
    genres: ['Fantasy', 'Adventure', 'Drama'],
    overview: 'An elf mage reflecting upon life, memory, and companionship decades after defeating the Demon King.',
    network: 'Crunchyroll',
    totalEpisodes: 28,
    totalSeasons: 1,
  },
  {
    id: 'cat-solo-leveling',
    tmdbId: 209867,
    title: 'Solo Leveling',
    type: 'anime',
    posterUrl: 'https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?auto=format&fit=crop&w=1400&q=80',
    rating: 8.5,
    releaseDate: '2024-01-07',
    genres: ['Action', 'Fantasy', 'Supernatural'],
    overview: 'In a world where hunters must battle deadly monsters to protect mankind, Sung Jin-woo awakens a mysterious quest system.',
    network: 'Crunchyroll',
    totalEpisodes: 12,
    totalSeasons: 1,
  },
  {
    id: 'cat-black-myth',
    title: 'Black Myth: Wukong',
    type: 'game',
    posterUrl: 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=1400&q=80',
    rating: 8.9,
    releaseDate: '2024-08-20',
    genres: ['Action RPG', 'Mythology', 'Souls-like'],
    overview: 'Set out as the Destined One in this action RPG rooted in Chinese mythology and Journey to the West.',
    network: 'Steam / PS5',
  },
  {
    id: 'cat-gta-6',
    title: 'Grand Theft Auto VI',
    type: 'game',
    posterUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=1400&q=80',
    rating: 9.8,
    releaseDate: '2026-09-15',
    genres: ['Open World', 'Action-Adventure', 'Crime'],
    overview: 'Grand Theft Auto VI heads to the state of Leonida, home to the neon-soaked streets of Vice City and beyond.',
    network: 'PlayStation 5 / Xbox',
  },
  {
    id: 'cat-three-body',
    title: 'The Three-Body Problem',
    type: 'book',
    posterUrl: 'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1451187580459-43490279c0fa?auto=format&fit=crop&w=1400&q=80',
    rating: 8.8,
    releaseDate: '2008-01-01',
    genres: ['Sci-Fi', 'First Contact', 'Physics'],
    overview: 'Set against the backdrop of China\'s Cultural Revolution, a secret military project sends signals into space to establish contact with aliens.',
    network: 'Tor Books',
  },
  {
    id: 'cat-wind-and-truth',
    title: 'Wind and Truth',
    type: 'book',
    posterUrl: 'https://images.unsplash.com/photo-1512820790803-83ca734da794?auto=format&fit=crop&w=800&q=80',
    backdropUrl: 'https://images.unsplash.com/photo-1451187580459-43490279c0fa?auto=format&fit=crop&w=1400&q=80',
    rating: 9.5,
    releaseDate: '2024-12-06',
    genres: ['Epic Fantasy', 'Cosmere', 'High Fantasy'],
    overview: 'The climactic fifth volume and first arc finale of Brandon Sanderson\'s acclaimed Stormlight Archive series.',
    network: 'Tor Books',
  }
];

export async function searchMediaCatalog(
  query: string,
  typeFilter: MediaType | 'all' = 'all',
  tmdbApiKey?: string
): Promise<SearchResultItem[]> {
  const trimmed = query.trim().toLowerCase();

  // If TMDB API key is provided, query TMDB live
  if (tmdbApiKey && tmdbApiKey.length > 10 && trimmed.length > 1) {
    try {
      const liveResults = await queryLiveTmdb(trimmed, typeFilter, tmdbApiKey);
      if (liveResults && liveResults.length > 0) {
        return liveResults;
      }
    } catch (e) {
      console.warn('Live TMDB query failed, falling back to curated catalog:', e);
    }
  }

  // Fallback to local catalog filtering
  return PRELOADED_CATALOG.filter((item) => {
    const matchesType = typeFilter === 'all' || item.type === typeFilter;
    if (!matchesType) return false;
    if (!trimmed) return true;

    return (
      item.title.toLowerCase().includes(trimmed) ||
      (item.originalTitle && item.originalTitle.toLowerCase().includes(trimmed)) ||
      item.genres.some((g) => g.toLowerCase().includes(trimmed)) ||
      (item.network && item.network.toLowerCase().includes(trimmed))
    );
  });
}

async function queryLiveTmdb(query: string, type: MediaType | 'all', apiKey: string): Promise<SearchResultItem[]> {
  const endpoint = type === 'movie'
    ? `https://api.themoviedb.org/3/search/movie`
    : type === 'tv' || type === 'anime'
    ? `https://api.themoviedb.org/3/search/tv`
    : `https://api.themoviedb.org/3/search/multi`;

  const url = `${endpoint}?api_key=${apiKey}&query=${encodeURIComponent(query)}&include_adult=false`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`TMDB error: ${res.status}`);

  const data = await res.json();
  const results: any[] = data.results || [];

  return results.slice(0, 10).map((r) => {
    const mediaType: MediaType = r.media_type === 'movie' || type === 'movie' ? 'movie' : 'tv';
    const title = r.title || r.name || 'Untitled';
    const releaseDate = r.release_date || r.first_air_date || '';
    const posterUrl = r.poster_path
      ? `https://image.tmdb.org/t/p/w500${r.poster_path}`
      : 'https://images.unsplash.com/photo-1534447677768-be436bb09401?auto=format&fit=crop&w=800&q=80';
    const backdropUrl = r.backdrop_path
      ? `https://image.tmdb.org/t/p/original${r.backdrop_path}`
      : undefined;

    return {
      id: `tmdb-${r.id}`,
      tmdbId: r.id,
      title,
      originalTitle: r.original_title || r.original_name,
      type: mediaType,
      posterUrl,
      backdropUrl,
      rating: r.vote_average ? parseFloat(r.vote_average.toFixed(1)) : 0,
      releaseDate,
      genres: ['Entertainment'],
      overview: r.overview || '',
      network: mediaType === 'tv' ? 'Television' : 'Cinema',
    };
  });
}
