import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { Component, provideAppInitializer, inject } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { By } from '@angular/platform-browser';
import { Catalog, DebridApi, Metadata } from '../../services/debrid-api.service';
import { DiscoveryPage } from './discovery.page';
import { SearchPage } from './search.page';
import { MoviePage } from './movie.page';
import { ShowShell } from './show-shell.component';
import { ShowPage } from './show.page';
import { EpisodePage } from './episode.page';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { ApiAvailabilityService } from '../../core/api-availability.service';
import { of } from 'rxjs';
import { NavigationHistory } from './navigation-history.service';
import { ReleasesPanelComponent } from './releases-panel.component';
import { CatalogRowComponent } from './catalog-row.component';

@Component({ template: '' })
class ContentDestination {}

describe('Discovery catalog routing', () => {
  const catalogs: Catalog[] = Array.from({ length: 14 }, (_, index) => ({
    id: String(index),
    type: 'movie',
    name: 'Catalog ' + index,
    searchable: true,
    browsable: true,
    pageable: true,
  }));
  const items: Metadata[] = Array.from({ length: 24 }, (_, index) => ({
    identity: {
      sourceId: 'meta',
      sourceRevision: 7,
      resourceType: 'movie',
      contentId: 'tt' + index,
    },
    title: 'Movie ' + index,
    videos: [],
  }));
  let advertised: Catalog[];
  const api = {
    get: vi.fn(async (path: string): Promise<unknown> => {
      if (path === '/addons')
        return {
          metadata: { id: 'meta', revision: 7, manifest: { catalogs: advertised } },
          search: [],
        };
      return items;
    }),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    advertised = catalogs;
    api.get.mockImplementation(async (path: string): Promise<unknown> => {
      if (path === '/addons')
        return {
          metadata: { id: 'meta', revision: 7, manifest: { catalogs: advertised } },
          search: [],
        };
      return items;
    });
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1400 });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'discovery', component: DiscoveryPage },
          { path: 'search', component: SearchPage },
          { path: 'movie/:id', component: ContentDestination },
        ]),
        provideLocationMocks(),
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
      ],
    });
  });

  async function open() {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/discovery', DiscoveryPage);
    await harness.fixture.whenStable();
    harness.detectChanges();
    await harness.fixture.whenStable();
    return { harness, page };
  }

  it('navigates to search with a push and displays the first ten browsable catalogs', async () => {
    advertised = [{ ...catalogs[0], id: 'only-search', browsable: false }, ...catalogs];
    const { page, harness } = await open();
    expect(page.rows.map((catalog) => catalog.id)).toEqual(
      catalogs.slice(0, 10).map((catalog) => catalog.id),
    );
    expect(harness.routeNativeElement!.querySelectorAll('app-catalog-row')).toHaveLength(10);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await page.search({ catalog: catalogs[0], query: ' dune ' });
    expect(navigate).toHaveBeenCalledWith(['/search'], {
      queryParams: { q: 'dune', catalog: 'movie:0' },
    });
  });

  it('hides empty and failed rows while other catalogs load independently', async () => {
    advertised = catalogs.slice(0, 3);
    api.get.mockImplementation(async (path: string): Promise<unknown> => {
      if (path === '/addons')
        return {
          metadata: { id: 'meta', revision: 7, manifest: { catalogs: advertised } },
          search: [],
        };
      if (path.includes('/movie/0?')) return [];
      if (path.includes('/movie/1?')) throw new Error('Unavailable');
      return items;
    });
    const { harness } = await open();
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelectorAll('app-catalog-row')).toHaveLength(1);
    expect(harness.routeNativeElement!.querySelector('app-catalog-row h2')?.textContent).toBe(
      'Catalog 2 | KZ.MOVIE',
    );
  });

  it('restores row snapshots from history without fetching their pages', async () => {
    advertised = catalogs.slice(0, 1);
    const { harness, page } = await open();
    const row: CatalogRowComponent = harness.fixture.debugElement.query(
      By.directive(CatalogRowComponent),
    ).componentInstance;
    row.items.set(items);
    row.page = 2;
    row.fresh = true;
    row.exhausted = true;
    page.update(catalogs[0], row.snapshot());
    const state = structuredClone(TestBed.inject(Location).getState());
    expect(state).toMatchObject({
      discoveryRows: {
        sourceId: 'meta',
        sourceRevision: 7,
        rows: { 'movie:0': { page: 2, fresh: true, exhausted: true } },
      },
    });
    harness.fixture.destroy();
    api.get.mockClear();
    vi.spyOn(TestBed.inject(Location), 'getState').mockReturnValue(state);
    const restored = TestBed.createComponent(DiscoveryPage);
    restored.detectChanges();
    await restored.whenStable();
    restored.detectChanges();
    await restored.whenStable();
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('/addons');
    const restoredRow: CatalogRowComponent = restored.debugElement.query(
      By.directive(CatalogRowComponent),
    ).componentInstance;
    expect(restoredRow.snapshot()).toEqual({ items, page: 2, fresh: true, exhausted: true });
  });

  it('shows the search bar alone when no catalog is browsable', async () => {
    advertised = catalogs.map((catalog) => ({ ...catalog, browsable: false }));
    const { harness } = await open();
    expect(harness.routeNativeElement!.querySelector('app-search-bar')).not.toBeNull();
    expect(harness.routeNativeElement!.querySelector('app-catalog-row')).toBeNull();
    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('restores row options, positions and loaded cards after following a link and Back', async () => {
    TestBed.inject(Router).setUpLocationChangeListener();
    advertised = [
      {
        ...catalogs[0],
        extra: [{ name: 'genre', isRequired: false, options: ['Drama', 'Action'] }],
      },
    ];
    const { harness, page } = await open();
    const row: CatalogRowComponent = harness.fixture.debugElement.query(
      By.directive(CatalogRowComponent),
    ).componentInstance;
    row.selectedExtras['genre'] = 'Drama';
    await row.reload(false);
    row.page = 2;
    row.exhausted = true;
    page.update(advertised[0], row.snapshot());
    harness.detectChanges();
    const before = structuredClone(row.snapshot());
    (harness.routeNativeElement!.querySelector('a.media-card') as HTMLAnchorElement).click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/movie/tt0');
    api.get.mockClear();
    TestBed.inject(Location).back();
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/discovery'));
    await harness.fixture.whenStable();
    harness.detectChanges();
    const restored: CatalogRowComponent = harness.fixture.debugElement.query(
      By.directive(CatalogRowComponent),
    ).componentInstance;
    expect(restored.snapshot()).toEqual(before);
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/addons');
  });
});

