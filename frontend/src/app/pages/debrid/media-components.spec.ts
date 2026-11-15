import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterLink } from '@angular/router';
import { By } from '@angular/platform-browser';
import { provideTranslateService } from '@ngx-translate/core';
import { mediaContent, mediaRoute } from './media-route';
import { MediaHeroComponent } from './media-hero.component';
import { MediaCardComponent } from './media-card.component';
import { externalLinks } from './external-links';
import { ExternalLinksComponent } from './external-links.component';
import { Addon, Metadata } from '../../services/debrid-api.service';

describe('Media route mapping', () => {
  const item = (resourceType: string): Metadata => ({
    identity: {
      sourceId: 'meta',
      sourceRevision: 1,
      resourceType,
      contentId: 'own',
      mappedIds: { tmdb: '1', tvdb: '2', imdb: 'tt3' },
    },
    title: 'Title',
    videos: [],
  });
  const manifest = (prefixes: string[]): Addon['manifest'] => ({
    name: 'Meta',
    catalogs: [],
    resources: [{ name: 'meta', idPrefixes: prefixes }],
  });
  it.each([
    ['movie', '/movie', 'tmdb:1'],
    ['series', '/show', 'tvdb:2'],
    ['anime', '/anime', 'tvdb:2'],
  ])('routes %s with the preferred mapped id', (type, prefix, id) => {
    expect(mediaRoute(item(type), manifest(['tmdb:', 'tvdb:', 'tt']))).toEqual([prefix, id]);
  });
  it('chooses the next supported mapping and otherwise the own id', () => {
    expect(mediaRoute(item('movie'), manifest(['tt']))).toEqual(['/movie', 'tt3']);
    expect(mediaRoute(item('series'), manifest(['tmdb:']))).toEqual(['/show', 'tmdb:1']);
    expect(mediaRoute(item('movie'), manifest(['other:']))).toEqual(['/movie', 'own']);
    expect(mediaRoute(item('movie'))).toEqual(['/movie', 'own']);
    const wrongType = manifest(['tt']);
    wrongType.resources![0].types = ['anime'];
    expect(mediaRoute(item('movie'), wrongType)).toEqual(['/movie', 'own']);
  });
  it('does not link unsupported resource types', () => {
    expect(mediaRoute(item('channel'), manifest(['tt']))).toBeNull();
  });
  it('routes by classification while resolving ids under the addon type', () => {
    const anime = { ...item('series'), category: 'ANIME' as const };
    const seriesOnly = manifest(['tvdb:']);
    seriesOnly.resources![0].types = ['series'];
    expect(mediaRoute(anime, seriesOnly)).toEqual(['/anime', 'tvdb:2']);
    expect(mediaRoute({ ...item('anime'), category: 'SERIES' }, manifest(['tvdb:']))).toEqual([
      '/show',
      'tvdb:2',
    ]);
    expect(mediaRoute({ ...item('movie'), category: 'MOVIE' }, manifest(['tt']))).toEqual([
      '/movie',
      'tt3',
    ]);
  });
  it('builds content with the classified category', () => {
    expect(mediaContent({ ...item('series'), category: 'ANIME' }).category).toBe('ANIME');
    expect(mediaContent(item('anime')).category).toBe('ANIME');
    expect(mediaContent(item('series')).category).toBe('SERIES');
    expect(mediaContent(item('movie')).category).toBe('MOVIE');
  });
});

