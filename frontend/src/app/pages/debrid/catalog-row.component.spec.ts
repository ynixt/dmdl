import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { Catalog, DebridApi, Metadata } from '../../services/debrid-api.service';
import { CatalogRowComponent } from './catalog-row.component';

describe('CatalogRowComponent', () => {
  const catalog: Catalog = {
    id: 'popular',
    type: 'movie',
    name: 'Popular',
    searchable: true,
    browsable: true,
    pageable: true,
  };
  const items: Metadata[] = Array.from({ length: 12 }, (_, index) => ({
    identity: {
      sourceId: 'meta',
      sourceRevision: 1,
      resourceType: 'movie',
      contentId: 'tt' + index,
    },
    title: 'Movie ' + index,
    videos: [],
  }));
  const api = { get: vi.fn<(path: string) => Promise<Metadata[]>>() };

  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1400 });
    api.get.mockResolvedValue(items);
    TestBed.configureTestingModule({
      providers: [provideTranslateService(), { provide: DebridApi, useValue: api }],
    });
  });

  async function create(selected = catalog) {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', selected);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('keeps one carousel mounted while initial skeletons become cards and during reload', async () => {
    let finish!: (value: Metadata[]) => void;
    api.get.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', catalog);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    const carousel = fixture.componentInstance.carousel();
    expect(fixture.nativeElement.querySelectorAll('p-skeleton')).toHaveLength(24);
    expect(fixture.nativeElement.querySelector('section').getAttribute('aria-busy')).toBe('true');
    expect(fixture.nativeElement.querySelector('[role="status"]').getAttribute('aria-label')).toBe(
      'KZ.LOADING',
    );
    expect(fixture.nativeElement.querySelector('[role="status"]').hasAttribute('inert')).toBe(
      false,
    );
    expect(fixture.nativeElement.textContent).not.toContain('KZ.LOADING');
    fixture.componentInstance.onPage({ page: 1 });
    expect(changed).not.toHaveBeenCalled();
    finish(items);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.carousel()).toBe(carousel);
    expect(fixture.nativeElement.querySelector('p-skeleton')).toBeNull();
    api.get.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const pending = fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(fixture.componentInstance.carousel()).toBe(carousel);
    expect(fixture.nativeElement.querySelectorAll('p-skeleton')).toHaveLength(24);
    finish(items);
    await pending;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('p-skeleton')).toBeNull();
  });

  it('loads required options from the manifest default and preserves them when paging and reloading', async () => {
    const selected = {
      ...catalog,
      extra: [
        {
          name: 'genre',
          isRequired: true,
          options: ['Action & Adventure', 'Drama'],
          default: 'Drama',
        },
      ],
    };
    const fixture = await create(selected);
    const row = fixture.componentInstance;
    expect(fixture.nativeElement.querySelector('p-select')?.textContent).toContain('Drama');
    expect(api.get).toHaveBeenCalledWith('/discovery/catalogs/movie/popular?skip=0&genre=Drama');
    row.carousel()!.page = 1;
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=12&genre=Drama',
    );
    await row.reload();
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=0&genre=Drama&forceRefresh=true',
    );
    expect(row.snapshot().extras).toEqual({ genre: 'Drama' });
  });

  it('resets paging on option changes and uses the first required option when no default exists', async () => {
    const selected = {
      ...catalog,
      extra: [{ name: 'genre', isRequired: true, options: ['Action & Adventure', 'Drama'] }],
    };
    const fixture = await create(selected);
    const row = fixture.componentInstance;
    expect(row.selectedExtras).toEqual({ genre: 'Action & Adventure' });
    row.page = 1;
    row.fresh = true;
    row.selectedExtras['genre'] = 'Drama';
    await row.reload(false);
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=0&genre=Drama',
    );
    expect(row.items()).toHaveLength(12);
    expect(row.page).toBe(0);
    expect(row.fresh).toBe(false);
  });

  it('restores selected options along with the loaded cards without refetching', async () => {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', {
      ...catalog,
      extra: [{ name: 'genre', isRequired: true, options: ['Action', 'Drama'], default: 'Action' }],
    });
    fixture.componentRef.setInput('restored', {
      items,
      page: 1,
      exhausted: false,
      fresh: true,
      extras: { genre: 'Drama' },
    });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: 'Drama' });
    expect(fixture.componentInstance.page).toBe(1);
    expect(api.get).not.toHaveBeenCalled();
  });

  it.each(['empty', 'error'])(
    'keeps option selectors available for an %s selection',
    async (outcome) => {
      const selected = {
        ...catalog,
        extra: [
          { name: 'genre', isRequired: true, options: ['Action', 'Drama'], default: 'Action' },
        ],
      };
      if (outcome === 'empty') api.get.mockResolvedValueOnce([]);
      else api.get.mockRejectedValueOnce(new Error('Unavailable'));
      const fixture = TestBed.createComponent(CatalogRowComponent);
      fixture.componentRef.setInput('catalog', selected);
      const hidden = vi.fn();
      fixture.componentInstance.unavailable.subscribe(hidden);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(hidden).not.toHaveBeenCalled();
      expect(fixture.nativeElement.querySelector('p-select')).not.toBeNull();
      fixture.componentInstance.selectedExtras['genre'] = 'Drama';
      await fixture.componentInstance.reload(false);
      expect(fixture.componentInstance.items()).toHaveLength(12);
    },
  );

  it('starts optional selectors at All and returns there when the clear action is clicked', async () => {
    const fixture = await create({
      ...catalog,
      extra: [{ name: 'genre', isRequired: false, options: ['Day', 'Week'], default: 'Day' }],
    });
    expect(fixture.nativeElement.querySelector('p-select')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.p-select-clear-icon')).toBeNull();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: '' });
    expect(fixture.nativeElement.querySelector('p-select').textContent).toContain(
      'KZ.ALL_CATALOG_OPTIONS',
    );
    expect(api.get).toHaveBeenLastCalledWith('/discovery/catalogs/movie/popular?skip=0&genre=');
    fixture.componentInstance.selectedExtras['genre'] = 'Day';
    await fixture.componentInstance.reload(false);
    fixture.detectChanges();
    const clear = fixture.nativeElement.querySelector('.p-select-clear-icon');
    expect(clear).not.toBeNull();
    clear.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: '' });
    expect(api.get).toHaveBeenLastCalledWith('/discovery/catalogs/movie/popular?skip=0&genre=');
  });

  it('hides a sole optional choice and uses All', async () => {
    const fixture = await create({
      ...catalog,
      extra: [{ name: 'genre', isRequired: false, options: ['None'], default: 'None' }],
    });
    expect(fixture.nativeElement.querySelector('p-select')).toBeNull();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: '' });
    expect(api.get).toHaveBeenLastCalledWith('/discovery/catalogs/movie/popular?skip=0&genre=');
  });

  it('applies a sole required option automatically', async () => {
    const fixture = await create({
      ...catalog,
      extra: [{ name: 'genre', isRequired: true, options: ['Action'], default: 'Action' }],
    });
    expect(fixture.nativeElement.querySelector('p-select')).toBeNull();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: 'Action' });
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=0&genre=Action',
    );
  });

  it('restores the All selection with its cached page', async () => {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', {
      ...catalog,
      extra: [{ name: 'genre', isRequired: false, options: ['Day', 'Week'], default: 'Day' }],
    });
    fixture.componentRef.setInput('restored', {
      items,
      page: 1,
      exhausted: false,
      fresh: false,
      extras: { genre: '' },
    });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: '' });
    expect(fixture.componentInstance.page).toBe(1);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('starts optional selectors at All when the manifest has no default', async () => {
    const fixture = await create({
      ...catalog,
      extra: [{ name: 'genre', isRequired: false, options: ['Day', 'Week'] }],
    });
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: '' });
    expect(api.get).toHaveBeenLastCalledWith('/discovery/catalogs/movie/popular?skip=0&genre=');
  });

  it('replaces the previous TMDB Popular default saved in older history with All', async () => {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', {
      ...catalog,
      id: 'tmdb.top',
      extra: [
        {
          name: 'genre',
          isRequired: false,
          options: ['Animação', 'Aventura'],
          default: 'Animação',
        },
      ],
    });
    fixture.componentRef.setInput('restored', {
      items,
      page: 1,
      exhausted: false,
      fresh: true,
      extras: { genre: 'Animação' },
    });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: '' });
    expect(fixture.componentInstance.page).toBe(0);
    expect(api.get).toHaveBeenCalledWith('/discovery/catalogs/movie/tmdb.top?skip=0&genre=');
    expect(fixture.componentInstance.snapshot().selectionVersion).toBe(1);
  });

  it('restores an explicit optional selection from a current history snapshot', async () => {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', {
      ...catalog,
      id: 'tmdb.top',
      extra: [
        {
          name: 'genre',
          isRequired: false,
          options: ['Animação', 'Aventura'],
          default: 'Animação',
        },
      ],
    });
    fixture.componentRef.setInput('restored', {
      items,
      page: 1,
      exhausted: false,
      fresh: true,
      extras: { genre: 'Animação' },
      selectionVersion: 1,
    });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.selectedExtras).toEqual({ genre: 'Animação' });
    expect(fixture.componentInstance.page).toBe(1);
    expect(fixture.componentInstance.items()).toEqual(items);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('loads page zero and appends at the end without resetting the PrimeNG carousel page', async () => {
    const fixture = await create();
    const row = fixture.componentInstance;
    const carousel = row.carousel()!;
    expect(api.get).toHaveBeenCalledWith('/discovery/catalogs/movie/popular?skip=0');
    expect(carousel.showNavigators).toBe(true);
    expect(carousel.showIndicators).toBe(false);
    expect(carousel.circular).toBe(false);
    api.get.mockResolvedValueOnce(items.map((item) => ({ ...item, title: 'Next ' + item.title })));
    carousel.page = 1;
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.get).toHaveBeenLastCalledWith('/discovery/catalogs/movie/popular?skip=12');
    expect(row.items()).toHaveLength(24);
    expect(row.page).toBe(1);
    expect(carousel.page).toBe(1);
    expect(carousel.firstIndex()).toBe(6);
  });

  it('stops paging after an empty page and ignores end events for non-pageable catalogs', async () => {
    const fixture = await create();
    api.get.mockResolvedValueOnce([]);
    fixture.componentInstance.carousel()!.page = 1;
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.exhausted).toBe(true);
    fixture.componentInstance.onPage({ page: 1 });
    expect(api.get).toHaveBeenCalledTimes(2);
    const plain = await create({ ...catalog, pageable: false });
    plain.componentInstance.carousel()!.page = 1;
    await plain.whenStable();
    expect(api.get).toHaveBeenCalledTimes(3);
  });

  it('reloads at the start and forces later pages while preserving fresh mode in snapshots', async () => {
    const fixture = await create();
    const row = fixture.componentInstance;
    row.page = 1;
    await row.reload();
    fixture.detectChanges();
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=0&forceRefresh=true',
    );
    expect(row.page).toBe(0);
    expect(row.items()).toHaveLength(12);
    row.carousel()!.page = 1;
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=12&forceRefresh=true',
    );
    expect(row.snapshot()).toMatchObject({ page: 1, fresh: true, exhausted: false });
  });

  it.each(['empty', 'error'])('reports an unavailable first page on %s', async (outcome) => {
    if (outcome === 'empty') api.get.mockResolvedValue([]);
    else api.get.mockRejectedValue(new Error('Unavailable'));
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', catalog);
    const unavailable = vi.fn();
    fixture.componentInstance.unavailable.subscribe(unavailable);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(unavailable).toHaveBeenCalledOnce();
  });

  it('restores items page exhaustion and fresh mode without requesting data', async () => {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', catalog);
    fixture.componentRef.setInput('restored', { items, page: 1, exhausted: true, fresh: true });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.snapshot()).toEqual({
      items,
      page: 1,
      exhausted: true,
      fresh: true,
    });
    expect(fixture.componentInstance.carousel()!.page).toBe(1);
    expect(
      fixture.nativeElement.querySelector('.p-carousel-item-active .media-card')?.textContent,
    ).toContain('Movie 6');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('advances through the carousel touch event bindings at a mobile width', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    const fixture = await create();
    const viewport = fixture.nativeElement.querySelector('.p-carousel-viewport') as HTMLElement;
    for (const [type, pageX] of [
      ['touchstart', 300],
      ['touchmove', 100],
      ['touchend', 100],
    ] as const) {
      const event = new Event(type, { bubbles: true, cancelable: true });
      Object.defineProperty(event, 'changedTouches', { value: [{ pageX, pageY: 100 }] });
      viewport.dispatchEvent(event);
    }
    fixture.detectChanges();
    expect(fixture.componentInstance.carousel()!.numVisible).toBe(2);
    expect(fixture.componentInstance.page).toBe(1);
    expect(fixture.componentInstance.carousel()!.firstIndex()).toBe(2);
    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('loads beyond an initial page that fits entirely in the viewport', async () => {
    api.get.mockResolvedValueOnce(items.slice(0, 2)).mockResolvedValueOnce(items.slice(2));
    const fixture = await create();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.get).toHaveBeenLastCalledWith('/discovery/catalogs/movie/popular?skip=2');
    expect(fixture.componentInstance.items()).toHaveLength(12);
    expect(fixture.componentInstance.page).toBe(0);
  });

  it('resumes a reload that was pending when the user left the page', async () => {
    const fixture = TestBed.createComponent(CatalogRowComponent);
    fixture.componentRef.setInput('catalog', catalog);
    fixture.componentRef.setInput('restored', {
      items: [],
      page: 0,
      exhausted: false,
      fresh: true,
    });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.get).toHaveBeenLastCalledWith(
      '/discovery/catalogs/movie/popular?skip=0&forceRefresh=true',
    );
    expect(fixture.componentInstance.items()).toHaveLength(12);
    expect(fixture.componentInstance.fresh).toBe(true);
  });
});
