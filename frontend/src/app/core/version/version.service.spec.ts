import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { credentialsInterceptor } from '../auth/credentials.interceptor';
import { AuthService } from '../auth/auth.service';
import { unauthorizedInterceptor } from '../auth/unauthorized.interceptor';
import { availabilityInterceptor } from '../availability.interceptor';
import { toBuildInfo, VersionService } from './version.service';

const SHA = '2f5cac4e0d1b2a3c4d5e6f708192a3b4c5d6e7f8';

describe('toBuildInfo', () => {
  it('shows a release tag linking to its release', () => {
    expect(toBuildInfo({ version: 'v0.0.6', commit: SHA })).toEqual({
      label: 'v0.0.6',
      url: 'https://github.com/ynixt/dmdl/releases/tag/v0.0.6',
    });
  });

  it('shows the short commit of an untagged build linking to the full commit', () => {
    expect(toBuildInfo({ version: null, commit: SHA })).toEqual({
      label: '2f5cac4',
      url: `https://github.com/ynixt/dmdl/commit/${SHA}`,
    });
  });

  it('shows "local" for an image built without identity', () => {
    expect(toBuildInfo({ version: null, commit: null })).toEqual({ label: 'local', url: null });
  });

  it.each([
    ['a string', 'v0.0.6'],
    ['null', null],
    ['missing fields', {}],
    ['a non-string version', { version: 6, commit: SHA }],
    ['an unsafe commit', { version: null, commit: 'abc/../x' }],
  ])('shows "dev" for %s', (_, body) => {
    expect(toBuildInfo(body)).toEqual({ label: 'dev', url: null });
  });
});

describe('VersionService', () => {
  let http: HttpTestingController;
  let service: VersionService;
  const expireSession = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    expireSession.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([
            credentialsInterceptor,
            unauthorizedInterceptor,
            availabilityInterceptor,
          ]),
        ),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: () => true, expireSession } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(VersionService);
  });
  afterEach(() => http.verify());

  it('requests /version.json once, only after the first load', () => {
    expect(service.buildInfo()).toBeNull();
    http.expectNone('/version.json');

    service.load();
    service.load();
    const request = http.expectOne('/version.json');
    expect(request.request.withCredentials).toBe(false);
    request.flush({ version: 'v0.0.6', commit: SHA });
    service.load();

    expect(service.buildInfo()?.label).toBe('v0.0.6');
  });

  it.each([
    [404, 'Not Found'],
    [401, 'Unauthorized'],
    [503, 'Service Unavailable'],
  ])('shows "dev" on HTTP %s without going offline or ending the session', (status, statusText) => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    service.load();
    http.expectOne('/version.json').flush('', { status, statusText });

    expect(service.buildInfo()).toEqual({ label: 'dev', url: null });
    expect(navigate).not.toHaveBeenCalled();
    expect(expireSession).not.toHaveBeenCalled();
    http.expectNone('/api/auth/registration');
  });

  it('shows "dev" when the request fails at the network level', () => {
    service.load();
    http.expectOne('/version.json').error(new ProgressEvent('error'));
    expect(service.buildInfo()).toEqual({ label: 'dev', url: null });
  });
});
