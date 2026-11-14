import { inject } from '@angular/core';
import { Router, Routes, UrlMatchResult, UrlSegment } from '@angular/router';
import { ShowShell } from './show-shell.component';

const SHOW_PREFIXES = ['show', 'anime'];

/** `/show/:id` and `/anime/:id` as one route, so moving a title between prefixes keeps the loaded shell. */
function showMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length < 2 || !SHOW_PREFIXES.includes(segments[0].path)) return null;
  return { consumed: segments.slice(0, 2), posParams: { prefix: segments[0], id: segments[1] } };
}

/** `/show/:id/:season` (no episode) → the show page with that season expanded. */
function seasonMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length !== 3 || !SHOW_PREFIXES.includes(segments[0].path)) return null;
  return {
    consumed: segments,
    posParams: { prefix: segments[0], id: segments[1], season: segments[2] },
  };
}

export function mediaShowRoutes(): Routes {
  return [
    {
      matcher: seasonMatcher,
      redirectTo: ({ params, queryParams, fragment }) =>
        inject(Router).createUrlTree(['/' + params['prefix'], params['id']], {
          queryParams: { ...queryParams, season: params['season'] },
          fragment: fragment ?? undefined,
        }),
    },
    {
      matcher: showMatcher,
      component: ShowShell,
      children: [
        {
          path: '',
          pathMatch: 'full',
          loadComponent: () => import('./show.page').then((m) => m.ShowPage),
        },
        {
          path: ':season/:episode',
          loadComponent: () => import('./episode.page').then((m) => m.EpisodePage),
        },
      ],
    },
  ];
}
