import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, TitleStrategy } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { firstValueFrom } from 'rxjs';
import { DownloadsPage } from '../../pages/debrid/downloads.page';
import { DebridApi } from '../../services/debrid-api.service';
import { AppTitleStrategy } from '../i18n/app-title.strategy';
import { AuthService } from './auth.service';
import { guestGuard } from './guest.guard';
import { unauthorizedInterceptor } from './unauthorized.interceptor';
import { availabilityInterceptor } from '../availability.interceptor';

@Component({ template: '<h1>Discovery</h1>' })
class ProtectedTestPage {}

@Component({ template: '<h1>Login</h1>' })
class LoginTestPage {}

class TestEventSource {
  static instances: TestEventSource[] = [];
  onerror: (() => void) | null = null;
  onopen: (() => void) | null = null;
  addEventListener = vi.fn();
  close = vi.fn();
  constructor() {
    TestEventSource.instances.push(this);
  }
}

describe('Authentication bootstrap with HTTP translations', () => {
  it('initializes authentication before the router loads translated page titles', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([unauthorizedInterceptor, availabilityInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService({
          lang: 'en-US',
          fallbackLang: 'en-US',
          loader: provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' }),
        }),
        { provide: TitleStrategy, useClass: AppTitleStrategy },
      ],
    });
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    http.expectOne('/i18n/en-US.json').flush({ APP: { NAME: 'Debrid Media Downloader' } });
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.url).toBe('/');
    http.verify();
  });
});

