import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { firstValueFrom, of } from 'rxjs';
import { DEFAULT_LANGUAGE, I18nService } from './i18n.service';

describe('Browser language defaults', () => {
  const storageKey = 'dmdl.language';
  const translate = {
    addLangs: vi.fn(),
    use: vi.fn(() => of({})),
    getBrowserCultureLang: vi.fn<() => string | undefined>(),
    getBrowserLang: vi.fn<() => string | undefined>(),
  };

  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    translate.getBrowserCultureLang.mockReturnValue(undefined);
    translate.getBrowserLang.mockReturnValue(undefined);
    TestBed.configureTestingModule({
      providers: [{ provide: TranslateService, useValue: translate }],
    });
  });

  afterEach(() => localStorage.clear());

  it.each([
    ['pt-BR', 'pt-BR'],
    ['pt-PT', 'pt-BR'],
    ['PT-br', 'pt-BR'],
    ['en-US', 'en-US'],
    ['en-GB', 'en-US'],
    ['fr-FR', 'en-US'],
    ['de-DE', 'en-US'],
    ['ptx', 'en-US'],
    ['', 'en-US'],
    [undefined, 'en-US'],
  ])('resolves browser language %s to %s', async (browserLanguage, expected) => {
    translate.getBrowserCultureLang.mockReturnValue(browserLanguage);
    const service = TestBed.inject(I18nService);
    await firstValueFrom(service.initialize());
    expect(service.currentLanguage()).toBe(expected);
    expect(translate.use).toHaveBeenCalledWith(expected);
    expect(localStorage.getItem(storageKey)).toBeNull();
  });

  it('uses the browser base language when its culture is unavailable', async () => {
    translate.getBrowserLang.mockReturnValue('pt');
    const service = TestBed.inject(I18nService);
    await firstValueFrom(service.initialize());
    expect(service.currentLanguage()).toBe('pt-BR');
  });

  it('stores a manual selection and gives it priority over browser detection', async () => {
    translate.getBrowserCultureLang.mockReturnValue('pt-BR');
    const service = TestBed.inject(I18nService);
    await firstValueFrom(service.setLanguage('en-US'));
    await firstValueFrom(service.initialize());
    expect(service.currentLanguage()).toBe('en-US');
    expect(localStorage.getItem(storageKey)).toBe('en-US');
  });

  it('rechecks the browser language on a later initialization after automatic detection', async () => {
    const service = TestBed.inject(I18nService);
    translate.getBrowserCultureLang.mockReturnValue('pt-BR');
    await firstValueFrom(service.initialize());
    translate.getBrowserCultureLang.mockReturnValue('en-GB');
    await firstValueFrom(service.initialize());
    expect(service.currentLanguage()).toBe('en-US');
  });

  it('uses English as the translation fallback', () => {
    expect(DEFAULT_LANGUAGE).toBe('en-US');
  });
});
