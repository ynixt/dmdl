import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { DebridApi, Metadata } from '../../services/debrid-api.service';
import { DiscoveryPage } from './discovery.page';
import { ReleasesPage } from './releases.page';
import { onboardingGuard } from '../../core/auth/onboarding.guard';
import { AuthService } from '../../core/auth/auth.service';

describe('Compact discovery navigation and release pages', () => {
  const metadata: Metadata = {
    identity: {
      sourceId: 'metadata',
      sourceRevision: 1,
      resourceType: 'series',
      contentId: 'opaque:series',
      mappedIds: { tvdb: '81189' },
    },
    title: 'Example series',
    year: 2024,
    videos: [{ id: 'opaque:s2e7', season: 2, episode: 7, title: 'Episode seven' }],
  };
  let releaseCount = 45;
  const layoutObservers: { element: Element; measure: () => void; disconnect: () => void }[] = [];
  const api = {
    get: vi.fn(async (path: string) => {
      if (path === '/addons')
        return {
          metadata: {
            manifest: {
              catalogs: [{ id: 'series', type: 'series', name: 'Series', searchable: true }],
              resources: [{ name: 'meta', types: ['series'], idPrefixes: ['tvdb:', 'opaque:'] }],
            },
          },
          search: [
            { id: 'stream', name: 'First source', enabled: true },
            { id: 'other', name: 'Second source', enabled: true },
          ],
        };
      if (path.startsWith('/discovery/content?')) {
        const query = new URLSearchParams(path.split('?')[1]);
        return {
          identity: { ...metadata.identity, videoId: metadata.videos[0].id },
          title: metadata.title,
          category: query.get('category') ?? 'SERIES',
          season: 2,
          episode: 7,
        };
      }
      return {
        organization: {
          categories: {
            SERIES: { destinations: [], defaultDestinationId: null },
            ANIME: { destinations: [], defaultDestinationId: null },
          },
        },
      };
    }),
    post: vi.fn(async (path: string) => {
      if (path === '/discovery/search') return [metadata];
      if (path === '/discovery/details') return metadata;
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
            provenance: {
              addonId: query.get('addonId'),
              addonRevision: 1,
              addonName: 'Source',
              sourcePosition: 0,
              resultPosition: index,
            },
            reference: { type: 'INFO_HASH', value: index.toString(16).padStart(40, '0') },
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
    layoutObservers.length = 0;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        disconnect = vi.fn();
        unobserve = vi.fn();
        constructor(private readonly callback: ResizeObserverCallback) {}
        observe(element: Element): void {
          if (
            element.classList.contains('media-card') ||
            element.classList.contains('download-bar')
          ) {
            layoutObservers.push({
              element,
              measure: () => this.callback([], this),
              disconnect: this.disconnect,
            });
          }
        }
      },
    );
    releaseCount = 45;
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'discovery/releases/:addonId/:type/:id/:season/:episode',
            component: ReleasesPage,
            canActivate: [onboardingGuard],
          },
          { path: 'discovery/releases/:addonId/:type/:id', component: ReleasesPage },
          { path: 'discovery', component: DiscoveryPage },
        ]),
        provideLocationMocks(),
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
        { provide: AuthService, useValue: { currentOnboardingCompleted: () => true } },
      ],
    });
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('keeps only searched text in discovery and restores selection through browser Back', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(Router).setUpLocationChangeListener();
    const page = await harness.navigateByUrl('/discovery', DiscoveryPage);
    await harness.fixture.whenStable();
    page.query = 'Example';
    await page.search();
    await page.open(metadata);
    await page.selectEpisode(metadata.videos[0]);
    page.category = 'ANIME';
    await page.changeCategory();
    expect(TestBed.inject(Router).url).toBe('/discovery?q=Example');
    await page.findReleases();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe(
      '/discovery/releases/stream/series/tvdb:81189/2/7?category=ANIME',
    );
    const releases = harness.routeDebugElement!.componentInstance as ReleasesPage;
    await vi.waitFor(() => expect(releases.result()?.page).toBe(1));
    expect(releases.content()?.identity.videoId).toBe('opaque:s2e7');
    expect(releases.content()?.category).toBe('ANIME');
    TestBed.inject(Location).back();
    await harness.fixture.whenStable();
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/discovery?q=Example'));
    const restored = harness.routeDebugElement!.componentInstance as DiscoveryPage;
    await vi.waitFor(() => expect(restored.video?.id).toBe('opaque:s2e7'));
    expect(restored.query).toBe('Example');
    expect(restored.category).toBe('ANIME');
  });

  it('clears results details episodes and the query when the logo navigates to discovery', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/discovery?q=Example', DiscoveryPage);
    await vi.waitFor(() => expect(page.results()).toHaveLength(1));
    await page.open(metadata);
    await page.selectEpisode(metadata.videos[0]);
    page.category = 'ANIME';
    await TestBed.inject(Router).navigateByUrl('/discovery');
    await harness.fixture.whenStable();
    expect(harness.routeDebugElement!.componentInstance).toBe(page);
    expect(page.query).toBe('');
    expect(page.results()).toEqual([]);
    expect(page.details()).toBeNull();
    expect(page.video).toBeNull();
    expect(page.category).toBe('MOVIE');
    expect(page.busy()).toBe(false);
    expect(harness.routeNativeElement!.querySelectorAll('.media-card')).toHaveLength(0);
    expect((harness.routeNativeElement!.querySelector('#query') as HTMLInputElement).value).toBe(
      '',
    );
    page.query = 'Unsubmitted text';
    await TestBed.inject(Router).navigateByUrl('/discovery');
    await harness.fixture.whenStable();
    expect(page.query).toBe('');
  });

  it('discards a pending metadata response after returning to the empty discovery URL', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/discovery?q=Example', DiscoveryPage);
    await vi.waitFor(() => expect(page.results()).toHaveLength(1));
    await harness.fixture.whenStable();
    let finish!: (metadata: Metadata) => void;
    api.post.mockImplementationOnce(
      () =>
        new Promise<Metadata>((resolve) => {
          finish = resolve;
        }),
    );
    const opening = page.open(metadata);
    await TestBed.inject(Router).navigateByUrl('/discovery');
    finish(metadata);
    await opening;
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/discovery');
    expect(page.results()).toEqual([]);
    expect(page.details()).toBeNull();
    expect(page.query).toBe('');
    expect(page.busy()).toBe(false);
  });

  it('loads a compact URL directly and renders 20 releases per page with their original positions', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl(
      '/discovery/releases/stream/series/tvdb:81189/2/7',
      ReleasesPage,
    );
    await vi.waitFor(() => expect(page.result()?.page).toBe(1));
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement!.querySelectorAll('.release')).toHaveLength(20);
    expect(api.get).toHaveBeenCalledWith(
      '/discovery/content?resourceType=series&contentId=tvdb%3A81189&season=2&episode=7',
    );
    page.selectRelease(page.groups()[0].releases[0]);
    await page.changePage(2);
    await vi.waitFor(() => expect(page.result()?.page).toBe(2));
    expect(page.groups()[0].releases[0].provenance.resultPosition).toBe(20);
    expect(page.release).toBeNull();
    await page.changePage(3);
    await vi.waitFor(() => expect(page.result()?.page).toBe(3));
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement!.querySelectorAll('.release')).toHaveLength(5);
    releaseCount = 5;
    await page.findReleases();
    await vi.waitFor(() => expect(page.result()?.page).toBe(1));
    expect(TestBed.inject(Router).url).not.toContain('page=');
  });

  it('changes the search addon in the URL and resets its page', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl(
      '/discovery/releases/stream/series/tvdb:81189/2/7?page=2',
      ReleasesPage,
    );
    await vi.waitFor(() => expect(page.result()?.page).toBe(2));
    await page.selectSource('other');
    await vi.waitFor(() => expect(page.result()?.group.addonId).toBe('other'));
    expect(page.result()?.page).toBe(1);
    expect(TestBed.inject(Router).url).toBe('/discovery/releases/other/series/tvdb:81189/2/7');
    expect(api.post).toHaveBeenLastCalledWith(
      '/discovery/releases/page?addonId=other&page=1&forceRefresh=true',
      expect.anything(),
    );
  });

  it.each([false, true])(
    'submits the double-clicked release once with initial selection %s',
    async (initiallySelected) => {
      const harness = await RouterTestingHarness.create();
      const page = await harness.navigateByUrl(
        '/discovery/releases/stream/series/tvdb:81189/2/7',
        ReleasesPage,
      );
      await vi.waitFor(() => expect(page.result()).not.toBeNull());
      await harness.fixture.whenStable();
      page.destinationId = 'ssd';
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      let finish!: (value: unknown) => void;
      api.post.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }) as never,
      );
      const card = harness.routeNativeElement!.querySelector('.release') as HTMLButtonElement;
      if (initiallySelected) {
        card.click();
        await harness.fixture.whenStable();
      }
      card.click();
      card.click();
      card.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      card.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await vi.waitFor(() => expect(finish).toBeDefined());
      expect(api.post.mock.calls.filter(([path]) => path === '/downloads')).toHaveLength(1);
      expect(api.post).toHaveBeenLastCalledWith('/downloads', {
        content: page.content(),
        release: page.groups()[0].releases[0],
        destinationId: 'ssd',
      });
      expect(page.release).toBe(page.groups()[0].releases[0]);
      finish({ id: 'accepted' });
      await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/downloads']));
    },
  );

  it('keeps double-click validation in the release page when the destination is missing', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl(
      '/discovery/releases/stream/series/tvdb:81189/2/7',
      ReleasesPage,
    );
    await vi.waitFor(() => expect(page.result()).not.toBeNull());
    await harness.fixture.whenStable();
    (harness.routeNativeElement!.querySelector('.release') as HTMLButtonElement).dispatchEvent(
      new MouseEvent('dblclick', { bubbles: true }),
    );
    await harness.fixture.whenStable();
    expect(page.release).toBe(page.groups()[0].releases[0]);
    expect(harness.routeNativeElement!.querySelector('.download-bar')).not.toBeNull();
    expect(api.post.mock.calls.some(([path]) => path === '/downloads')).toBe(false);
  });

  it('navigates to releases and renders a spinner before the pending source returns', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/discovery?q=Example', DiscoveryPage);
    await vi.waitFor(() => expect(page.results()).toHaveLength(1));
    await page.open(metadata);
    await page.selectEpisode(metadata.videos[0]);
    let finish!: (result: unknown) => void;
    api.post.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }) as never,
    );
    await page.findReleases();
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toContain('/discovery/releases/'));
    harness.detectChanges();
    const releases = harness.routeDebugElement!.componentInstance as ReleasesPage;
    await vi.waitFor(() => expect(finish).toBeDefined());
    expect(releases.busy()).toBe(true);
    expect(
      harness.routeNativeElement!.querySelector('.release-loading p-progress-spinner'),
    ).not.toBeNull();
    expect(api.get).not.toHaveBeenCalledWith('/setup');
    finish({ group: { releases: [], outcome: 'EMPTY' }, page: 1, pageSize: 20, total: 0 });
    await vi.waitFor(() => expect(releases.busy()).toBe(false));
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('.release-loading')).toBeNull();
  });

  it('renders distinct cached uncached and unknown availability labels', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl(
      '/discovery/releases/stream/series/tvdb:81189/2/7',
      ReleasesPage,
    );
    await vi.waitFor(() => expect(page.result()).not.toBeNull());
    page.result.update((result) => ({
      ...result!,
      group: {
        ...result!.group,
        releases: result!.group.releases.slice(0, 3).map((release, index) => ({
          ...release,
          cacheStatus: (['CACHED', 'UNCACHED', 'UNKNOWN'] as const)[index],
        })),
      },
    }));
    harness.detectChanges();
    const labels = [...harness.routeNativeElement!.querySelectorAll('.cache-badge')].map((badge) =>
      badge.textContent?.trim(),
    );
    expect(labels).toEqual([
      'KZ.DEBRID_CACHE_CACHED',
      'KZ.DEBRID_CACHE_UNCACHED',
      'KZ.DEBRID_CACHE_UNKNOWN',
    ]);
    expect(harness.routeNativeElement!.querySelector('.cached .pi-bolt')).not.toBeNull();
  });
});
