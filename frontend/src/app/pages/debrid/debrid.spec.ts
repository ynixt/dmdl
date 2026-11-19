import { of } from 'rxjs';
import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { DebridApi, Metadata, ReleaseGroup } from '../../services/debrid-api.service';
import { DiscoveryPage } from './discovery.page';
import { SettingsPage } from './settings.page';
import { AppConfirmationService } from '../../core/ui/confirmation.service';
import { ConfirmationService } from '@openng/optimus-ui/api';

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
        };
      if (path === '/settings/stream-badges/defaults') return { streamBadgeJson: '{"imports":[]}' };
      if (path === '/webhooks') return [];
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
        ConfirmationService,
        { provide: DebridApi, useValue: api },
        { provide: AppConfirmationService, useValue: { confirm: vi.fn() } },
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });
  afterEach(() => vi.unstubAllGlobals());
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
    fixture.componentInstance.selectTab('storage');
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
});
