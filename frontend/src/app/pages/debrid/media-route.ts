import { Router } from '@angular/router';
import { Addon, Category, Content, Metadata, Video } from '../../services/debrid-api.service';

/** Navigation state key carrying the card's data, so a content page can show its header at once. */
export const PREVIEW_STATE = 'mediaPreview';

/** The card data passed by the navigation in progress, or by the current history entry on back/forward. */
export function navigationPreview(router: Router): Metadata | null {
  const state =
    router.currentNavigation()?.extras.state ?? (history.state as Record<string, unknown> | null);
  const preview = state?.[PREVIEW_STATE] as Metadata | undefined;
  return preview?.identity && preview.title ? preview : null;
}

/** The title's presentation category; older payloads without `category` fall back to the addon type. */
export function metadataCategory(metadata: Metadata): Category | null {
  if (metadata.category) return metadata.category;
  return (
    ({ movie: 'MOVIE', series: 'SERIES', anime: 'ANIME' } as Record<string, Category>)[
      metadata.identity.resourceType
    ] ?? null
  );
}

export function categoryPrefix(category: Category): '/movie' | '/show' | '/anime' {
  return category === 'MOVIE' ? '/movie' : category === 'ANIME' ? '/anime' : '/show';
}

export function mediaRoute(metadata: Metadata, manifest?: Addon['manifest']): string[] | null {
  const category = metadataCategory(metadata);
  if (!category) return null;
  // The id must be one the addon resolves under its own type, whatever the classification says.
  const type = metadata.identity.resourceType;
  const namespaces = category === 'MOVIE' ? ['tmdb', 'tvdb', 'imdb'] : ['tvdb', 'tmdb', 'imdb'];
  const mapped = namespaces.flatMap((namespace) => {
    const id = metadata.identity.mappedIds?.[namespace];
    return id ? [namespace === 'imdb' ? id : namespace + ':' + id] : [];
  });
  const id =
    mapped.find((id) =>
      manifest?.resources?.some(
        (resource) =>
          resource.name === 'meta' &&
          (!resource.types?.length || resource.types.includes(type)) &&
          resource.idPrefixes?.some((prefix) => id.startsWith(prefix)),
      ),
    ) ?? metadata.identity.contentId;
  return [categoryPrefix(category), id];
}

export function mediaContent(metadata: Metadata, video?: Video): Content {
  return {
    identity: { ...metadata.identity, ...(video ? { videoId: video.id } : {}) },
    category: metadataCategory(metadata) ?? 'SERIES',
    title: metadata.title,
    year: metadata.year,
    posterUrl: metadata.posterUrl,
    ...(video ? { season: video.season, episode: video.episode, episodeTitle: video.title } : {}),
  };
}
