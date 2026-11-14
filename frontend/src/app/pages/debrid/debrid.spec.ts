import { of } from 'rxjs';
import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { DebridApi, Metadata, ReleaseGroup } from '../../services/debrid-api.service';
import { DiscoveryPage } from './discovery.page';
import { ReleasesPage } from './releases.page';
import { SettingsPage } from './settings.page';

describe('Manual discovery journey', () => {
  const source = '00000000-0000-0000-0000-000000000001';
  const details: Metadata = {
    identity: {
      sourceId: source,
      sourceRevision: 1,
      resourceType: 'series',
      contentId: 'kitsu:12',
    },
    title: 'Example',
    videos: [{ id: 'kitsu:12:special', season: 0, episode: 1, title: 'Special' }],
  };
  const groups: ReleaseGroup[] = [
    {
      addonId: source,
      addonName: 'First source',
      position: 0,
      outcome: 'SUCCESS',
      releases: [
        {
          title: 'B',
          provenance: {
            addonId: source,
            addonRevision: 1,
            addonName: 'First source',
            sourcePosition: 0,
            resultPosition: 0,
          },
          reference: { type: 'INFO_HASH', value: 'b'.repeat(40) },
        },
        {
          title: 'A',
          provenance: {
            addonId: source,
            addonRevision: 1,
            addonName: 'First source',
            sourcePosition: 0,
            resultPosition: 1,
          },
          reference: { type: 'INFO_HASH', value: 'a'.repeat(40) },
        },
      ],
    },
    {
      addonId: 'second',
      addonName: 'Second source',
      position: 1,
      outcome: 'ERROR',
      releases: [],
      message: 'Source unavailable',
    },
  ];
  const api = {
    get: async (path: string) => {
      if (path === '/settings')
        return {
          locale: 'en-US',
          storageRoot: '/test-media',
          maxConcurrentDownloads: 2,
          organization: {
            revision: 1,
            categories: {
              MOVIE: {
                destinations: [{ id: 'MOVIE-hdd', name: 'HDD', path: '/test-media/movies' }],
                defaultDestinationId: 'MOVIE-hdd',
                folderTemplate: '{title}',
              },
              SERIES: {
                destinations: [{ id: 'SERIES-hdd', name: 'HDD', path: '/test-media/series' }],
                defaultDestinationId: 'SERIES-hdd',
                folderTemplate: '{title}',
              },
              ANIME: {
                destinations: [{ id: 'ANIME-hdd', name: 'HDD', path: '/test-media/anime' }],
                defaultDestinationId: 'ANIME-hdd',
                folderTemplate: '{title}',
              },
            },
          },
          transfer: {
            split: 4,
            maxConnectionPerServer: 2,
            minSplitSizeMib: 16,
            maxTries: 5,
            retryWaitSeconds: 3,
            fileAllocation: 'PREALLOC',
            ipv6Enabled: true,
          },
          jellyfin: { enabled: false, tokenConfigured: false },
        };
      if (path.startsWith('/discovery/content?'))
        return {
          identity: { ...details.identity, videoId: details.videos[0].id },
          title: details.title,
          category: 'SERIES',
          season: 0,
          episode: 1,
        };
      if (path === '/settings/stream-badges/defaults') return { streamBadgeJson: '{"imports":[]}' };
      if (path === '/setup') return { missing: [], ready: true, completed: false };
      return {
        metadata: {
          manifest: {
            catalogs: [{ id: 'custom', name: 'Anime', type: 'series', searchable: true }],
          },
        },
        search: [{ id: source, name: 'First source', enabled: true }],
      };
    },
    post: vi.fn(async (path: string): Promise<unknown> => {
      if (path === '/discovery/details') return details;
      if (path.startsWith('/discovery/releases/page?'))
        return { group: groups[0], page: 1, pageSize: 20, total: 2, hasNext: false };
      return groups;
    }),
  };
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe = vi.fn();
        unobserve = vi.fn();
        disconnect = vi.fn();
      },
    );
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideLocationMocks(),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(
              convertToParamMap({
                addonId: source,
                type: 'series',
                id: 'kitsu:12',
                season: '0',
                episode: '1',
              }),
            ),
            queryParamMap: of(convertToParamMap({})),
            snapshot: {
              paramMap: convertToParamMap({
                addonId: source,
                type: 'series',
                id: 'kitsu:12',
                season: '0',
                episode: '1',
              }),
              queryParams: {},
              queryParamMap: convertToParamMap({}),
            },
          },
        },
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });
  afterEach(() => vi.unstubAllGlobals());
  it('requires one explicit episode and routes the selected opaque identity to releases', async () => {
    const page = TestBed.createComponent(DiscoveryPage).componentInstance;
    await page.ngOnInit();
    await page.open(details, false);
    expect(page.content()).toBeNull();
    page.video = details.videos[0];
    page.category = 'ANIME';
    await page.findReleases();
    expect(page.content()?.identity.videoId).toBe('kitsu:12:special');
    expect(page.content()?.season).toBe(0);
    expect(TestBed.inject(Router).navigate).toHaveBeenLastCalledWith(
      ['/discovery/releases', source, 'series', 'kitsu:12', 0, 1],
      {
        queryParams: { category: 'ANIME' },
        state: { discovery: expect.objectContaining({ query: '', category: 'ANIME' }) },
      },
    );
    expect(api.post).not.toHaveBeenCalledWith('/discovery/releases', expect.anything());
  });
  it('keeps an explicit series category for titles from anime metadata resources', async () => {
    const page = TestBed.createComponent(DiscoveryPage).componentInstance;
    await page.ngOnInit();
    page.details.set({ ...details, identity: { ...details.identity, resourceType: 'anime' } });
    page.video = details.videos[0];
    page.category = 'SERIES';
    await page.findReleases();
    expect(TestBed.inject(Router).navigate).toHaveBeenLastCalledWith(
      ['/discovery/releases', source, 'anime', 'kitsu:12', 0, 1],
      expect.objectContaining({ queryParams: { category: 'SERIES' } }),
    );
  });
  it('restores a compact release URL and retries its selected source on reload', async () => {
    const page = TestBed.createComponent(ReleasesPage).componentInstance;
    await page.ngOnInit();
    expect(page.content()?.identity.videoId).toBe('kitsu:12:special');
    expect(page.groups().map((group) => group.addonName)).toEqual(['First source']);
    expect(page.groups()[0].releases.map((release) => release.title)).toEqual(['B', 'A']);
    page.selectRelease(groups[0].releases[0]);
    await page.findReleases();
    expect(api.post).toHaveBeenLastCalledWith(
      '/discovery/releases/page?addonId=' + source + '&page=1&forceRefresh=true',
      page.content(),
    );
    expect(page.release).toBeNull();
  });
  it('downloads HTTP results with optional service metadata', async () => {
    vi.spyOn(api, 'get');
    const page = TestBed.createComponent(ReleasesPage).componentInstance;
    await page.ngOnInit();
    const release = {
      ...groups[0].releases[0],
      reference: { type: 'PROVIDER_HTTP', value: 'https://addon.example/file.mkv' },
      provenance: { ...groups[0].releases[0].provenance, boundProvider: undefined },
    };
    page.selectRelease(release);
    await page.submit();
    expect(api.post).toHaveBeenCalledWith('/downloads', {
      content: page.content(),
      release,
      destinationId: page.destinationId,
    });
    expect(api.get).not.toHaveBeenCalledWith('/providers');
  });

  it('renders asynchronous discovery configuration without user interaction', async () => {
    const fixture = TestBed.createComponent(DiscoveryPage);
    fixture.detectChanges();
    await fixture.whenStable();
    await vi.waitFor(() => expect(fixture.nativeElement.textContent).toContain('Anime'));
  });
  it('renders asynchronous settings and organization fields without user interaction', async () => {
    const fixture = TestBed.createComponent(SettingsPage);
    fixture.detectChanges();
    await fixture.whenStable();
    await vi.waitFor(() =>
      expect(fixture.nativeElement.querySelector('.destination-row input')?.value).toBe('HDD'),
    );
    expect(fixture.nativeElement.querySelector('[id^=storage-path-]')?.value).toBe(
      '/test-media/movies',
    );
    expect(fixture.nativeElement.querySelector('#transfer-lowest-speed-enabled')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#transfer-lowest-speed')).toBeNull();
  });
  it('preselects the category default and submits an explicit SSD override', async () => {
    const page = TestBed.createComponent(ReleasesPage).componentInstance;
    await page.ngOnInit();
    expect(page.destinationId).toBe('SERIES-hdd');
    page.organization!.categories.SERIES.destinations.push({
      id: 'SERIES-ssd',
      name: 'SSD',
      path: '/ssd/series',
    });
    page.destinationId = 'SERIES-ssd';
    page.release = groups[0].releases[0];
    await page.submit();
    expect(api.post).toHaveBeenCalledWith(
      '/downloads',
      expect.objectContaining({ destinationId: 'SERIES-ssd' }),
    );
    expect(page.organization!.categories.SERIES.defaultDestinationId).toBe('SERIES-hdd');
  });
});
