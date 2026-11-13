import { inject, Injectable, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { map, Observable, tap } from 'rxjs';

export const DEFAULT_LANGUAGE = 'en-US';
export const SUPPORTED_LANGUAGES = ['en-US', 'pt-BR'] as const;
const LANGUAGE_STORAGE_KEY = 'dmdl.language';

export type AppLanguage = (typeof SUPPORTED_LANGUAGES)[number];

@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly translate = inject(TranslateService);
  private readonly activeLanguage = signal<AppLanguage>(DEFAULT_LANGUAGE);

  readonly availableLanguages = SUPPORTED_LANGUAGES;
  readonly currentLanguage = this.activeLanguage.asReadonly();

  initialize(): Observable<void> {
    this.translate.addLangs([...SUPPORTED_LANGUAGES]);

    const language = this.resolveInitialLanguage();
    return this.setLanguage(language, false);
  }

  setLanguage(language: AppLanguage, persistSelection = true): Observable<void> {
    return this.translate.use(language).pipe(
      tap(() => {
        this.activeLanguage.set(language);
        if (persistSelection) this.persistLanguage(language);
      }),
      map(() => void 0),
    );
  }

  private resolveInitialLanguage(): AppLanguage {
    const savedLanguage = this.readPersistedLanguage();
    if (savedLanguage) {
      return savedLanguage;
    }

    const browserLanguage =
      this.translate.getBrowserCultureLang() || this.translate.getBrowserLang();

    return toSupportedLanguage(browserLanguage) ?? DEFAULT_LANGUAGE;
  }

  private persistLanguage(language: AppLanguage): void {
    if (!isLocalStorageAvailable()) {
      return;
    }

    localStorage.setItem(LANGUAGE_STORAGE_KEY, language);
  }

  private readPersistedLanguage(): AppLanguage | null {
    if (!isLocalStorageAvailable()) {
      return null;
    }

    const storedLanguage = localStorage.getItem(LANGUAGE_STORAGE_KEY);
    return isSupportedLanguage(storedLanguage) ? storedLanguage : null;
  }
}

function isSupportedLanguage(language: string | null): language is AppLanguage {
  return language === 'en-US' || language === 'pt-BR';
}

export function toSupportedLanguage(language: string | null | undefined): AppLanguage | null {
  if (!language) {
    return null;
  }
  const normalized = language.trim();
  if (isSupportedLanguage(normalized)) {
    return normalized;
  }

  const baseLanguage = normalized.toLowerCase().split('-')[0];
  if (baseLanguage === 'pt') return 'pt-BR';
  if (baseLanguage === 'en') return 'en-US';
  return null;
}

function isLocalStorageAvailable(): boolean {
  return typeof window !== 'undefined' && typeof localStorage !== 'undefined';
}
