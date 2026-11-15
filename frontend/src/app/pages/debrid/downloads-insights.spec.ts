import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import {
  DebridApi,
  DownloadEvent,
  DownloadStatus,
  SpeedSample,
} from '../../services/debrid-api.service';
import { DownloadsPage } from './downloads.page';

describe('Downloads page insights', () => {
  let fixture: ComponentFixture<DownloadsPage>;
  let records: DownloadStatus[];
  let events: Record<string, DownloadEvent[]>;
  let speedHistory: Record<string, SpeedSample[]>;
  let streamListeners: Record<string, (event: MessageEvent) => void>;
  let now: number;
  const api = { base: '/api', get: vi.fn(), delete: vi.fn(), post: vi.fn() };

  function download(id: string, state: string, startedAt?: string): DownloadStatus {
    return {
      request: {
        id,
        state,
        content: {
          identity: {
            sourceId: 'metadata',
            sourceRevision: 1,
            resourceType: 'movie',
            contentId: id,
            mappedIds: {},
          },
          title: id,
          category: 'MOVIE',
        },
        provider: 'TORBOX',
        createdAt: '2026-11-14T14:40:00Z',
        startedAt,
        organization: { storageRoot: 'C:/Movies', destinationId: 'movies', destinationName: 'M' },
        progress: {
          completedBytes: 512,
          totalBytes: 1024,
          speedBytesPerSecond: 2048,
          connections: 8,
        },
        execution: { generation: 1, availableFiles: [], cancellationCleanupCompleted: true },
      },
    };
  }

  function section(id: string): HTMLElement {
    return [...fixture.nativeElement.querySelectorAll('section')].find(
      (element: HTMLElement) => element.querySelector('h2')?.textContent === id,
    );
  }

  function eventsButton(id: string): HTMLButtonElement {
    return section(id).querySelector('button[aria-controls]') as HTMLButtonElement;
  }

  function pushStream(): void {
    streamListeners['downloads']({ data: JSON.stringify(records) } as MessageEvent);
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(DownloadsPage);
    await settle();
    await vi.waitFor(() => expect(fixture.componentInstance.busy()).toBe(false));
    await settle();
  }

  beforeEach(() => {
    vi.resetAllMocks();
    now = Date.parse('2026-11-14T15:00:00Z');
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    events = {};
    speedHistory = {};
    streamListeners = {};
    records = [];
    api.get.mockImplementation(async (path: string) => {
      const [, id, kind] = /^\/downloads\/([^/]+)\/([a-z-]+)$/.exec(path) ?? [];
      if (kind === 'history') return [...(events[id] ?? [])];
      if (kind === 'speed-history') return [...(speedHistory[id] ?? [])];
      return structuredClone(records);
    });
    vi.stubGlobal(
      'EventSource',
      class {
        addEventListener = (name: string, listener: (event: MessageEvent) => void) =>
          (streamListeners[name] = listener);
        close = vi.fn();
      },
    );
    TestBed.configureTestingModule({
      providers: [provideTranslateService(), { provide: DebridApi, useValue: api }],
    });
  });

  afterEach(() => {
    fixture?.destroy();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows the requested time always and the started time only once transferring began', async () => {
    records = [
      download('queued', 'QUEUED'),
      download('active', 'TRANSFERRING', '2026-11-14T14:41:00Z'),
    ];
    await render();
    const queued = section('queued');
    expect(queued.querySelector('.requested-at')?.textContent).toContain('KZ.REQUESTED_AT');
    expect(queued.querySelector('.requested-at time')?.getAttribute('datetime')).toBe(
      '2026-11-14T14:40:00Z',
    );
    expect(queued.querySelector('.started-at')).toBeNull();
    const active = section('active');
    expect(active.querySelector('.started-at')?.textContent).toContain('KZ.STARTED_AT');
    expect(active.querySelector('.started-at time')?.getAttribute('datetime')).toBe(
      '2026-11-14T14:41:00Z',
    );
    expect(active.querySelector('.started-at time')?.textContent?.trim()).not.toBe('');
    expect(queued.querySelector('.completed-at')).toBeNull();
    expect(active.querySelector('.completed-at')).toBeNull();
  });

  it('shows the persisted completion time alongside the requested and started times', async () => {
    const completed = download('completed', 'COMPLETED', '2026-11-14T14:41:00Z');
    completed.request.completedAt = '2026-11-14T14:59:00Z';
    records = [completed];
    await render();
    expect(section('completed').querySelector('.completed-at')?.textContent).toContain(
      'KZ.COMPLETED_AT',
    );
    expect(section('completed').querySelector('.completed-at time')?.getAttribute('datetime')).toBe(
      completed.request.completedAt,
    );
    expect(section('completed').querySelector('.completed-at time')?.textContent?.trim()).not.toBe(
      '',
    );
    expect(section('completed').querySelectorAll('.download-times time')).toHaveLength(3);
  });

  it('merges stream changes while retaining finished downloads and replaces the snapshot on reconnect', async () => {
    records = [download('completed', 'COMPLETED'), download('active', 'TRANSFERRING')];
    await render();
    const completed = fixture.componentInstance.items()[0];
    const update = download('active', 'COMPLETED', '2026-11-14T14:41:00Z');
    update.request.completedAt = '2026-11-14T15:00:00Z';
    const newest = download('newest', 'QUEUED');
    newest.request.createdAt = '2026-11-14T15:01:00Z';
    streamListeners['download-updates']({ data: JSON.stringify([update, newest]) } as MessageEvent);
    await settle();
    expect(fixture.componentInstance.items().map((item) => item.request.id)).toEqual([
      'newest',
      'active',
      'completed',
    ]);
    expect(fixture.componentInstance.items()[2]).toBe(completed);
    expect(section('active').querySelector('.completed-at')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-download-speed-chart')).toBeNull();

    update.notification = { state: 'SENT', attempts: 1 };
    streamListeners['download-updates']({ data: JSON.stringify([update]) } as MessageEvent);
    await settle();
    expect(section('active').querySelector('.notification')?.textContent).toContain(
      'KZ.NOTIFY_SENT',
    );

    fixture.componentInstance.history.set({
      completed: [{ id: 'event', state: 'COMPLETED', occurredAt: '2026-11-14T14:59:00Z' }],
    });
    streamListeners['downloads-removed']({ data: JSON.stringify(['completed']) } as MessageEvent);
    await settle();
    expect(fixture.componentInstance.items().map((item) => item.request.id)).toEqual([
      'newest',
      'active',
    ]);
    expect(fixture.componentInstance.history()['completed']).toBeUndefined();

    records = [newest];
    pushStream();
    await settle();
    expect(fixture.componentInstance.items()).toEqual(records);
  });

  it('toggles the event list and refetches events when reopened', async () => {
    records = [download('paused', 'PAUSED')];
    events['paused'] = [{ id: 'e1', state: 'QUEUED', occurredAt: '2026-11-14T14:40:00Z' }];
    await render();
    const button = eventsButton('paused');
    expect(button.textContent?.trim()).toBe('KZ.HISTORY');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-controls')).toBe('events-paused');

    button.click();
    await vi.waitFor(() => expect(fixture.componentInstance.history()['paused']).toBeDefined());
    await settle();
    const list = fixture.nativeElement.querySelector('#events-paused') as HTMLElement;
    expect(list.querySelectorAll('li')).toHaveLength(1);
    expect(eventsButton('paused').textContent?.trim()).toBe('KZ.HIDE_EVENTS');
    expect(eventsButton('paused').getAttribute('aria-expanded')).toBe('true');

    eventsButton('paused').click();
    await settle();
    expect(fixture.nativeElement.querySelector('#events-paused')).toBeNull();
    expect(eventsButton('paused').textContent?.trim()).toBe('KZ.HISTORY');
    expect(eventsButton('paused').getAttribute('aria-expanded')).toBe('false');

    events['paused'].push({ id: 'e2', state: 'PAUSED', occurredAt: '2026-11-14T14:50:00Z' });
    eventsButton('paused').click();
    await vi.waitFor(() => expect(fixture.componentInstance.history()['paused']).toHaveLength(2));
    await settle();
    expect(fixture.nativeElement.querySelectorAll('#events-paused li')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('#events-paused').textContent).toContain(
      'KZ.STATE_PAUSED',
    );
    expect(
      api.get.mock.calls.filter(([path]) => path === '/downloads/paused/history'),
    ).toHaveLength(2);
  });

  it('keeps a stream completion when an older HTTP refresh finishes later', async () => {
    records = [download('active', 'TRANSFERRING')];
    await render();
    let finishRefresh!: (items: DownloadStatus[]) => void;
    api.get.mockImplementationOnce(
      () => new Promise<DownloadStatus[]>((resolve) => (finishRefresh = resolve)),
    );
    const reload = fixture.componentInstance.reload();
    const completed = download('active', 'COMPLETED');
    completed.request.completedAt = '2026-11-14T15:00:00Z';
    streamListeners['download-updates']({ data: JSON.stringify([completed]) } as MessageEvent);
    finishRefresh(records);
    await reload;
    await settle();
    expect(fixture.componentInstance.items()).toEqual([completed]);
  });

  it('charts only transferring downloads and drops their history when they stop', async () => {
    records = [
      download('active', 'TRANSFERRING', '2026-11-14T14:41:00Z'),
      download('paused', 'PAUSED'),
      download('queued', 'QUEUED'),
    ];
    speedHistory['active'] = [
      { at: '2026-11-14T14:59:00Z', speedBytesPerSecond: 4096, connections: 8 },
      { at: '2026-11-14T14:59:30Z', speedBytesPerSecond: 1024, connections: 1 },
    ];
    await render();
    await vi.waitFor(() =>
      expect(fixture.componentInstance.speedPoints()['active']?.length).toBe(3),
    );
    await settle();
    expect(fixture.nativeElement.querySelectorAll('app-download-speed-chart')).toHaveLength(1);
    expect(section('active').querySelector('app-download-speed-chart')).not.toBeNull();
    const historyCalls = api.get.mock.calls
      .map(([path]) => path as string)
      .filter((path) => path.endsWith('/speed-history'));
    expect(historyCalls).toEqual(['/downloads/active/speed-history']);

    records[0].request.state = 'PAUSED';
    pushStream();
    await settle();
    expect(fixture.nativeElement.querySelector('app-download-speed-chart')).toBeNull();
    expect(fixture.componentInstance.speedHistory()).toEqual({});
  });

  it('appends live stream points and trims them once stored samples cover them', async () => {
    records = [download('active', 'TRANSFERRING', '2026-11-14T14:41:00Z')];
    await render();
    await vi.waitFor(() =>
      expect(fixture.componentInstance.speedHistory()['active']?.tail).toHaveLength(1),
    );
    for (const [offset, speed] of [
      [1_000, 3_000],
      [31_000, 5_000],
    ]) {
      now += offset;
      records[0].request.progress.speedBytesPerSecond = speed;
      records[0].request.progress.connections = 2;
      pushStream();
      await settle();
    }
    const live = fixture.componentInstance.speedHistory()['active'];
    expect(live.stored).toEqual([]);
    expect(live.tail.map((point) => point.speedBytesPerSecond)).toEqual([2048, 3000, 5000]);

    speedHistory['active'] = [
      { at: new Date(now - 10_000).toISOString(), speedBytesPerSecond: 3500, connections: 2 },
    ];
    await fixture.componentInstance.refreshSpeedHistory();
    await settle();
    const refreshed = fixture.componentInstance.speedHistory()['active'];
    expect(refreshed.stored).toEqual([
      { at: now - 10_000, speedBytesPerSecond: 3500, connections: 2 },
    ]);
    expect(refreshed.tail).toEqual([{ at: now, speedBytesPerSecond: 5000, connections: 2 }]);
    expect(
      fixture.componentInstance.speedPoints()['active'].map((point) => point.speedBytesPerSecond),
    ).toEqual([3500, 5000]);
  });
  it('shows a phase instead of the speed while aria2 does not answer, writes to disk or restarts', async () => {
    const waiting = download('waiting', 'TRANSFERRING');
    waiting.request.progress = { ...waiting.request.progress, phase: 'WAITING_FOR_ARIA2' };
    const writing = download('writing', 'TRANSFERRING');
    writing.request.progress = {
      ...writing.request.progress,
      phase: 'WRITING_TO_DISK',
      pendingWriteBytes: 400 * 1048576,
    };
    const restarting = download('restarting', 'TRANSFERRING');
    restarting.request.progress = { ...restarting.request.progress, phase: 'ARIA2_RESTARTING' };
    const moving = download('moving', 'TRANSFERRING');
    moving.request.progress = {
      ...moving.request.progress,
      phase: 'TRANSFERRING',
      speedBytesPerSecond: 35 * 1048576,
    };
    records = [waiting, writing, restarting, moving];
    await render();
    expect(section('waiting').querySelector('.transfer-phase')?.textContent).toContain(
      'KZ.PHASE_WAITING_FOR_ARIA2',
    );
    expect(section('writing').querySelector('.transfer-phase')?.textContent).toContain(
      'KZ.PHASE_WRITING_TO_DISK',
    );
    expect(fixture.componentInstance.pendingMiB(writing.request)).toBe(400);
    expect(section('restarting').querySelector('.transfer-phase')?.textContent).toContain(
      'KZ.PHASE_ARIA2_RESTARTING',
    );
    for (const id of ['waiting', 'writing', 'restarting']) {
      expect(section(id).textContent).not.toContain('MiB/s');
    }
    expect(section('moving').querySelector('.transfer-phase')).toBeNull();
    expect(section('moving').textContent).toContain('35.0 MiB/s');
  });

  it('treats progress not refreshed for more than five seconds as stale', async () => {
    const active = download('active', 'TRANSFERRING');
    active.request.progress = { ...active.request.progress, phase: 'TRANSFERRING' };
    records = [active];
    await render();
    expect(fixture.componentInstance.phase(active.request)).toBe('TRANSFERRING');
    now += 6000;
    fixture.componentInstance.now.set(Date.now());
    await settle();
    expect(fixture.componentInstance.phase(active.request)).toBe('STALE');
    expect(section('active').querySelector('.transfer-phase')?.textContent).toContain(
      'KZ.PHASE_STALE',
    );
    pushStream();
    await settle();
    expect(section('active').querySelector('.transfer-phase')).toBeNull();
  });

  it('estimates the remaining time from the one-minute average so a short freeze changes it gradually', async () => {
    const active = download('active', 'TRANSFERRING');
    active.request.progress = {
      completedBytes: 0,
      totalBytes: 10_000 * 1048576,
      speedBytesPerSecond: 35 * 1048576,
      averageBytesPerSecond: 35 * 1048576,
    };
    const page = TestBed.createComponent(DownloadsPage).componentInstance;
    const before = page.remainingSeconds(active.request)!;
    // 15 s freeze: the observed 10 s speed drops to 0, the 60 s average only to three quarters.
    active.request.progress = {
      ...active.request.progress,
      speedBytesPerSecond: 0,
      averageBytesPerSecond: 26.25 * 1048576,
    };
    const during = page.remainingSeconds(active.request)!;
    expect(before).toBe(286);
    expect(during).toBe(381);
    active.request.progress = { ...active.request.progress, averageBytesPerSecond: 0 };
    expect(page.remainingSeconds(active.request)).toBeUndefined();
  });
});
