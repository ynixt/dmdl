import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { TitleStrategy, provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { providePrimeNG } from 'primeng/config';
import { catchError, map, of, switchMap } from 'rxjs';
import { routes } from './app.routes';
import { credentialsInterceptor } from './core/auth/credentials.interceptor';
import { unauthorizedInterceptor } from './core/auth/unauthorized.interceptor';
import { AuthService } from './core/auth/auth.service';
import { availabilityInterceptor } from './core/availability.interceptor';
import { AppTitleStrategy } from './core/i18n/app-title.strategy';
import { DEFAULT_LANGUAGE, I18nService, toSupportedLanguage } from './core/i18n/i18n.service';
import { dmdlPrimeNgTheme } from './core/theme/dmdl.preset';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    providePrimeNG({
      ripple: true,
      theme: dmdlPrimeNgTheme,
    }),
    provideHttpClient(
      withInterceptors([credentialsInterceptor, unauthorizedInterceptor, availabilityInterceptor]),
    ),
    provideRouter(routes),
    provideTranslateService({
      lang: DEFAULT_LANGUAGE,
      fallbackLang: DEFAULT_LANGUAGE,
      loader: provideTranslateHttpLoader({
        prefix: '/i18n/',
        suffix: '.json',
      }),
    }),
    provideAppInitializer(() => {
      const i18nService = inject(I18nService);
      const authService = inject(AuthService);

      return i18nService.initialize().pipe(
        switchMap(() => authService.initializeSession()),
        switchMap((isAuthenticated) => {
          if (!isAuthenticated || !authService.currentOnboardingCompleted()) {
            return of(void 0);
          }

          const backendLanguage = toSupportedLanguage(authService.currentBackendLocale());
          if (!backendLanguage || backendLanguage === i18nService.currentLanguage()) {
            return of(void 0);
          }

          return i18nService.setLanguage(backendLanguage).pipe(map(() => void 0));
        }),
        catchError(() => of(void 0)),
      );
    }),
    {
      provide: TitleStrategy,
      useClass: AppTitleStrategy,
    },
  ],
};
