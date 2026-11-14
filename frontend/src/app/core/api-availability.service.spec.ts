import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiAvailabilityService } from './api-availability.service';

describe('API availability', () => {
  let service: ApiAvailabilityService;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    service = TestBed.inject(ApiAvailabilityService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it.each([0, 502, 503, 504])(
    'classifies HTTP %i without a state body as unreachable',
    (status) => {
      expect(service.classify(new HttpErrorResponse({ status }))).toBe('unreachable');
    },
  );
  it.each([401, 403, 500])('keeps HTTP %i in its existing flow', (status) => {
    expect(service.classify(new HttpErrorResponse({ status }))).toBeNull();
  });
  it.each([
    ['starting', 'starting'],
    ['ready', 'not-responding'],
  ])('reads lifecycle state %s', (state, expected) => {
    expect(
      service.classify(
        new HttpErrorResponse({ status: 503, error: { code: 'BACKEND_UNAVAILABLE', state } }),
      ),
    ).toBe(expected);
    expect(service.classify(new HttpErrorResponse({ status: 503, error: { state } }))).toBe(
      'unreachable',
    );
  });
  it('shares one probe and navigation across concurrent failures', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const first = service.confirmAndGoOffline('/downloads');
    const second = service.confirmAndGoOffline('/settings');
    expect(first).toBe(second);
    http
      .expectOne('/api/auth/registration')
      .flush(
        { code: 'BACKEND_UNAVAILABLE', state: 'ready' },
        { status: 503, statusText: 'Unavailable' },
      );
    await first;
    expect(service.reason()).toBe('not-responding');
    expect(navigate).toHaveBeenCalledOnce();
    expect(navigate.mock.calls[0][0].toString()).toBe('/offline?returnUrl=%2Fdownloads');
  });
  it('deduplicates subscribers and allows a later probe', async () => {
    const first = firstValueFrom(service.probe());
    const second = firstValueFrom(service.probe());
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    await Promise.all([first, second]);
    expect(service.reached()).toBe(true);
    const third = firstValueFrom(service.probe());
    http.expectOne('/api/auth/registration').flush({ registrationRequired: true });
    expect(await third).toEqual({ registrationRequired: true });
  });
  it('bounds a hung probe to twelve seconds', async () => {
    vi.useFakeTimers();
    const result = firstValueFrom(service.probe()).catch((error: unknown) => error);
    const request = http.expectOne('/api/auth/registration');
    await vi.advanceTimersByTimeAsync(12000);
    expect(request.cancelled).toBe(true);
    expect(service.classify(await result)).toBe('unreachable');
  });
  it('stays on the page when the API answers an application error', async () => {
    const navigate = vi.spyOn(router, 'navigateByUrl');
    const result = service.confirmAndGoOffline('/downloads');
    http.expectOne('/api/auth/registration').flush({}, { status: 500, statusText: 'Error' });
    expect(await result).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });
});
