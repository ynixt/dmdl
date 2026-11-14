import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { Catalog, DebridApi, Metadata } from '../../services/debrid-api.service';
import { SearchPage } from './search.page';

describe('SearchPage', () => {
  const movie: Catalog = {
    id: 'search:movies',
    type: 'movie',
    name: 'Movies',
    searchable: true,
    browsable: false,
    pageable: false,
  };
  const series: Catalog = { ...movie, id: 'series', type: 'series', name: 'Series' };
  const item: Metadata = {
    identity: { sourceId: 'meta', sourceRevision: 1, resourceType: 'movie', contentId: 'tt1' },
    title: 'Dune',
    videos: [],
  };
  const api = {
    get: vi.fn(async () => ({ metadata: { manifest: { catalogs: [movie, series] } }, search: [] })),
    post: vi.fn(async (): Promise<Metadata[]> => [item]),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.post.mockResolvedValue([item]);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'search', component: SearchPage }]),
        provideLocationMocks(),
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
      ],
    });
  });

  async function open(url: string) {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl(url, SearchPage);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return { harness, page };
  }

  it('shows card skeletons for a pending search and preserves the result count during reload', async () => {
    let finish!: (value: Metadata[]) => void;
    api.post.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/search?q=dune', SearchPage);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelectorAll('.cards app-media-card')).toHaveLength(12);
    expect(harness.routeNativeElement!.querySelectorAll('.cards p-skeleton')).toHaveLength(48);
    expect(harness.routeNativeElement!.textContent).not.toContain('KZ.LOADING');
    finish([item, item, item]);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('p-skeleton')).toBeNull();
    api.post.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const pending = page.reload();
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelectorAll('.cards app-media-card')).toHaveLength(3);
    expect(harness.routeNativeElement!.querySelectorAll('.cards p-skeleton')).toHaveLength(12);
    finish([item, item, item]);
    await pending;
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelector('p-skeleton')).toBeNull();
  });

  it('searches from the URL including catalog ids containing colons', async () => {
    const { harness, page } = await open('/search?q=%20dune%20&catalog=movie:search:movies');
    expect(page.catalog).toEqual(movie);
    expect(page.query).toBe('dune');
    expect(api.post).toHaveBeenCalledWith('/discovery/search', {
      resourceType: 'movie',
      catalogId: movie.id,
      query: 'dune',
    });
    expect(harness.routeNativeElement!.querySelector('.media-card')?.textContent).toContain('Dune');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await page.open(item);
    expect(navigate).toHaveBeenCalledWith(['/movie', 'tt1'], { state: { mediaPreview: item } });
  });

  it('falls back to the first searchable catalog when the URL catalog is unknown', async () => {
    const { page } = await open('/search?q=dune&catalog=movie:missing');
    expect(page.catalog).toEqual(movie);
    expect(api.post).toHaveBeenCalledWith('/discovery/search', {
      resourceType: 'movie',
      catalogId: movie.id,
      query: 'dune',
    });
  });

  it.each(['/search', '/search?q=%20%20'])(
    'prompts without requesting results for %s',
    async (url) => {
      const { harness } = await open(url);
      expect(api.post).not.toHaveBeenCalled();
      expect(harness.routeNativeElement!.textContent).toContain('KZ.SEARCH_PROMPT');
    },
  );

  it('replaces the URL for subsequent searches and reloads the active query with forceRefresh', async () => {
    const { page, harness } = await open('/search?q=dune&catalog=movie:search:movies');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    await page.search({ catalog: series, query: ' arrival ' });
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(navigate).toHaveBeenCalledWith(['/search'], {
      queryParams: { q: 'arrival', catalog: 'series:series' },
      replaceUrl: true,
    });
    expect(api.post).toHaveBeenLastCalledWith('/discovery/search', {
      resourceType: 'series',
      catalogId: 'series',
      query: 'arrival',
    });
    let finish!: (items: Metadata[]) => void;
    api.post.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const pending = page.reload();
    harness.detectChanges();
    expect(
      harness.routeNativeElement!.querySelector('.row p-button button')?.hasAttribute('disabled'),
    ).toBe(true);
    expect(api.post).toHaveBeenLastCalledWith('/discovery/search?forceRefresh=true', {
      resourceType: 'series',
      catalogId: 'series',
      query: 'arrival',
    });
    await page.reload();
    finish([item]);
    await pending;
    expect(page.results()).toEqual([item]);
  });
});
