import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import { firstValueFrom, map, Observable, of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { AppLanguage, I18nService } from '../../core/i18n/i18n.service';
import { DebridApi, Settings } from '../../services/debrid-api.service';
import { OnboardingPage } from './onboarding.page';

describe('Onboarding wizard', () => {
  const settings = (): Settings => ({
    username: 'fixture',
    locale: 'pt-BR',
    maxConcurrentDownloads: 2,
    configurationRevision: 4,
    storageRoot: '/media',
    onboardingCompleted: false,
    organization: {
      revision: 1,
      categories: {
        MOVIE: {
          folderTemplate: '{title}[ {providerId}]',
          defaultDestinationId: 'movie-hdd',
          destinations: [{ id: 'movie-hdd', name: 'HDD', path: '/hdd/movies' }],
        },
        SERIES: {
          folderTemplate: '{title}/Season {season:02}',
          defaultDestinationId: 'series-hdd',
          destinations: [{ id: 'series-hdd', name: 'HDD', path: '/hdd/series' }],
        },
        ANIME: {
          folderTemplate: '{title}/Season {season:02}',
          defaultDestinationId: 'anime-hdd',
          destinations: [{ id: 'anime-hdd', name: 'HDD', path: '/hdd/anime' }],
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
  });
  const api = { get: vi.fn(), post: vi.fn() };
  const auth = { refreshSession: vi.fn(async () => true) };
  const router = { navigate: vi.fn(async () => true) };
  const i18n = {
    setLanguage: vi.fn<(language: string) => Observable<void>>(() => of(void 0)),
    currentLanguage: vi.fn<() => AppLanguage>(() => 'pt-BR'),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockImplementation(async (path: string) => {
      if (path === '/settings') return settings();
      if (path === '/settings/stream-badges/defaults') return { streamBadgeJson: '{"imports":[]}' };
      return [];
    });
    api.post.mockResolvedValue({ valid: true, errors: {} });
    i18n.setLanguage.mockImplementation(() => of(void 0));
    i18n.currentLanguage.mockReturnValue('pt-BR');
    TestBed.configureTestingModule({
      providers: [
        provideTranslateService(),
        { provide: DebridApi, useValue: api },
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
        { provide: I18nService, useValue: i18n },
      ],
    });
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  });

  it('shows one step with one advance action and keeps integrations in the draft', async () => {
    const fixture = TestBed.createComponent(OnboardingPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.componentInstance;
    await vi.waitFor(() => expect(page.draft).not.toBeNull());
    fixture.detectChanges();
    page.draft!.metadataUrl = 'https://metadata/manifest.json';
    page.searchRows = [
      { manifestUrl: 'https://addon.example/config/manifest.json', enabled: true },
    ];
    expect(fixture.nativeElement.querySelectorAll('.wizard-footer button')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('#setup-metadata')).not.toBeNull();
    for (let index = 0; index < 3; index++) await page.advance();
    expect(page.step()).toBe(3);
    expect(
      api.post.mock.calls.every(
        ([path]) => path === '/setup/validate' || path === '/settings/organization/preview',
      ),
    ).toBe(true);
    expect(page.payload()).not.toHaveProperty('providers');
    expect(page.payload().settings).not.toHaveProperty('defaultProvider');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.wizard-footer button')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('#setup-token-TORBOX')).toBeNull();
    expect(page.payload().settings.organization.categories.MOVIE.destinations).toHaveLength(1);
    expect(page.payload().settings.jellyfin.enabled).toBe(false);
  });

  it('edits and restores badge JSON in draft and submits it only at completion', async () => {
    const fixture = TestBed.createComponent(OnboardingPage);
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.componentInstance;
    await vi.waitFor(() => expect(page.draft).not.toBeNull());
    page.step.set(1);
    fixture.detectChanges();
    await fixture.whenStable();
    const editor = fixture.nativeElement.querySelector('#setup-badge-json') as HTMLTextAreaElement;
    editor.value = '{"filters":[{"name":"HEVC","pattern":"hevc"}]}';
    editor.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(page.payload().settings.streamBadgeJson).toBe(editor.value);
    expect(api.post).not.toHaveBeenCalled();
    fixture.debugElement.query(By.css('app-stream-badge-fields button')).nativeElement.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.payload().settings.streamBadgeJson).toBe(page.defaultBadges);
    expect(api.post).not.toHaveBeenCalled();
    await page.complete();
    expect(api.post).toHaveBeenCalledWith(
      '/setup/complete',
      expect.objectContaining({
        draft: expect.objectContaining({
          settings: expect.objectContaining({ streamBadgeJson: page.defaultBadges }),
        }),
      }),
    );
  });
  it('keeps an invalid step and preserves user input', async () => {
    const page = TestBed.createComponent(OnboardingPage).componentInstance;
    await page.ngOnInit();
    page.step.set(0);
    page.draft!.metadataUrl = 'https://invalid/manifest.json';
    api.post.mockResolvedValue({
      valid: false,
      errors: { 'metadata.manifestUrl': 'KZ.INVALID_CONFIGURATION' },
    });
    await page.advance();
    expect(page.step()).toBe(0);
    expect(page.draft!.metadataUrl).toBe('https://invalid/manifest.json');
    expect(page.errors()['metadata.manifestUrl']).toBeTruthy();
  });

  it('adds and removes named search addons explicitly without intermediate saves', async () => {
    const fixture = TestBed.createComponent(OnboardingPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    await vi.waitFor(() => expect(page.draft).not.toBeNull());
    page.step.set(1);
    fixture.detectChanges();
    const url = fixture.nativeElement.querySelector('#setup-source-0') as HTMLInputElement;
    url.value = 'https://addon.example/stremio/config';
    url.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.searchRows).toHaveLength(1);
    expect(fixture.nativeElement.querySelectorAll('.source-fields')).toHaveLength(1);
    page.searchRows[0].name = 'AIOStreams TorBox';
    page.draft!.metadataName = 'My AIOMetadata';
    fixture.debugElement
      .query(By.css('p-button[icon="pi pi-plus"]'))
      .triggerEventHandler('onClick');
    fixture.detectChanges();
    expect(page.searchRows).toHaveLength(2);
    expect(page.payload().search).toHaveLength(2);
    expect(page.payload().search[0].name).toBe('AIOStreams TorBox');
    expect(page.payload().metadataName).toBe('My AIOMetadata');
    expect(api.post).not.toHaveBeenCalled();
    const first = page.searchRows[0];
    fixture.debugElement
      .queryAll(By.css('p-button[icon="pi pi-trash"]'))[1]
      .triggerEventHandler('onClick');
    fixture.detectChanges();
    expect(page.searchRows).toEqual([first]);
    expect(fixture.nativeElement.querySelector('.wizard-footer').textContent).toContain(
      'KZ.NEXT_STEP',
    );
    api.post.mockResolvedValue({
      valid: false,
      errors: { 'search.0.manifestUrl': 'KZ.ADDON_NOT_FOUND' },
    });
    await page.advance();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#setup-source-error-0').textContent).toContain(
      'KZ.ADDON_NOT_FOUND',
    );
    expect(page.step()).toBe(1);
    expect(page.searchRows[0]).toBe(first);
  });

  it('initializes the draft with the active browser language', async () => {
    i18n.currentLanguage.mockReturnValue('en-US');
    const page = TestBed.createComponent(OnboardingPage).componentInstance;
    await page.ngOnInit();
    expect(page.draft!.settings.locale).toBe('en-US');
    expect(i18n.setLanguage).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('translates language selection immediately and finishes on the Jellyfin step', async () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('pt-BR', { KZ: { LANGUAGE: 'Idioma', NEXT_STEP: 'Avançar' } });
    translate.setTranslation('en-US', { KZ: { LANGUAGE: 'Language', NEXT_STEP: 'Next' } });
    await firstValueFrom(translate.use('pt-BR'));
    i18n.setLanguage.mockImplementation((language) =>
      translate.use(language).pipe(map(() => void 0)),
    );
    const fixture = TestBed.createComponent(OnboardingPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    await vi.waitFor(() => expect(page.draft).not.toBeNull());
    fixture.detectChanges();
    fixture.debugElement
      .query(By.css('p-select[inputId="setup-language"]'))
      .triggerEventHandler('ngModelChange', 'en-US');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('label[for="setup-language"]').textContent).toBe(
      'Language',
    );
    expect(fixture.nativeElement.querySelector('.wizard-footer').textContent).toContain('Next');
    expect(page.draft!.settings.locale).toBe('en-US');
    expect(page.step()).toBe(0);
    expect(api.post).not.toHaveBeenCalled();

    page.step.set(3);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#setup-language')).toBeNull();
    expect(fixture.nativeElement.querySelector('.wizard-footer').textContent).toContain(
      'KZ.SAVE_COMPLETE_SETUP',
    );
    expect(page.steps).toEqual(['metadata', 'search', 'storage', 'jellyfin']);
    expect(page.draft!.settings.locale).toBe('en-US');
    expect(api.post).not.toHaveBeenCalled();
  });

  it('retains failed final submissions and retries with the same identifier while preventing duplicate activation', async () => {
    const page = TestBed.createComponent(OnboardingPage).componentInstance;
    await page.ngOnInit();
    page.step.set(3);
    api.post.mockRejectedValueOnce({
      error: { error: 'KZ.SETUP_INVALID', errors: { storage: 'KZ.INVALID_CONFIGURATION' } },
    });
    await page.complete();
    const first = api.post.mock.calls[0][1];
    expect(page.error()).toBe('KZ.SETUP_INVALID');
    const pending = page.complete();
    await page.complete();
    await pending;
    expect(api.post).toHaveBeenCalledTimes(2);
    expect(api.post.mock.calls[1][1].submissionId).toBe(first.submissionId);
    expect(auth.refreshSession).toHaveBeenCalledTimes(1);
    expect(router.navigate).toHaveBeenCalledWith(['/discovery']);
  });

  it('tests draft Jellyfin URL and token from the optional step and clears results after URL or key changes', async () => {
    const fixture = TestBed.createComponent(OnboardingPage);
    fixture.detectChanges();
    const page = fixture.componentInstance;
    await vi.waitFor(() => expect(page.busy()).toBe(false));
    page.step.set(3);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#setup-jellyfin-token')).toBeNull();
    await page.testJellyfin();
    expect(api.post).not.toHaveBeenCalled();
    fixture.debugElement.query(By.css('p-toggleswitch')).triggerEventHandler('ngModelChange', true);
    fixture.detectChanges();
    const button = fixture.debugElement.query(By.css('p-button[icon="pi pi-check-circle"]'));
    expect(button.componentInstance.disabled).toBe(true);
    const url = fixture.nativeElement.querySelector('#setup-jellyfin-url') as HTMLInputElement;
    const token = fixture.nativeElement.querySelector('#setup-jellyfin-token') as HTMLInputElement;
    url.value = ' http://jellyfin.fixture:8096 ';
    url.dispatchEvent(new Event('input'));
    token.value = ' draft-jellyfin ';
    token.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(button.componentInstance.disabled).toBe(false);
    api.post.mockResolvedValueOnce({ ok: true });
    button.triggerEventHandler('onClick');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/settings/jellyfin/test', {
      enabled: true,
      url: 'http://jellyfin.fixture:8096',
      token: 'draft-jellyfin',
    });
    expect(
      fixture.nativeElement.querySelector('#setup-test-result-JELLYFIN').textContent,
    ).toContain('KZ.CONNECTION_OK');
    expect(page.step()).toBe(3);
    url.value = 'http://other-jellyfin.fixture';
    url.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(page.connectionTests()['JELLYFIN']).toBeUndefined();
    api.post.mockResolvedValueOnce({ ok: true });
    await page.testJellyfin();
    token.value = 'changed-key';
    token.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(page.connectionTests()['JELLYFIN']).toBeUndefined();
    expect(page.draft!.settings.jellyfin.token).toBe('changed-key');
    expect(auth.refreshSession).not.toHaveBeenCalled();
  });
});
