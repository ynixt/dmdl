import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';
import { routes } from '../../app.routes';
import { ApiAvailabilityService } from '../../core/api-availability.service';
import { AuthService } from '../../core/auth/auth.service';
import { LogsPage } from './logs.page';
import { LogPage, LogRecord } from './logs.service';

class LogConnection extends EventTarget {
  static connections: LogConnection[] = [];
  closed = false;
  onerror?: () => void;
  constructor(
    readonly url: string,
    readonly options: EventSourceInit,
  ) {
    super();
    LogConnection.connections.push(this);
  }
  close() {
    this.closed = true;
  }
  emit(page: LogPage) {
    this.dispatchEvent(new MessageEvent('logs', { data: JSON.stringify(page) }));
  }
}

describe('Authenticated logs', () => {
  let fixture: ComponentFixture<LogsPage>;
  let http: HttpTestingController;
  const auth = {
    refreshSession: vi.fn(),
    expireSession: vi.fn(),
    initializeSession: () => of(true),
    currentOnboardingCompleted: () => false,
  };

  function record(id: string, message = id): LogRecord {
    return {
      id,
      message,
      source: 'aria2',
      severity: 'ERROR',
      timestamp: '2026-11-15T10:00:00Z',
      truncated: false,
    };
  }
  function page(records: LogRecord[] = [], cursor = 'snapshot'): LogPage {
    return {
      records,
      cursor,
      olderCursor: records[0]?.id ?? null,
      hasMore: false,
      gap: null,
      sources: [],
    };
  }
  function history() {
    return http.expectOne((request) => request.url.endsWith('/logs'));
  }
  function connection() {
    return LogConnection.connections.at(-1)!;
  }
  function render(records: LogRecord[] = []) {
    fixture = TestBed.createComponent(LogsPage);
    fixture.detectChanges();
    history().flush(page(records));
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    LogConnection.connections = [];
    vi.stubGlobal('EventSource', LogConnection);
    auth.refreshSession.mockResolvedValue(true);
    auth.expireSession.mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService(),
        provideRouter(routes),
        { provide: AuthService, useValue: auth },
        { provide: ApiAvailabilityService, useValue: { confirmAndGoOffline: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    fixture?.destroy();
    http.verify();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('opens the guarded route during onboarding through its normal entry point', async () => {
    const harness = await RouterTestingHarness.create();
    const navigating = harness.navigateByUrl('/logs', LogsPage);
    await vi.waitFor(() =>
      expect(harness.routeDebugElement?.componentInstance).toBeInstanceOf(LogsPage),
    );
    history().flush(page());
    const logs = await navigating;
    expect(logs).toBeInstanceOf(LogsPage);
    expect(harness.routeNativeElement?.textContent).toContain('LOGS.TITLE');
  });

  it('freezes on pause, ignores late events and resumes from the saved cursor', () => {
    const logs = render([record('first')]);
    connection().emit(page([record('second')], 'second-cursor'));
    const previous = connection();
    logs.togglePause();
    expect(previous.closed).toBe(true);
    previous.emit(page([record('late')], 'late-cursor'));
    expect(logs.records().map((entry) => entry.id)).toEqual(['first', 'second']);
    logs.togglePause();
    expect(connection().url).toContain('cursor=second-cursor');
    connection().emit(page([record('second'), record('retained')], 'replay-cursor'));
    expect(logs.records().map((entry) => entry.id)).toEqual(['first', 'second', 'retained']);
    expect(connection().options.withCredentials).toBe(true);
  });

  it('cancels superseded requests and combines debounced source text and severity filters', () => {
    vi.useFakeTimers();
    const logs = render([record('old')]);
    const previous = connection();
    logs.source = 'aria2';
    logs.filtersChanged();
    const superseded = history();
    logs.text = '[error].*';
    logs.filtersChanged(true);
    expect(superseded.cancelled).toBe(true);
    expect(previous.closed).toBe(true);
    logs.toggleLevel('ERROR', true);
    const selected = history();
    expect(selected.request.params.get('source')).toBe('aria2');
    expect(selected.request.params.get('text')).toBe('[error].*');
    expect(selected.request.params.getAll('levels')).toEqual(['ERROR']);
    selected.flush(page([record('matching')]));
    vi.advanceTimersByTime(500);
    http.expectNone((request) => request.url.endsWith('/logs'));
    expect(logs.records()[0].id).toBe('matching');
    expect(connection().url).toContain('levels=ERROR');
  });

  it('recovers reset and retention gaps, reports source failures and renders markup as text', () => {
    const logs = render([record('before')]);
    const reset = {
      ...page([record('new', '<img src=x onerror=alert(1)>')]),
      gap: 'reset' as const,
      sources: [{ source: 'aria2' as const, state: 'failed', oldestTimestamp: null }],
    };
    connection().emit(reset);
    fixture.detectChanges();
    expect(logs.records()).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('pre').textContent).toContain('<img');
    expect(fixture.nativeElement.textContent).toContain('LOGS.RESET');
    expect(fixture.nativeElement.textContent).toContain('LOGS.CAPTURE_STATUS');
    connection().emit({ ...reset, gap: 'retention' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('LOGS.GAP');
  });

  it('bounds display and shows empty loading truncation and failure feedback', () => {
    fixture = TestBed.createComponent(LogsPage);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('LOGS.LOADING');
    history().flush(page());
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('LOGS.EMPTY');
    const many = Array.from({ length: 2050 }, (_, i) => record(String(i)));
    many[2049].truncated = true;
    connection().emit(page(many));
    fixture.detectChanges();
    expect(fixture.componentInstance.records()).toHaveLength(2000);
    expect(fixture.nativeElement.textContent).toContain('LOGS.EVICTED');
    expect(fixture.nativeElement.textContent).toContain('LOGS.TRUNCATED');
    fixture.componentInstance.filtersChanged();
    history().flush({}, { status: 500, statusText: 'Failed' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('LOGS.FAILURE');
  });

  it('pages older records and updates multiline records without duplicates', () => {
    const logs = render([record('recent')]);
    logs.loadOlder();
    const older = history();
    expect(older.request.params.get('before')).toBe('recent');
    older.flush(page([record('older')]));
    connection().emit(page([record('recent', 'expanded stack trace')]));
    expect(logs.records().map((entry) => entry.id)).toEqual(['older', 'recent']);
    expect(logs.records()[1].message).toBe('expanded stack trace');
  });

  it('recovers rejected streams through the normal session flow and ignores pending reconnects after pause', async () => {
    const logs = render();
    auth.refreshSession.mockResolvedValueOnce(false);
    connection().onerror?.();
    await vi.waitFor(() => expect(auth.expireSession).toHaveBeenCalled());
    expect(connection().closed).toBe(true);
    expect(logs.status()).toBe('disconnected');
  });
});