describe('Media presentation', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({ providers: [provideTranslateService(), provideRouter([])] }),
  );
  it('renders loading placeholders in the same card structure and prevents interaction', () => {
    const fixture = TestBed.createComponent(MediaCardComponent);
    fixture.componentRef.setInput('title', 'Title');
    fixture.componentRef.setInput('poster', 'https://image/poster');
    fixture.componentRef.setInput('loading', true);
    const opened = vi.fn();
    fixture.componentInstance.beforeNavigate.subscribe(opened);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.poster p-skeleton')).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('strong').querySelectorAll('p-skeleton'),
    ).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.year p-skeleton')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('.media-card').getAttribute('aria-hidden')).toBe(
      'true',
    );
    expect(fixture.nativeElement.querySelector('a, button')).toBeNull();
    fixture.nativeElement.querySelector('.media-card').click();
    expect(opened).not.toHaveBeenCalled();
    fixture.componentRef.setInput('loading', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('p-skeleton')).toBeNull();
    expect(fixture.nativeElement.querySelector('a, button')).toBeNull();
    expect(fixture.nativeElement.querySelector('strong').textContent).toContain('Title');
  });
  it('renders hero image, title, subtitle and description', () => {
    const fixture = TestBed.createComponent(MediaHeroComponent);
    for (const [key, value] of Object.entries({
      image: 'https://image/poster',
      title: 'Title',
      subtitle: '2026',
      description: 'Synopsis',
    }))
      fixture.componentRef.setInput(key, value);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Title');
    expect(fixture.nativeElement.textContent).toContain('2026');
    expect(fixture.nativeElement.textContent).toContain('Synopsis');
    expect(fixture.nativeElement.querySelector('img').alt).toBe('Title');
  });
  it.each([
    ['IMDB', 'IMDb'],
    ['KITSU', 'Kitsu'],
    [null, 'KZ.RATING_NO_SOURCE'],
    [undefined, 'KZ.RATING_NO_SOURCE'],
  ])('renders the rating with source %s labelled %s', (source, label) => {
    const fixture = TestBed.createComponent(MediaHeroComponent);
    fixture.componentRef.setInput('title', 'Title');
    fixture.componentRef.setInput('rating', '8.6');
    fixture.componentRef.setInput('ratingSource', source);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.rating .score').textContent).toContain('8.6');
    expect(fixture.nativeElement.querySelector('.rating .source').textContent.trim()).toBe(label);
  });
  it.each([undefined, null, ''])('hides the rating block when the rating is %s', (rating) => {
    const fixture = TestBed.createComponent(MediaHeroComponent);
    fixture.componentRef.setInput('title', 'Title');
    fixture.componentRef.setInput('rating', rating);
    fixture.componentRef.setInput('ratingSource', 'IMDB');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.rating')).toBeNull();
  });
  it('renders external links as new-tab buttons and nothing without mapped ids', () => {
    const fixture = TestBed.createComponent(ExternalLinksComponent);
    fixture.componentRef.setInput('mappedIds', { mal: '1', imdb: 'tt0213338' });
    fixture.componentRef.setInput('category', 'ANIME');
    fixture.detectChanges();
    const links = [...fixture.nativeElement.querySelectorAll('a')] as HTMLAnchorElement[];
    expect(links.map((a) => [a.textContent!.trim(), a.href])).toEqual([
      ['IMDb', 'https://www.imdb.com/title/tt0213338'],
      ['MyAnimeList', 'https://myanimelist.net/anime/1'],
    ]);
    for (const a of links) {
      expect(a.target).toBe('_blank');
      expect(a.rel).toBe('noopener noreferrer');
    }
    fixture.componentRef.setInput('mappedIds', {});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('nav')).toBeNull();
  });
  it('renders accessible RouterLinks with preview state and inert unsupported media', () => {
    const fixture = TestBed.createComponent(MediaCardComponent);
    fixture.componentRef.setInput('title', 'Title');
    fixture.componentRef.setInput('poster', 'https://image/poster');
    fixture.componentRef.setInput('year', 2026);
    const opened = vi.fn();
    fixture.componentInstance.beforeNavigate.subscribe(opened);
    fixture.componentRef.setInput('commands', ['/movie', 'tmdb:1']);
    fixture.componentRef.setInput('state', { mediaPreview: { title: 'Title' } });
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/movie/tmdb:1');
    expect(link.getAttribute('aria-label')).toBe('Title');
    expect(link.tabIndex).toBe(0);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    link.click();
    expect(opened).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        state: { mediaPreview: { title: 'Title' } },
      }),
    );
    expect(fixture.debugElement.query(By.directive(RouterLink))).not.toBeNull();
    for (const options of [
      { ctrlKey: true },
      { metaKey: true },
      { shiftKey: true },
      { button: 1 },
    ]) {
      const event = new MouseEvent('click', { bubbles: true, cancelable: true, ...options });
      link.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    }
    expect(opened).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledOnce();
    fixture.componentRef.setInput('commands', null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a, button')).toBeNull();
    expect(fixture.nativeElement.querySelector('.media-card').getAttribute('aria-label')).toContain(
      'KZ.UNSUPPORTED_TYPE',
    );
    expect(fixture.nativeElement.textContent).toContain('2026');
    fixture.nativeElement.querySelector('.media-card').click();
    expect(opened).toHaveBeenCalledOnce();
  });
});

describe('External database links', () => {
  it('links movies to movie pages', () => {
    expect(externalLinks({ imdb: 'tt0133093', tmdb: '603', tvdb: '169' }, 'MOVIE')).toEqual([
      { provider: 'imdb', label: 'IMDb', url: 'https://www.imdb.com/title/tt0133093' },
      { provider: 'tmdb', label: 'TMDB', url: 'https://www.themoviedb.org/movie/603' },
      { provider: 'tvdb', label: 'TVDB', url: 'https://thetvdb.com/dereferrer/movie/169' },
    ]);
  });
  it.each(['SERIES', 'ANIME', null] as const)('links %s titles to series pages', (category) => {
    expect(externalLinks({ tvdb: '431162', tmdb: '220542' }, category).map((l) => l.url)).toEqual([
      'https://www.themoviedb.org/tv/220542',
      'https://thetvdb.com/dereferrer/series/431162',
    ]);
  });
  it('links anime databases in a fixed order and skips unknown providers', () => {
    expect(
      externalLinks({ anidb: '23', anilist: '1', mal: '1', kitsu: '1', other: '9' }, 'ANIME').map(
        (l) => l.url,
      ),
    ).toEqual([
      'https://kitsu.app/anime/1',
      'https://myanimelist.net/anime/1',
      'https://anilist.co/anime/1',
      'https://anidb.net/anime/23',
    ]);
  });
  it('returns no links without mapped ids', () => {
    expect(externalLinks(undefined, 'MOVIE')).toEqual([]);
    expect(externalLinks({}, 'SERIES')).toEqual([]);
  });
});
