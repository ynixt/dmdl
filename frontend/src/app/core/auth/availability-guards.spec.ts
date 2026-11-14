import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  provideRouter,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';
import { DebridApi } from '../../services/debrid-api.service';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';
import { guestGuard } from './guest.guard';
import { onboardingGuard } from './onboarding.guard';
import { registrationGuard } from './registration.guard';

describe('Availability guards', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function run(guard: CanActivateFn, url = '/downloads?filter=active') {
    return firstValueFrom(
      TestBed.runInInjectionContext(() =>
        guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
      ) as Observable<boolean | UrlTree>,
    );
  }

  for (const [name, guard, fallback] of [
    ['auth', authGuard, '/login'],
    ['guest', guestGuard, 'true'],
    ['registration', registrationGuard, '/login'],
  ] as const) {
    it.each([0, 502, 503, 504])(
      `${name} sends HTTP %i to offline with the full intended URL`,
      async (status) => {
        const result = run(guard);
        const request = http.expectOne('/api/auth/registration');
        if (status === 0) request.error(new ProgressEvent('error'));
        else request.flush({}, { status, statusText: 'Unavailable' });
        expect((await result).toString()).toBe('/offline?returnUrl=%2Fdownloads%3Ffilter%3Dactive');
      },
    );
    it.each([401, 403, 500])(`${name} retains previous handling for HTTP %i`, async (status) => {
      const result = run(guard);
      http.expectOne('/api/auth/registration').flush({}, { status, statusText: 'Failed' });
      expect((await result).toString()).toBe(fallback);
    });
  }

  it.each([502, 401, 500])('onboarding handles HTTP %i from setup', async (status) => {
    vi.spyOn(TestBed.inject(DebridApi), 'get').mockRejectedValue(new HttpErrorResponse({ status }));
    const result = await run(onboardingGuard);
    expect(result.toString()).toBe(
      status === 502 ? '/offline?returnUrl=%2Fdownloads%3Ffilter%3Dactive' : '/onboarding',
    );
  });

  it('checks the session again after recovery even with a cached authenticated session', async () => {
    const auth = TestBed.inject(AuthService);
    const first = run(authGuard);
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    http.expectOne('/api/auth/session').flush({ username: 'owner', onboardingCompleted: true });
    expect(await first).toBe(true);
    auth.invalidateSession();
    const recovered = run(authGuard);
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    http.expectOne('/api/auth/session').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect((await recovered).toString()).toBe('/login');
  });
});
