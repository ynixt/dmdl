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

  function record(id: string, message = id, timestamp = '2026-11-15T10:00:00Z'): LogRecord {
    return {
      id,
      message,
      source: 'aria2',
      severity: 'ERROR',
      timestamp,
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

  it('sorts by timestamp in both directions through the selector without reconnecting', async () => {
    const logs = render([
      record('recent', 'recent', '2026-11-15T10:02:00Z'),
      record('oldest', 'oldest', '2026-11-15T10:00:00Z'),
      record('middle', 'middle', '2026-11-15T10:01:00Z'),
    ]);
    await fixture.whenStable();
    const messages = () =>
      Array.from(fixture.nativeElement.querySelectorAll('pre') as NodeListOf<HTMLElement>).map(
        (element) => element.textContent,
      );
    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    expect(select.value).toBe('asc');
    expect(messages()).toEqual(['oldest', 'middle', 'recent']);

    select.value = 'desc';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(messages()).toEqual(['recent', 'middle', 'oldest']);
    expect(logs.records().map((entry) => entry.id)).toEqual(['recent', 'oldest', 'middle']);
    expect(LogConnection.connections).toHaveLength(1);
    expect(connection().closed).toBe(false);
    http.expectNone((request) => request.url.endsWith('/logs'));

    select.value = 'asc';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(messages()).toEqual(['oldest', 'middle', 'recent']);
  });

  it('keeps descending order for history, live updates, pause and filter changes', () => {
    const logs = render([record('recent', 'recent', '2026-11-15T10:01:00Z')]);
    logs.changeSortOrder('desc');
    logs.loadOlder();
    history().flush(page([record('older')]));
    connection().emit(
      page([
        record('newest', 'newest', '2026-11-15T10:02:00Z'),
        record('recent', 'expanded', '2026-11-15T10:01:00Z'),
      ]),
    );
    expect(logs.sortedRecords().map((entry) => entry.id)).toEqual(['newest', 'recent', 'older']);
    expect(logs.sortedRecords()[1].message).toBe('expanded');

    logs.togglePause();
    logs.changeSortOrder('asc');
    expect(logs.sortedRecords().map((entry) => entry.id)).toEqual(['older', 'recent', 'newest']);
    logs.changeSortOrder('desc');
    logs.filtersChanged();
    history().flush(
      page([
        record('filtered-old'),
        record('filtered-new', 'filtered-new', '2026-11-15T10:03:00Z'),
      ]),
    );
    expect(logs.sortedRecords().map((entry) => entry.id)).toEqual(['filtered-new', 'filtered-old']);
  });

  it.each(['asc', 'desc'] as const)(
    'follows live records only near the newest edge in %s order',
    (order) => {
      const frames: FrameRequestCallback[] = [];
      vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
        frames.push(callback);
        return frames.length;
      });
      const logs = render([record('first')]);
      logs.changeSortOrder(order);
      frames.splice(0).forEach((callback) => callback(0));
      const viewport: HTMLElement = fixture.nativeElement.querySelector('.logs-records');
      Object.defineProperties(viewport, {
        scrollHeight: { value: 1000, configurable: true },
        clientHeight: { value: 200 },
      });
      viewport.scrollTop = order === 'asc' ? 800 : 0;

      connection().emit(page([record('second')]));
      fixture.detectChanges();
      Object.defineProperty(viewport, 'scrollHeight', { value: 1200 });
      expect(frames.length).toBeGreaterThan(0);
      frames.splice(0).forEach((callback) => callback(0));
      expect(viewport.scrollTop).toBe(order === 'asc' ? 1200 : 0);

      viewport.scrollTop = 400;
      connection().emit(page([record('third')]));
      fixture.detectChanges();
      frames.splice(0).forEach((callback) => callback(0));
      expect(viewport.scrollTop).toBe(400);
    },
  );

  it('recovers rejected streams through the normal session flow and ignores pending reconnects after pause', async () => {
    const logs = render();
    auth.refreshSession.mockResolvedValueOnce(false);
    connection().onerror?.();
    await vi.waitFor(() => expect(auth.expireSession).toHaveBeenCalled());
    expect(connection().closed).toBe(true);
    expect(logs.status()).toBe('disconnected');
  });
});
