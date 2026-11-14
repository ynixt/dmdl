import { Category } from '../../services/debrid-api.service';

export interface ExternalLink {
  provider: string;
  label: string;
  url: string;
}

/** Title pages on each database, in display order. Ids are validated by the backend. */
const PROVIDERS: readonly {
  provider: string;
  label: string;
  url: (id: string, movie: boolean) => string;
}[] = [
  { provider: 'imdb', label: 'IMDb', url: (id) => `https://www.imdb.com/title/${id}` },
  {
    provider: 'tmdb',
    label: 'TMDB',
    url: (id, movie) => `https://www.themoviedb.org/${movie ? 'movie' : 'tv'}/${id}`,
  },
  {
    provider: 'tvdb',
    label: 'TVDB',
    url: (id, movie) => `https://thetvdb.com/dereferrer/${movie ? 'movie' : 'series'}/${id}`,
  },
  { provider: 'kitsu', label: 'Kitsu', url: (id) => `https://kitsu.app/anime/${id}` },
  { provider: 'mal', label: 'MyAnimeList', url: (id) => `https://myanimelist.net/anime/${id}` },
  { provider: 'anilist', label: 'AniList', url: (id) => `https://anilist.co/anime/${id}` },
  { provider: 'anidb', label: 'AniDB', url: (id) => `https://anidb.net/anime/${id}` },
];

/** One link per mapped id with a known provider; unknown providers are skipped. */
export function externalLinks(
  mappedIds: Record<string, string> | undefined,
  category: Category | null,
): ExternalLink[] {
  const movie = category === 'MOVIE';
  return PROVIDERS.flatMap(({ provider, label, url }) => {
    const id = mappedIds?.[provider];
    return id ? [{ provider, label, url: url(encodeURIComponent(id), movie) }] : [];
  });
}
