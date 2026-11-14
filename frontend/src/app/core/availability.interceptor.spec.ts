import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiAvailabilityService } from './api-availability.service';
import { AuthService } from './auth/auth.service';
import { availabilityInterceptor } from './availability.interceptor';

describe('Availability interceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let router: Router;
  let availability: ApiAvailabilityService;
  let authenticated: boolean;

  beforeEach(() => {
    authenticated = true;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([availabilityInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: () => authenticated } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    router = TestBed.inject(Router);
    availability = TestBed.inject(ApiAvailabilityService);
  });
  afterEach(() => http.verify());

  it.each([true, false])('confirms a gateway failure with probe success %s', async (success) => {
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const operation = firstValueFrom(client.get('/api/downloads')).catch((error: unknown) => error);
    http.expectOne('/api/downloads').flush({}, { status: 502, statusText: 'Bad gateway' });
    const probe = http.expectOne('/api/auth/registration');
    if (success) probe.flush({ registrationRequired: false });
    else probe.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(await operation).toMatchObject({ status: 502 });
    await Promise.resolve();
    expect(navigate).toHaveBeenCalledTimes(success ? 0 : 1);
    if (!success) expect(navigate.mock.calls[0][0].toString()).toBe('/offline?returnUrl=%2F');
  });
  it.each([
    '/api/auth/login',
    '/api/auth/logout',
    '/api/auth/register',
    '/api/auth/registration',
    '/i18n/en-US.json',
    '/api-other/test',
  ])('excludes %s from confirmation', async (url) => {
    const operation = firstValueFrom(client.get(url)).catch((error: unknown) => error);
    http.expectOne(url).flush({}, { status: 502, statusText: 'Bad gateway' });
    await operation;
    http.expectNone('/api/auth/registration');
  });
  it('excludes unauthenticated failures', async () => {
    authenticated = false;
    const operation = firstValueFrom(client.get('/api/downloads')).catch((error: unknown) => error);
    http.expectOne('/api/downloads').error(new ProgressEvent('error'));
    await operation;
    http.expectNone('/api/auth/registration');
  });
  it('tracks successful API responses including absolute URLs', async () => {
    const operation = firstValueFrom(client.get('http://localhost/api/downloads'));
    http.expectOne('http://localhost/api/downloads').flush([]);
    await operation;
    expect(availability.reached()).toBe(true);
  });
});
