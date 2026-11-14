import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { DebridApi, DownloadStatus } from '../../services/debrid-api.service';
import { DownloadsPage } from './downloads.page';

describe('Completed download history actions', () => {
  let fixture: ComponentFixture<DownloadsPage>;
  let records: DownloadStatus[];
  const api = {
    base: '/api',
    get: vi.fn(),
    delete: vi.fn(),
    post: vi.fn(),
  };

  function download(id: string, state: string): DownloadStatus {
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
        createdAt: '2026-11-12T12:00:00Z',
        organization: {
          storageRoot: 'C:/Movies',
          destinationId: 'movies',
          destinationName: 'Movies',
        },
        progress: { completedBytes: 1024, totalBytes: 1024, speedBytesPerSecond: 0 },
        execution: { generation: 1, availableFiles: [], cancellationCleanupCompleted: true },
      },
    };
  }

  function buttons(label: string): HTMLButtonElement[] {
    return [...fixture.nativeElement.querySelectorAll('button')].filter(
      (button) => button.textContent.trim() === label,
    );
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(DownloadsPage);
    fixture.detectChanges();
    await fixture.whenStable();
    await vi.waitFor(() => expect(fixture.componentInstance.busy()).toBe(false));
    fixture.detectChanges();
  }

  beforeEach(() => {
    vi.resetAllMocks();
    records = [
      download('completed-one', 'COMPLETED'),
      download('completed-two', 'COMPLETED'),
      download('active', 'TRANSFERRING'),
      download('failed', 'FAILED'),
      download('cancelled', 'CANCELLED'),
    ];
    api.get.mockImplementation(async (path: string) =>
      path.endsWith('/speed-history') ? [] : [...records],
    );
    api.delete.mockImplementation(async (path: string) => {
      records = records.filter(
        (item) =>
          !['COMPLETED', 'CANCELLED'].includes(item.request.state) ||
          (path !== '/downloads/completed' && path !== '/downloads/' + item.request.id),
      );
    });
    vi.stubGlobal(
      'EventSource',
      class {
        addEventListener = vi.fn();
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
  });

  it('shows current connection count and individual speeds using adaptive units', async () => {
    const item = download('active', 'TRANSFERRING');
    item.request.progress.connections = 4;
    item.request.progress.connectionSpeedsBytesPerSecond = [512, 1536, 1310719, 0];
    records = [item];
    await render();
    expect(fixture.nativeElement.querySelector('.open-connections').textContent).toContain('4');
    const rows = [
      ...fixture.nativeElement.querySelectorAll('.connection-speeds li'),
    ] as HTMLElement[];
    expect(rows).toHaveLength(4);
    expect(rows.map((row) => row.querySelector('strong')?.textContent?.trim())).toEqual([
      '512 B/s',
      '1.5 KiB/s',
      '1.25 MiB/s',
      '0 B/s',
    ]);
    expect(rows[0].textContent).toContain('KZ.CONNECTION 1');
    expect(rows[3].textContent).toContain('KZ.CONNECTION 4');
    item.request.progress.connections = 1;
    item.request.progress.connectionSpeedsBytesPerSecond = [2048];
    await fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.connection-speeds li')).toHaveLength(1);
    expect(
      fixture.nativeElement.querySelector('.connection-speeds strong').textContent.trim(),
    ).toBe('2 KiB/s');
  });

  it('hides missing connection metrics and suppresses stale details outside transferring', async () => {
    const item = download('active', 'TRANSFERRING');
    records = [item];
    await render();
    expect(fixture.nativeElement.querySelector('.open-connections')).toBeNull();
    item.request.progress.connections = 2;
    item.request.progress.connectionSpeedsBytesPerSecond = [512, 512];
    for (const state of ['PAUSED', 'COMPLETED', 'CANCELLED', 'FAILED']) {
      item.request.state = state;
      await fixture.componentInstance.reload();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.open-connections')).toBeNull();
      expect(fixture.nativeElement.querySelector('.connection-speeds')).toBeNull();
    }
    item.request.state = 'TRANSFERRING';
    fixture.componentInstance.cancellingIds.set(new Set(['active']));
    await fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.connection-speeds')).toBeNull();
  });

  it('offers removal on completed and cancelled rows and refreshes after an individual removal', async () => {
    await render();
    expect(buttons('KZ.REMOVE_FROM_HISTORY')).toHaveLength(3);
    fixture.componentInstance.history.set({
      'completed-one': [{ id: 'event', occurredAt: '', state: 'COMPLETED' }],
      active: [{ id: 'active-event', occurredAt: '', state: 'TRANSFERRING' }],
    });
    buttons('KZ.REMOVE_FROM_HISTORY')[0].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.delete).toHaveBeenCalledExactlyOnceWith('/downloads/completed-one');
    expect(fixture.componentInstance.items().map((item) => item.request.id)).toEqual([
      'completed-two',
      'active',
      'failed',
      'cancelled',
    ]);
    expect(Object.keys(fixture.componentInstance.history())).toEqual(['active']);
    expect(buttons('KZ.REMOVE_FROM_HISTORY')).toHaveLength(2);
  });

  it('clears completed and cancelled entries together and disables the bulk action afterward', async () => {
    await render();
    buttons('KZ.REMOVE_ALL_COMPLETED')[0].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.delete).toHaveBeenCalledExactlyOnceWith('/downloads/completed');
    expect(fixture.componentInstance.items().map((item) => item.request.id)).toEqual([
      'active',
      'failed',
    ]);
    expect(buttons('KZ.REMOVE_FROM_HISTORY')).toHaveLength(0);
    expect(buttons('KZ.REMOVE_ALL_COMPLETED')[0].disabled).toBe(true);
  });

  it.each([true, false])(
    'removes an individual cancelled entry with cleanup completion %s',
    async (cleanupCompleted) => {
      const cancelled = download('cancelled', 'CANCELLED');
      cancelled.request.execution.cancellationCleanupCompleted = cleanupCompleted;
      cancelled.request.progress.allocation = {
        allocatedBytes: 512,
        totalBytes: 1024,
        secondsRemaining: 5,
      };
      records = [cancelled];
      await render();
      expect(fixture.nativeElement.querySelector('.transfer-metrics')).toBeNull();
      expect(fixture.nativeElement.querySelector('[role="progressbar"]')).toBeNull();
      expect(fixture.nativeElement.querySelector('.allocation-percentage')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain('MiB/s');
      expect(fixture.nativeElement.querySelector('h2').textContent).toBe('cancelled');
      expect(buttons('KZ.REMOVE_FROM_HISTORY')).toHaveLength(1);
      expect(buttons('KZ.REMOVE_ALL_COMPLETED')[0].disabled).toBe(false);
      buttons('KZ.REMOVE_FROM_HISTORY')[0].click();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(api.delete).toHaveBeenCalledExactlyOnceWith('/downloads/cancelled');
      expect(fixture.componentInstance.items()).toHaveLength(0);
      expect(buttons('KZ.REMOVE_ALL_COMPLETED')[0].disabled).toBe(true);
    },
  );

  it('blocks duplicate submissions and disables row and bulk actions during removal', async () => {
    await render();
    let complete!: () => void;
    api.delete.mockImplementationOnce(() => new Promise<void>((resolve) => (complete = resolve)));
    const removal = fixture.componentInstance.removeFromHistory('completed-one');
    await fixture.componentInstance.removeFromHistory();
    fixture.detectChanges();
    expect(api.delete).toHaveBeenCalledTimes(1);
    expect(buttons('KZ.REMOVE_FROM_HISTORY').every((button) => button.disabled)).toBe(true);
    expect(buttons('KZ.REMOVE_ALL_COMPLETED')[0].disabled).toBe(true);
    complete();
    await removal;
    expect(fixture.componentInstance.busy()).toBe(false);
  });

  it('keeps entries visible and reports failed removal so the user can retry', async () => {
    await render();
    api.delete.mockRejectedValueOnce({ error: { error: 'KZ.REQUEST_FAILED' } });
    buttons('KZ.REMOVE_ALL_COMPLETED')[0].click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.items()).toHaveLength(5);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'KZ.REQUEST_FAILED',
    );
    expect(buttons('KZ.REMOVE_ALL_COMPLETED')[0].disabled).toBe(false);
    buttons('KZ.REMOVE_ALL_COMPLETED')[0].click();
    await fixture.whenStable();
    expect(fixture.componentInstance.items()).toHaveLength(2);
  });
  it('shows allocation counters and estimated times with gigabyte file sizes', async () => {
    const item = download('allocating', 'TRANSFERRING');
    item.request.execution.fileAllocationOverride = 'PREALLOC';
    item.request.progress = {
      completedBytes: 0,
      totalBytes: 4_000_000_000,
      speedBytesPerSecond: 0,
      allocation: { allocatedBytes: 500_000_000, totalBytes: 4_000_000_000, secondsRemaining: 7 },
    };
    records = [item];
    await render();
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain(
      'KZ.ALLOCATING_SPACE',
    );
    expect(fixture.nativeElement.textContent).toContain('GB');
    expect(fixture.nativeElement.textContent).toContain('KZ.ALLOCATION_FALLBACK');
    expect(fixture.nativeElement.textContent).toContain('7s');
    expect(fixture.nativeElement.querySelector('.allocation-percentage').textContent).toBe('12.5%');
    expect(fixture.componentInstance.percentage(item.request)).toBe(0);
    expect(
      fixture.nativeElement.querySelector('[role="progressbar"]').getAttribute('aria-valuenow'),
    ).toBe('0');
    item.request.progress.allocation = undefined;
    item.request.progress.completedBytes = 1_000_000_000;
    item.request.progress.speedBytesPerSecond = 100_000_000;
    await fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('KZ.DOWNLOAD_ETA');
    expect(fixture.nativeElement.textContent).toContain('30s');
    expect(fixture.componentInstance.byteUnit(999_999_999)).toBe('MiB');
    expect(fixture.componentInstance.byteUnit(1_000_000_000)).toBe('GB');
    expect(fixture.componentInstance.duration(3661)).toBe('1h 1min');
  });
  it('computes download percentage using downloaded bytes with at most two decimals', async () => {
    await render();
    const item = download('active', 'TRANSFERRING').request;
    item.progress.allocation = { allocatedBytes: 1000, totalBytes: 1000 };
    for (const [completed, total, expected] of [
      [1, 3, 33.33],
      [2, 3, 66.67],
      [0, 100, 0],
      [100, 100, 100],
      [101, 100, 100],
      [-1, 100, 0],
      [1, 0, 0],
    ]) {
      item.progress.completedBytes = completed;
      item.progress.totalBytes = total;
      expect(fixture.componentInstance.percentage(item)).toBe(expected);
    }
    item.progress.totalBytes = undefined;
    expect(fixture.componentInstance.percentage(item)).toBe(0);
  });
  it('shows calculating for a null allocation ETA returned by the backend', async () => {
    const item = download('allocating', 'TRANSFERRING');
    item.request.progress = {
      completedBytes: 0,
      totalBytes: 1024,
      speedBytesPerSecond: 0,
      allocation: { allocatedBytes: 128, totalBytes: 1024, secondsRemaining: null },
    };
    records = [item];
    await render();
    expect(fixture.nativeElement.textContent).toContain('KZ.CALCULATING_ETA');
    expect(fixture.nativeElement.textContent).not.toContain('0s');
  });
  it('shows preparation and calculating feedback before transfer measurements are available', async () => {
    const item = download('preparing', 'TRANSFERRING');
    item.request.progress = { completedBytes: 0, speedBytesPerSecond: 0 };
    records = [item];
    await render();
    expect(fixture.nativeElement.textContent).toContain('KZ.PREPARING_TRANSFER');
    expect(fixture.nativeElement.textContent).toContain('KZ.CALCULATING_ETA');
  });
  it('keeps the cancellation spinner until asynchronous disk cleanup is completed', async () => {
    const item = download('active', 'TRANSFERRING');
    records = [item];
    await render();
    let complete!: () => void;
    api.post.mockImplementationOnce(() => new Promise<void>((resolve) => (complete = resolve)));
    const cancellation = fixture.componentInstance.control(item.request, 'CANCEL');
    fixture.detectChanges();
    expect(buttons('KZ.CANCELLING')[0].disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.transfer-metrics')).toBeNull();
    item.request.state = 'CANCELLED';
    item.request.execution.cancellationCleanupCompleted = false;
    complete();
    await cancellation;
    fixture.detectChanges();
    expect(buttons('KZ.CANCELLING')[0].disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('KZ.STATE_CANCELLING');
    item.request.execution.cancellationCleanupCompleted = true;
    await fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(buttons('KZ.CANCELLING')).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('KZ.STATE_CANCELLED');
    expect(fixture.nativeElement.querySelector('.transfer-metrics')).toBeNull();
  });
  it('restores transfer metrics when the cancellation request fails', async () => {
    const item = download('active', 'TRANSFERRING');
    records = [item];
    await render();
    api.post.mockRejectedValueOnce({ error: { error: 'KZ.REQUEST_FAILED' } });
    await fixture.componentInstance.control(item.request, 'CANCEL');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.transfer-metrics')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="progressbar"]')).not.toBeNull();
    expect(fixture.componentInstance.cancelling(item.request)).toBe(false);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'KZ.REQUEST_FAILED',
    );
  });
});