describe('Media page navigation and releases', () => {
  const initial: Metadata = {
    identity: {
      sourceId: 'metadata',
      sourceRevision: 1,
      resourceType: 'series',
      contentId: 'opaque:series',
      mappedIds: { tvdb: '81189' },
    },
    title: 'Example series',
    year: 2024,
    description: 'Synopsis',
    posterUrl: 'https://image/poster',
    videos: [
      {
        id: 'ep7',
        season: 2,
        episode: 7,
        title: 'Seven',
        thumbnail: 'https://image/ep',
        overview: 'Episode overview',
        released: '2026-11-01T00:00:00Z',
      },
      { id: 'ep2', season: 1, episode: 2, title: 'Two' },
      { id: 'special', season: 0, episode: 1, title: 'Special' },
      { id: 'ep1', season: 1, episode: 1, title: 'One' },
    ],
  };
  let metadata: Metadata;
  let releaseCount: number;
  let metadataError: boolean;
  let finishReload: ((item: Metadata) => void) | null;
  let holdMeta: boolean;
  let finishMeta: ((item: Metadata) => void) | null;
  const api = {
    get: vi.fn(async (path: string): Promise<unknown> => {
      if (path === '/addons')
        return {
          metadata: {
            manifest: {
              catalogs: [{ id: 'series', type: 'series', name: 'Series', searchable: true }],
              resources: [{ name: 'meta', types: ['series'], idPrefixes: ['tvdb:'] }],
            },
          },
          search: [
            { id: 'disabled', name: 'Disabled', enabled: false, position: 0 },
            { id: 'stream', name: 'First', enabled: true, position: 1 },
            { id: 'other', name: 'Second', enabled: true, position: 2 },
          ],
        };
      if (path.startsWith('/discovery/meta?')) {
        if (metadataError) throw { error: { error: 'KZ.REQUEST_FAILED' } };
        if (holdMeta)
          return new Promise<Metadata>((resolve) => {
            finishMeta = resolve;
          });
        if (path.includes('forceRefresh=true'))
          return new Promise<Metadata>((resolve) => {
            finishReload = resolve;
          });
        return metadata;
      }
      return {
        organization: {
          categories: Object.fromEntries(
            ['MOVIE', 'SERIES', 'ANIME'].map((category) => [
              category,
              {
                destinations: [
                  { id: category + '-hdd', name: category + ' HDD', path: '/' + category },
                ],
                defaultDestinationId: category + '-hdd',
              },
            ]),
          ),
        },
      };
    }),
    post: vi.fn(async (path: string): Promise<unknown> => {
      if (path === '/discovery/search') return [metadata];
      if (path === '/downloads') return { id: 'accepted' };
      const query = new URLSearchParams(path.split('?')[1]);
      const page = Math.min(Number(query.get('page')), Math.max(1, Math.ceil(releaseCount / 20)));
      return {
        group: {
          addonId: query.get('addonId'),
          addonName: 'Source',
          outcome: 'SUCCESS',
          releases: Array.from({ length: releaseCount }, (_, index) => ({
            title: 'Release ' + index,
            cacheStatus: (['CACHED', 'UNCACHED', 'UNKNOWN'] as const)[index % 3],
            provenance: {
              addonId: query.get('addonId'),
              addonRevision: 1,
              addonName: 'Source',
              sourcePosition: 0,
              resultPosition: index,
            },
            reference: { type: 'PROVIDER_HTTP', value: 'https://addon/file' + index },
          })).slice((page - 1) * 20, page * 20),
        },
        page,
        pageSize: 20,
        total: releaseCount,
        hasNext: page * 20 < releaseCount,
      };
    }),
  };
  beforeEach(() => {
    vi.clearAllMocks();
    metadata = structuredClone(initial);
    releaseCount = 45;
    metadataError = false;
    finishReload = null;
    holdMeta = false;
    finishMeta = null;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = vi.fn();
        disconnect = vi.fn();
        unobserve = vi.fn();
      },
    );
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        {
          provide: AuthService,
          useValue: { initializeSession: () => of(true), currentOnboardingCompleted: () => true },
        },
        { provide: ApiAvailabilityService, useValue: {} },
        provideAppInitializer(() => {
          inject(NavigationHistory);
        }),
        provideLocationMocks(),
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
      ],
    });
    TestBed.inject(NavigationHistory);
    TestBed.inject(Router).setUpLocationChangeListener();
  });
  afterEach(() => vi.unstubAllGlobals());
  function panel(h: RouterTestingHarness): ReleasesPanelComponent {
    return h.fixture.debugElement.query(By.directive(ReleasesPanelComponent)).componentInstance;
  }
  async function settled(h: RouterTestingHarness) {
    for (let i = 0; i < 3; i++) {
      h.detectChanges();
      await h.fixture.whenStable();
    }
    await vi.waitFor(async () => {
      h.detectChanges();
      await h.fixture.whenStable();
      const releases = h.fixture.debugElement.query(By.directive(ReleasesPanelComponent));
      if (releases) expect(releases.componentInstance.busy()).toBe(false);
    });
    h.detectChanges();
  }
  it('opens cards at mapped routes and restores search on Back without a side panel', async () => {
    const h = await RouterTestingHarness.create();
    const discovery = await h.navigateByUrl('/search?q=Example&catalog=series:series', SearchPage);
    await settled(h);
    expect(discovery.results()).toHaveLength(1);
    expect(h.routeNativeElement!.querySelector('.details-panel')).toBeNull();
    (h.routeNativeElement!.querySelector('.media-card') as HTMLButtonElement).click();
    await settled(h);
    expect(TestBed.inject(Router).url).toBe('/show/tvdb:81189');
    (h.routeNativeElement!.querySelector('app-back-link a') as HTMLAnchorElement).click();
    await vi.waitFor(() =>
      expect(TestBed.inject(Router).url).toBe('/search?q=Example&catalog=series:series'),
    );
    await settled(h);
    expect((h.routeNativeElement!.querySelector('#query') as HTMLInputElement).value).toBe(
      'Example',
    );
    expect(h.routeNativeElement!.querySelector('.media-card')).not.toBeNull();
    await TestBed.inject(Router).navigateByUrl('/discovery');
    await settled(h);
    expect(h.routeNativeElement!.querySelector('.media-card')).toBeNull();
  });
  it('loads a movie and embeds releases without a find action', async () => {
    metadata.identity.resourceType = 'movie';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/movie/tt123', MoviePage);
    await settled(h);
    expect(api.get).toHaveBeenCalledWith('/discovery/meta?kind=movie&contentId=tt123');
    expect(h.routeNativeElement!.textContent).toContain('Synopsis');
    expect(h.routeNativeElement!.textContent).not.toContain('KZ.FIND_RELEASES');
    expect(panel(h).addonId).toBe('stream');
    expect(
      panel(h)
        .destinations()
        .map((group) => group.label),
    ).toEqual(['MOVIE']);
    expect(panel(h).destination?.id).toBe('MOVIE-hdd');
  });
  it('shows metadata errors and a Discovery link', async () => {
    metadataError = true;
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/movie/missing', MoviePage);
    await settled(h);
    expect(h.routeNativeElement!.querySelector('[role=alert]')).not.toBeNull();
    expect(h.routeNativeElement!.querySelector('a[href="/discovery"]')).not.toBeNull();
    expect(h.routeNativeElement!.querySelector('app-releases-panel')).toBeNull();
  });
  it.each(['show', 'anime'])('loads %s and redirects season-only URLs', async (prefix) => {
    metadata.category = prefix === 'anime' ? 'ANIME' : 'SERIES';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/' + prefix + '/id/2', ShowShell);
    await settled(h);
    expect(TestBed.inject(Router).url).toBe('/' + prefix + '/id?season=2');
    expect(api.get).toHaveBeenCalledWith('/discovery/meta?kind=show&contentId=id');
    expect(h.fixture.debugElement.query(By.directive(ShowPage)).componentInstance.expanded()).toBe(
      2,
    );
  });
  it('corrects the prefix from the classification, not the addon type', async () => {
    // The addon calls it `series`; Kitsu classified it as anime.
    metadata.category = 'ANIME';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7?source=other&page=2');
    await settled(h);
    expect(TestBed.inject(Router).url).toBe('/anime/id/2/7?source=other&page=2');
    const episode: EpisodePage = h.fixture.debugElement.query(
      By.directive(EpisodePage),
    ).componentInstance;
    expect(episode.parent()).toEqual(['/anime', 'id']);
    expect(panel(h).content().identity.resourceType).toBe('series');
    expect(panel(h).content().category).toBe('ANIME');
    expect(panel(h).destination?.id).toBe('ANIME-hdd');
    // Same route for both prefixes: the shell survives the correction without a second request.
    expect(api.get.mock.calls.filter(([path]) => path.startsWith('/discovery/meta?'))).toHaveLength(
      1,
    );
  });
  it('shows the card header immediately while the show loads', async () => {
    holdMeta = true;
    const h = await RouterTestingHarness.create();
    await TestBed.inject(Router).navigate(['/show', 'id'], {
      state: { mediaPreview: { ...metadata, title: 'From the card', videos: [] } },
    });
    h.detectChanges();
    expect(h.routeNativeElement!.querySelector('app-media-hero')?.textContent).toContain(
      'From the card',
    );
    expect(h.routeNativeElement!.querySelector('[role=status]')).not.toBeNull();
    finishMeta!(metadata);
    await settled(h);
    expect(h.routeNativeElement!.querySelector('app-media-hero')?.textContent).toContain(
      'Example series',
    );
    expect(h.routeNativeElement!.textContent).toContain('KZ.SPECIALS');
  });
  it('shows the card header immediately while the movie loads', async () => {
    holdMeta = true;
    metadata.identity.resourceType = 'movie';
    const h = await RouterTestingHarness.create();
    await TestBed.inject(Router).navigate(['/movie', 'tt123'], {
      state: { mediaPreview: { ...metadata, title: 'Movie card' } },
    });
    h.detectChanges();
    expect(h.routeNativeElement!.querySelector('app-media-hero')?.textContent).toContain(
      'Movie card',
    );
    expect(h.routeNativeElement!.querySelector('app-releases-panel')).toBeNull();
    finishMeta!(metadata);
    await settled(h);
    expect(h.routeNativeElement!.querySelector('app-releases-panel')).not.toBeNull();
  });
  it('passes the card data through a search result link', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/search?q=Example&catalog=series:series', SearchPage);
    await settled(h);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    (h.routeNativeElement!.querySelector('a.media-card') as HTMLAnchorElement).click();
    await settled(h);
    expect(navigate).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        state: { mediaPreview: metadata },
      }),
    );
  });
  it('keeps an addon-typed anime on /show when classified as series', async () => {
    metadata.identity.resourceType = 'anime';
    metadata.category = 'SERIES';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/anime/id', ShowShell);
    await settled(h);
    expect(TestBed.inject(Router).url).toBe('/show/id');
  });
  it('shows season posters only for seasons that have one', async () => {
    metadata.seasonPosters = { '1': 'https://image/s1', '2': 'https://image/s2' };
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id', ShowShell);
    await settled(h);
    const headers = [...h.routeNativeElement!.querySelectorAll('.season-header')];
    expect(
      headers.map((header) => header.querySelector('img')?.getAttribute('src') ?? null),
    ).toEqual([null, 'https://image/s1', 'https://image/s2']);
  });
  it('sorts seasons and episodes, labels Specials and lazily expands with URL sync', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id', ShowShell);
    await settled(h);
    const show: ShowPage = h.fixture.debugElement.query(By.directive(ShowPage)).componentInstance;
    expect(show.seasons()).toEqual([0, 1, 2]);
    expect(show.episodes(1).map((v) => v.episode)).toEqual([1, 2]);
    expect(h.routeNativeElement!.textContent).toContain('KZ.SPECIALS');
    expect(h.routeNativeElement!.querySelectorAll('.episode')).toHaveLength(0);
    show.expand(1);
    await settled(h);
    expect(TestBed.inject(Router).url).toBe('/show/id?season=1');
    expect(h.routeNativeElement!.querySelectorAll('.episode')).toHaveLength(2);
    expect(TestBed.inject(NavigationHistory).hasPrevious()).toBe(false);
  });
  it('shows empty episodes', async () => {
    metadata.videos = [];
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id', ShowShell);
    await settled(h);
    expect(h.routeNativeElement!.textContent).toContain('KZ.NO_EPISODES');
  });
  it('refreshes seasons and episodes and keeps old data on failure', async () => {
    const h = await RouterTestingHarness.create();
    const shell = await h.navigateByUrl('/show/id?season=2', ShowShell);
    await settled(h);
    const show: ShowPage = h.fixture.debugElement.query(By.directive(ShowPage)).componentInstance;
    const pending = shell.reload(2);
    h.detectChanges();
    expect(shell.reloading()).toBe(2);
    const added = structuredClone(metadata);
    added.videos.push(
      { id: 'new', season: 2, episode: 8 },
      { id: 'new-season', season: 3, episode: 1 },
    );
    finishReload!(added);
    await pending;
    await settled(h);
    expect(show.seasons()).toEqual([0, 1, 2, 3]);
    expect(show.episodes(2)).toHaveLength(2);
    expect(show.expanded()).toBe(2);
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/meta?kind=show&contentId=id&forceRefresh=true',
    );
    const headerReload = shell.reload();
    expect(shell.reloading()).toBe('seasons');
    finishReload!(added);
    await headerReload;
    metadataError = true;
    await shell.reload(2);
    expect(shell.metadata()).toEqual(added);
    expect(shell.error()).toBe('KZ.REQUEST_FAILED');
    expect(shell.reloading()).toBeNull();
  });
  it('loads episode details, shares metadata between children and falls back to its season', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7', ShowShell);
    await settled(h);
    expect(h.routeNativeElement!.textContent).toContain('Episode overview');
    expect(h.routeNativeElement!.querySelector('app-media-hero img')?.getAttribute('src')).toBe(
      'https://image/ep',
    );
    expect(panel(h).content().identity.videoId).toBe('ep7');
    const episode: EpisodePage = h.fixture.debugElement.query(
      By.directive(EpisodePage),
    ).componentInstance;
    expect(episode.parent()).toEqual(['/show', 'id']);
    (h.routeNativeElement!.querySelector('app-back-link a') as HTMLAnchorElement).click();
    await settled(h);
    expect(TestBed.inject(Router).url).toBe('/show/id?season=2');
    expect(api.get.mock.calls.filter(([path]) => path.startsWith('/discovery/meta?'))).toHaveLength(
      1,
    );
  });
  it('shows the title rating with its source and external links on movie pages', async () => {
    metadata.identity.resourceType = 'movie';
    metadata.identity.mappedIds = { imdb: 'tt0133093', tmdb: '603', tvdb: '169' };
    metadata.rating = '8.7';
    metadata.ratingSource = 'IMDB';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/movie/tt0133093', MoviePage);
    await settled(h);
    const hero = h.routeNativeElement!.querySelector('app-media-hero')!;
    expect(hero.querySelector('.rating .score')?.textContent).toContain('8.7');
    expect(hero.querySelector('.rating .source')?.textContent?.trim()).toBe('IMDb');
    expect(
      [...hero.querySelectorAll<HTMLAnchorElement>('app-external-links a')].map((a) => a.href),
    ).toEqual([
      'https://www.imdb.com/title/tt0133093',
      'https://www.themoviedb.org/movie/603',
      'https://thetvdb.com/dereferrer/movie/169',
    ]);
  });
  it('shows series external links and an unsourced rating on show pages', async () => {
    metadata.identity.mappedIds = { tvdb: '81189', tmdb: '1396' };
    metadata.rating = '9.5';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id', ShowShell);
    await settled(h);
    const hero = h.routeNativeElement!.querySelector('app-media-hero')!;
    expect(hero.querySelector('.rating .source')?.textContent?.trim()).toBe('KZ.RATING_NO_SOURCE');
    expect(
      [...hero.querySelectorAll<HTMLAnchorElement>('app-external-links a')].map((a) => a.href),
    ).toEqual([
      'https://www.themoviedb.org/tv/1396',
      'https://thetvdb.com/dereferrer/series/81189',
    ]);
  });
  it('shows episode ratings without a source and never shows external links on episodes', async () => {
    metadata.identity.mappedIds = { tvdb: '81189', tmdb: '1396' };
    metadata.rating = '9.5';
    metadata.ratingSource = 'IMDB';
    metadata.videos[0].rating = '7.7';
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7', ShowShell);
    await settled(h);
    const page = h.routeNativeElement!;
    expect(page.querySelector('.rating .score')?.textContent).toContain('7.7');
    expect(page.querySelector('.rating .source')?.textContent?.trim()).toBe('KZ.RATING_NO_SOURCE');
    expect(page.querySelector('app-external-links')).toBeNull();
    await h.navigateByUrl('/show/id/1/1');
    await settled(h);
    expect(h.routeNativeElement!.querySelector('.rating')).toBeNull();
    expect(h.routeNativeElement!.querySelector('app-external-links')).toBeNull();
  });
  it('shows an error and show link for a missing episode', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/9/99', ShowShell);
    await settled(h);
    expect(h.routeNativeElement!.textContent).toContain('KZ.EPISODE_NOT_FOUND');
    expect(h.routeNativeElement!.querySelector('a[href="/show/id?season=9"]')).not.toBeNull();
    expect(h.routeNativeElement!.querySelector('app-releases-panel')).toBeNull();
  });
  it('switches source without forced refresh, merges params, pages and clamps reload', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7?page=2&keep=yes', ShowShell);
    await settled(h);
    const releases = panel(h);
    expect(releases.result()?.page).toBe(2);
    expect(releases.groups()[0].releases[0].provenance.resultPosition).toBe(20);
    const tabs = [...h.routeNativeElement!.querySelectorAll<HTMLElement>('[role="tab"]')];
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual(['First', 'Second']);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    tabs[1].click();
    await settled(h);
    await vi.waitFor(() => expect(releases.result()?.page).toBe(1));
    h.detectChanges();
    expect(tabs[1].getAttribute('aria-selected')).toBe('true');
    expect(TestBed.inject(Router).url).toBe('/show/id/2/7?keep=yes&source=other');
    expect(releases.result()?.page).toBe(1);
    expect(api.post).toHaveBeenLastCalledWith(
      '/discovery/releases/page?addonId=other&page=1',
      expect.anything(),
    );
    await releases.changePage(3);
    await settled(h);
    await vi.waitFor(() => expect(releases.result()?.page).toBe(3));
    expect(releases.groups()[0].releases).toHaveLength(5);
    releaseCount = 5;
    await releases.findReleases();
    await settled(h);
    expect(releases.result()?.page).toBe(1);
    expect(TestBed.inject(Router).url).not.toContain('page=');
    expect(api.post.mock.calls.some(([path]) => path.includes('forceRefresh=true'))).toBe(true);
  });
  it.each(['disabled', 'unknown'])('rejects unavailable source %s', async (source) => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7?source=' + source, ShowShell);
    await settled(h);
    expect(panel(h).error()).toBe('KZ.RELEASE_SOURCE_UNAVAILABLE');
    expect(api.post).not.toHaveBeenCalled();
  });
  it('downloads a series episode to Anime without changing releases', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7', ShowShell);
    await settled(h);
    const releases = panel(h);
    expect(releases.destination?.id).toBe('SERIES-hdd');
    expect(releases.destinations().map((group) => group.label)).toEqual(['SERIES', 'ANIME']);
    const list = releases.result(),
      release = releases.groups()[0].releases[0];
    releases.selectRelease(release);
    releases.destination = releases.destinations()[1].items[0];
    expect(releases.result()).toBe(list);
    expect(releases.release).toBe(release);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await releases.submit();
    expect(api.post).toHaveBeenLastCalledWith('/downloads', {
      content: { ...releases.content(), category: 'ANIME' },
      release,
      destinationId: 'ANIME-hdd',
    });
    expect(
      api.post.mock.calls.filter(([path]) => path.startsWith('/discovery/releases/page')),
    ).toHaveLength(1);
    expect(navigate).toHaveBeenCalledWith(['/downloads']);
  });
  it('keeps double-click submission single and renders cache availability', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id/2/7', ShowShell);
    await settled(h);
    const releases = panel(h);
    expect(
      [...h.routeNativeElement!.querySelectorAll('.cache-badge')]
        .slice(0, 3)
        .map((n) => n.textContent?.trim()),
    ).toEqual(['KZ.DEBRID_CACHE_CACHED', 'KZ.DEBRID_CACHE_UNCACHED', 'KZ.DEBRID_CACHE_UNKNOWN']);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    let finish!: () => void;
    api.post.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({ id: 'accepted' });
        }),
    );
    const release = releases.groups()[0].releases[0],
      pending = releases.downloadRelease(release);
    await releases.downloadRelease(release);
    expect(api.post.mock.calls.filter(([path]) => path === '/downloads')).toHaveLength(1);
    finish();
    await pending;
  });
  it('uses browser history after in-app navigation', async () => {
    const h = await RouterTestingHarness.create();
    await h.navigateByUrl('/show/id?season=2', ShowShell);
    await settled(h);
    await TestBed.inject(Router).navigateByUrl('/show/id/2/7');
    await settled(h);
    const back = vi.spyOn(TestBed.inject(Location), 'back');
    (h.routeNativeElement!.querySelector('app-back-link a') as HTMLAnchorElement).click();
    expect(back).toHaveBeenCalledOnce();
  });
});
