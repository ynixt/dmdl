import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { ApiAvailabilityService, ApiUnavailableReason } from '../../core/api-availability.service';
import { AuthService } from '../../core/auth/auth.service';
import { OfflinePageComponent } from './offline.page';

describe('Offline page', () => {
  let http: HttpTestingController;
  let availability: ApiAvailabilityService;
  let fixture: ComponentFixture<OfflinePageComponent>;
  let returnUrl: string;

  beforeEach(() => {
    vi.useFakeTimers();
    returnUrl = '/downloads?filter=active';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: () => returnUrl } } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    availability = TestBed.inject(ApiAvailabilityService);
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });
  afterEach(() => {
    fixture?.destroy();
    http.verify();
    vi.useRealTimers();
  });

  function render() {
    fixture = TestBed.createComponent(OfflinePageComponent);
    fixture.detectChanges();
  }
  function fail(state?: string) {
    http
      .expectOne('/api/auth/registration')
      .flush(state ? { code: 'BACKEND_UNAVAILABLE', state } : {}, {
        status: 503,
        statusText: 'Unavailable',
      });
    fixture.detectChanges();
  }

  it.each([
    ['starting', false, 'STARTING'],
    ['not-responding', false, 'NOT_RESPONDING'],
    ['unreachable', false, 'UNREACHABLE'],
    ['unreachable', true, 'CONNECTION_LOST'],
  ] as const)(
    'renders reason %s with previous contact %s',
    (reason: ApiUnavailableReason, reached, key) => {
      availability.reason.set(reason);
      availability.reached.set(reached);
      render();
      expect(fixture.nativeElement.textContent).toContain('PAGES.OFFLINE.' + key);
      expect(fixture.nativeElement.querySelector('p-progress-spinner')).not.toBeNull();
      fail(reason === 'starting' ? 'starting' : reason === 'not-responding' ? 'ready' : undefined);
      expect(fixture.nativeElement.textContent).toContain('PAGES.OFFLINE.' + key);
      expect(fixture.nativeElement.textContent).not.toContain('BACKEND_UNAVAILABLE');
    },
  );

  it('backs off at 1, 2, 4, 8, then 10 seconds and caps the interval', () => {
    render();
    for (const delay of [1, 2, 4, 8, 10, 10]) {
      fail();
      expect(fixture.componentInstance.remaining()).toBe(delay);
      vi.advanceTimersByTime(delay * 1000 - 1);
      http.expectNone('/api/auth/registration');
      vi.advanceTimersByTime(1);
    }
    fail();
  });

  it('retries immediately and resets the interval', () => {
    render();
    fail();
    vi.advanceTimersByTime(1000);
    fail();
    expect(fixture.componentInstance.remaining()).toBe(2);
    fixture.nativeElement.querySelector('button').click();
    fail();
    expect(fixture.componentInstance.remaining()).toBe(1);
  });

  it.each([
    '/downloads?filter=active',
    '/',
    '//evil.example',
    'https://evil.example',
    '/\\evil.example',
    '/offline',
  ])('recovers safely from returnUrl %s', (url) => {
    returnUrl = url;
    const invalidate = vi.spyOn(TestBed.inject(AuthService), 'invalidateSession');
    render();
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    expect(invalidate).toHaveBeenCalledOnce();
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith(
      url === '/downloads?filter=active' ? url : '/',
    );
  });

  it('cancels requests and timers when destroyed', () => {
    render();
    const request = http.expectOne('/api/auth/registration');
    fixture.destroy();
    expect(request.cancelled).toBe(true);
    vi.advanceTimersByTime(30000);
    http.expectNone('/api/auth/registration');
  });

  it('stops retrying connectivity when the API answers a different error', () => {
    render();
    http
      .expectOne('/api/auth/registration')
      .flush({}, { status: 500, statusText: 'Application error' });
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith(returnUrl);
  });

  it('has matching translation keys in both languages', async () => {
    const english = await import('../../../../public/i18n/en-US.json');
    const portuguese = await import('../../../../public/i18n/pt-BR.json');
    expect(Object.keys(english.PAGES.OFFLINE).sort()).toEqual(
      Object.keys(portuguese.PAGES.OFFLINE).sort(),
    );
  });
});