describe('Expired session handling during page operations', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let router: Router;
  let api: DebridApi;

  beforeEach(() => {
    TestEventSource.instances = [];
    vi.stubGlobal('EventSource', TestEventSource);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([unauthorizedInterceptor])),
        provideHttpClientTesting(),
        provideTranslateService(),
        provideRouter([
          { path: 'discovery', component: ProtectedTestPage },
          { path: 'downloads', component: DownloadsPage },
          { path: 'offline', component: ProtectedTestPage },
          { path: 'login', component: LoginTestPage, canActivate: [guestGuard] },
        ]),
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    api = TestBed.inject(DebridApi);
  });

  afterEach(() => {
    http.verify();
    vi.unstubAllGlobals();
  });

  async function authenticate(): Promise<void> {
    const login = auth.login({ username: 'owner', password: 'fixture-password' });
    http.expectOne('/api/auth/login').flush({
      status: 'AUTHENTICATED',
      locale: 'pt-BR',
      onboardingCompleted: true,
    });
    await login;
  }

  it.each([401, 403])('returns to login before the page receives HTTP %i', async (status) => {
    await authenticate();
    const harness = await RouterTestingHarness.create('/discovery');
    const navigate = vi.spyOn(router, 'navigate');
    const operation = api.post('/discovery/search', { query: 'Example' }).catch((error) => {
      expect(router.url).toBe('/login');
      return error;
    });
    http
      .expectOne('/api/discovery/search')
      .flush({ error: 'UNAUTHORIZED' }, { status, statusText: 'Unauthorized' });
    expect(await operation).toMatchObject({ status });
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.currentUsername()).toBeNull();
    expect(auth.currentBackendLocale()).toBeNull();
    expect(auth.currentOnboardingCompleted()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });
    expect(harness.routeNativeElement?.textContent).toContain('Login');
  });

  it('redirects when local authentication state is unknown or has already been cleared', async () => {
    await RouterTestingHarness.create('/discovery');
    expect(auth.isAuthenticated()).toBe(false);
    const operation = api.get('/settings').catch((error) => error);
    http.expectOne('/api/settings').flush({}, { status: 401, statusText: 'Unauthorized' });
    await operation;
    expect(router.url).toBe('/login');
    await router.navigate(['/discovery']);
    const nextOperation = api.get('/addons').catch((error) => error);
    http.expectOne('/api/addons').flush({}, { status: 401, statusText: 'Unauthorized' });
    await nextOperation;
    expect(router.url).toBe('/login');
  });

  it('shares one redirect across simultaneous authorization failures', async () => {
    await authenticate();
    await RouterTestingHarness.create('/discovery');
    const navigate = vi.spyOn(router, 'navigate');
    const first = api.get('/settings').catch((error) => error);
    const second = api.get('/providers').catch((error) => error);
    http.expectOne('/api/settings').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/providers').flush({}, { status: 403, statusText: 'Forbidden' });
    await Promise.all([first, second]);
    expect(router.url).toBe('/login');
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it.each(['login', 'logout', 'register', 'registration'])(
    'preserves errors from public auth endpoint %s',
    async (endpoint) => {
      await authenticate();
      await RouterTestingHarness.create('/discovery');
      const operation = firstValueFrom(
        TestBed.inject(HttpClient).post('/api/auth/' + endpoint + '?test=1', {}),
      ).catch((error) => error);
      http
        .expectOne('/api/auth/' + endpoint + '?test=1')
        .flush({}, { status: 401, statusText: 'Unauthorized' });
      expect(await operation).toMatchObject({ status: 401 });
      expect(router.url).toBe('/discovery');
      expect(auth.isAuthenticated()).toBe(true);
    },
  );

  it.each([400, 500, 502])(
    'preserves the session for application or upstream errors with HTTP %i',
    async (status) => {
      await authenticate();
      await RouterTestingHarness.create('/discovery');
      const operation = api.post('/addons/search', {}).catch((error) => error);
      http
        .expectOne('/api/addons/search')
        .flush({ error: 'KZ.ADDON_UNAUTHORIZED' }, { status, statusText: 'Request failed' });
      expect(await operation).toMatchObject({ status });
      expect(router.url).toBe('/discovery');
      expect(auth.isAuthenticated()).toBe(true);
    },
  );

  it('matches protected absolute URLs using their pathname', async () => {
    await authenticate();
    await RouterTestingHarness.create('/discovery');
    const url = 'http://localhost/api/discovery/details?returnUrl=/api/auth/login';
    const operation = firstValueFrom(TestBed.inject(HttpClient).post(url, {})).catch(
      (error) => error,
    );
    http.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });
    await operation;
    expect(router.url).toBe('/login');
  });

  it('keeps non-API authorization failures in their own flow', async () => {
    await authenticate();
    await RouterTestingHarness.create('/discovery');
    const operation = firstValueFrom(TestBed.inject(HttpClient).get('/api-other/resource')).catch(
      (error) => error,
    );
    http.expectOne('/api-other/resource').flush({}, { status: 401, statusText: 'Unauthorized' });
    await operation;
    expect(router.url).toBe('/discovery');
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('checks the session after a download-stream failure and closes the stream on redirect', async () => {
    await authenticate();
    const harness = await RouterTestingHarness.create('/discovery');
    const navigation = harness.navigateByUrl('/downloads', DownloadsPage);
    await vi.waitFor(() => http.expectOne('/api/downloads').flush([]));
    await navigation;
    await vi.waitFor(() => expect(TestEventSource.instances).toHaveLength(1));
    const stream = TestEventSource.instances[0];
    stream.onerror!();
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    http.expectOne('/api/downloads').flush({}, { status: 401, statusText: 'Unauthorized' });
    await vi.waitFor(() => expect(router.url).toBe('/login'));
    expect(stream.close).toHaveBeenCalledOnce();
  });

  it('retains the session and reconnect feedback during a network interruption', async () => {
    await authenticate();
    const harness = await RouterTestingHarness.create('/discovery');
    const navigation = harness.navigateByUrl('/downloads', DownloadsPage);
    await vi.waitFor(() => http.expectOne('/api/downloads').flush([]));
    const page = await navigation;
    await vi.waitFor(() => expect(TestEventSource.instances).toHaveLength(1));
    TestEventSource.instances[0].onerror!();
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    http.expectOne('/api/downloads').error(new ProgressEvent('error'));
    await harness.fixture.whenStable();
    expect(page.error()).toBe('KZ.RECONNECTING');
    expect(auth.isAuthenticated()).toBe(true);
    expect(router.url).toBe('/downloads');
  });

  it('confirms a lost download stream before navigating offline and closes it on destruction', async () => {
    await authenticate();
    const harness = await RouterTestingHarness.create('/discovery');
    const navigation = harness.navigateByUrl('/downloads', DownloadsPage);
    await vi.waitFor(() => http.expectOne('/api/downloads').flush([]));
    await navigation;
    await vi.waitFor(() => expect(TestEventSource.instances).toHaveLength(1));
    const stream = TestEventSource.instances[0];
    stream.onerror!();
    http.expectOne('/api/downloads').error(new ProgressEvent('error'));
    expect(router.url).toBe('/downloads');
    http.expectOne('/api/auth/registration').flush({}, { status: 503, statusText: 'Unavailable' });
    await vi.waitFor(() => expect(router.url).toBe('/offline?returnUrl=%2Fdownloads'));
    expect(stream.close).toHaveBeenCalledOnce();
  });

  it('avoids creating a stream after the initial request redirects away from downloads', async () => {
    await authenticate();
    const harness = await RouterTestingHarness.create('/discovery');
    const navigation = harness.navigateByUrl('/downloads');
    await vi.waitFor(() =>
      http.expectOne('/api/downloads').flush({}, { status: 401, statusText: 'Unauthorized' }),
    );
    await navigation;
    await vi.waitFor(() => expect(router.url).toBe('/login'));
    await harness.fixture.whenStable();
    expect(TestEventSource.instances).toHaveLength(0);
  });

  it('refreshes progress over HTTP when stream events stop and clears the watchdog on destruction', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const now = vi.spyOn(Date, 'now');
    try {
      await authenticate();
      const harness = await RouterTestingHarness.create('/discovery');
      const navigation = harness.navigateByUrl('/downloads', DownloadsPage);
      await vi.waitFor(() => http.expectOne('/api/downloads').flush([]));
      const page = await navigation;
      await vi.waitFor(() => expect(TestEventSource.instances).toHaveLength(1));
      now.mockReturnValue(Date.now() + 5000);
      vi.advanceTimersByTime(1500);
      const refresh = http.expectOne('/api/downloads');
      vi.advanceTimersByTime(4500);
      http.expectNone('/api/downloads');
      refresh.flush([
        {
          request: {
            id: 'progress',
            state: 'TRANSFERRING',
            content: { title: 'Fixture' },
            provider: 'TORBOX',
            organization: { destinationName: 'HDD', storageRoot: '/fixture' },
            execution: { generation: 1, availableFiles: [] },
            progress: { completedBytes: 1048576, totalBytes: 4194304, speedBytesPerSecond: 524288 },
          },
        },
      ]);
      await harness.fixture.whenStable();
      expect(page.items()[0].request.progress.completedBytes).toBe(1048576);
      harness.fixture.destroy();
      vi.advanceTimersByTime(6000);
      http.expectNone('/api/downloads');
      expect(TestEventSource.instances[0].close).toHaveBeenCalledOnce();
    } finally {
      now.mockRestore();
      vi.useRealTimers();
    }
  });
});
