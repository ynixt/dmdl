import { CommonModule } from '@angular/common';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  OnInit,
  signal,
  untracked,
  ChangeDetectionStrategy,
} from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { Router } from '@angular/router';
import { ApiAvailabilityService } from '../../core/api-availability.service';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';
import {
  DebridApi,
  Download,
  DownloadEvent,
  DownloadStatus,
  SpeedSample,
  TransferPhase,
} from '../../services/debrid-api.service';
import {
  DownloadSpeedChartComponent,
  samplesToPoints,
  SpeedPoint,
} from './download-speed-chart.component';

/** Stored samples from the backend plus live points received since the newest one. */
interface SpeedHistory {
  stored: SpeedPoint[];
  tail: SpeedPoint[];
}

const SPEED_HISTORY_REFRESH_MS = 30_000;
const MAX_LIVE_TAIL = 2880;
/** Progress not refreshed for this long is no longer presented as current. */
export const STALE_PROGRESS_MS = 5000;

@Component({
  selector: 'app-downloads',
  imports: [
    CommonModule,
    TranslatePipe,
    ButtonModule,
    ProgressBarModule,
    DownloadSpeedChartComponent,
  ],
  templateUrl: './downloads.page.html',
  changeDetection: ChangeDetectionStrategy.Default,
  styleUrl: './debrid.scss',
})
export class DownloadsPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly destroy = inject(DestroyRef);
  private readonly availability = inject(ApiAvailabilityService);
  private readonly router = inject(Router);
  private streamVersion = 0;
  readonly items = signal<DownloadStatus[]>([]);
  readonly hasRemovableHistory = computed(() =>
    this.items().some((item) => this.canRemoveFromHistory(item.request)),
  );
  readonly error = signal('');
  readonly busy = signal(false);
  readonly history = signal<Record<string, DownloadEvent[]>>({});
  readonly cancellingIds = signal<Set<string>>(new Set());
  /** Local receipt time of the latest download list; the browser clock is never compared with server times. */
  readonly receivedAt = signal(Date.now());
  readonly now = signal(Date.now());
  readonly transferringCount = computed(
    () => this.items().filter((item) => item.request.state === 'TRANSFERRING').length,
  );
  readonly speedHistory = signal<Record<string, SpeedHistory>>({});
  readonly speedPoints = computed<Record<string, SpeedPoint[]>>(() =>
    Object.fromEntries(
      Object.entries(this.speedHistory()).map(([id, history]) => [
        id,
        [...history.stored, ...history.tail],
      ]),
    ),
  );
  constructor() {
    effect(() => {
      const items = this.items();
      untracked(() => {
        this.receivedAt.set(Date.now());
        this.syncSpeedHistory(items);
      });
    });
    const refresh = setInterval(() => void this.refreshSpeedHistory(), SPEED_HISTORY_REFRESH_MS);
    const tick = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroy.onDestroy(() => {
      clearInterval(refresh);
      clearInterval(tick);
    });
  }
  async ngOnInit(): Promise<void> {
    await this.reload();
    if (this.destroy.destroyed) return;
    const stream = new EventSource(this.api.base + '/downloads/events', { withCredentials: true });
    let lastEventAt = Date.now();
    let refreshing = false;
    const refresh = async () => {
      if (refreshing || this.destroy.destroyed) return;
      refreshing = true;
      try {
        await this.refreshItems();
        if (!this.destroy.destroyed) {
          if (this.error() === 'KZ.RECONNECTING') this.error.set('');
        }
      } catch {
        // Interceptors handle authentication and confirmed connectivity failures.
      } finally {
        refreshing = false;
      }
    };
    const received = () => {
      lastEventAt = Date.now();
      if (this.error() === 'KZ.RECONNECTING') this.error.set('');
    };
    stream.addEventListener('downloads', (event) => {
      this.streamVersion++;
      this.items.set(JSON.parse((event as MessageEvent).data) as DownloadStatus[]);
      received();
    });
    stream.addEventListener('download-updates', (event) => {
      this.streamVersion++;
      const updates = JSON.parse((event as MessageEvent).data) as DownloadStatus[];
      this.items.update((items) => {
        const merged = new Map(items.map((item) => [item.request.id, item]));
        for (const item of updates) merged.set(item.request.id, item);
        return [...merged.values()].sort(
          (a, b) =>
            b.request.createdAt.localeCompare(a.request.createdAt) ||
            a.request.id.localeCompare(b.request.id),
        );
      });
      received();
    });
    stream.addEventListener('downloads-removed', (event) => {
      this.streamVersion++;
      const removed = new Set(JSON.parse((event as MessageEvent).data) as string[]);
      this.items.update((items) => items.filter((item) => !removed.has(item.request.id)));
      this.history.update((history) =>
        Object.fromEntries(Object.entries(history).filter(([id]) => !removed.has(id))),
      );
      received();
    });
    stream.addEventListener('heartbeat', received);
    stream.onerror = () => {
      this.error.set('KZ.RECONNECTING');
      void this.availability.confirmAndGoOffline(this.router.url);
      void refresh();
    };
    stream.onopen = () => this.error.set('');
    const polling = setInterval(() => {
      if (Date.now() - lastEventAt >= 3000) void refresh();
    }, 1500);
    this.destroy.onDestroy(() => {
      stream.close();
      clearInterval(polling);
    });
  }
  async reload(): Promise<void> {
    await this.run(() => this.refreshItems());
  }
  private async refreshItems(): Promise<void> {
    const version = this.streamVersion;
    const items = await this.api.get<DownloadStatus[]>('/downloads');
    if (!this.destroy.destroyed && version === this.streamVersion) this.items.set(items);
  }
  async removeFromHistory(id?: string): Promise<void> {
    if (this.busy()) return;
    await this.run(async () => {
      await this.api.delete(id ? `/downloads/${id}` : '/downloads/completed');
      await this.refreshItems();
      this.history.update((current) =>
        Object.fromEntries(
          Object.entries(current).filter(([requestId]) =>
            this.items().some((item) => item.request.id === requestId),
          ),
        ),
      );
    });
  }
  canRemoveFromHistory(item: Download): boolean {
    return ['COMPLETED', 'CANCELLED'].includes(item.state);
  }
  percentage(item: Download): number {
    if (!item.progress.totalBytes || item.progress.totalBytes < 0) return 0;
    const percentage = Math.max(
      0,
      Math.min(100, (100 * item.progress.completedBytes) / item.progress.totalBytes),
    );
    return Math.round(percentage * 100) / 100;
  }
  byteScale(total: number | undefined): number {
    return (total ?? 0) >= 1_000_000_000 ? 1_000_000_000 : 1_048_576;
  }
  byteUnit(total: number | undefined): string {
    return (total ?? 0) >= 1_000_000_000 ? 'GB' : 'MiB';
  }
  preparingTransfer(item: Download): boolean {
    return (
      item.state === 'TRANSFERRING' &&
      !item.progress.allocation &&
      item.progress.completedBytes === 0 &&
      item.progress.speedBytesPerSecond === 0
    );
  }
  /** What a transferring download is doing; stale progress is never shown as transferring. */
  phase(item: Download): TransferPhase | 'STALE' {
    if (item.state !== 'TRANSFERRING') return 'TRANSFERRING';
    if (this.now() - this.receivedAt() > STALE_PROGRESS_MS) return 'STALE';
    return item.progress.phase ?? 'TRANSFERRING';
  }
  pendingMiB(item: Download): number {
    return Math.round((item.progress.pendingWriteBytes ?? 0) / 1048576);
  }
  /** Uses the 60 s observed average so a short freeze changes the estimate gradually. */
  remainingSeconds(item: Download): number | undefined {
    if (item.progress.allocation) return item.progress.allocation.secondsRemaining ?? undefined;
    const rate = item.progress.averageBytesPerSecond ?? item.progress.speedBytesPerSecond;
    if (!item.progress.totalBytes || rate <= 0) return undefined;
    return Math.ceil(Math.max(0, item.progress.totalBytes - item.progress.completedBytes) / rate);
  }
  duration(seconds: number): string {
    const rounded = Math.max(0, Math.ceil(seconds));
    const hours = Math.floor(rounded / 3600);
    const minutes = Math.floor((rounded % 3600) / 60);
    return hours
      ? `${hours}h ${minutes}min`
      : minutes
        ? `${minutes}min ${rounded % 60}s`
        : `${rounded}s`;
  }
  canPause(state: string): boolean {
    return ['QUEUED', 'PREPARING', 'TRANSFERRING'].includes(state);
  }
  canCancel(state: string): boolean {
    return [
      'QUEUED',
      'PREPARING',
      'TRANSFERRING',
      'AWAITING_FILE_SELECTION',
      'PAUSED',
      'FAILED',
    ].includes(state);
  }
  cancelling(item: Download): boolean {
    return (
      this.cancellingIds().has(item.id) ||
      (item.state === 'CANCELLED' && item.execution.cancellationCleanupCompleted !== true)
    );
  }
  async control(item: Download, action: string): Promise<void> {
    if (this.busy()) return;
    if (action === 'CANCEL') this.cancellingIds.update((ids) => new Set([...ids, item.id]));
    await this.run(async () => {
      await this.api.post(`/downloads/${item.id}/control`, {
        generation: item.execution.generation,
        action,
      });
      await this.refreshItems();
    });
    this.cancellingIds.update((ids) => new Set([...ids].filter((id) => id !== item.id)));
  }
  async notification(id: string): Promise<void> {
    await this.run(async () => {
      await this.api.post(`/downloads/${id}/notification/retry`);
      await this.refreshItems();
    });
  }
  async toggleHistory(id: string): Promise<void> {
    if (this.history()[id]) {
      this.history.update((current) =>
        Object.fromEntries(Object.entries(current).filter(([requestId]) => requestId !== id)),
      );
      return;
    }
    await this.loadHistory(id);
  }
  /** Re-reads stored samples for every charted download and drops live points they now cover. */
  async refreshSpeedHistory(): Promise<void> {
    await Promise.all(Object.keys(this.speedHistory()).map((id) => this.fetchSpeedHistory(id)));
  }
  private syncSpeedHistory(items: DownloadStatus[]): void {
    const now = Date.now();
    const current = this.speedHistory();
    const next: Record<string, SpeedHistory> = {};
    const added: string[] = [];
    for (const { request } of items) {
      if (request.state !== 'TRANSFERRING') continue;
      const previous = current[request.id];
      if (!previous) added.push(request.id);
      const point: SpeedPoint = {
        at: now,
        speedBytesPerSecond: request.progress.allocation ? 0 : request.progress.speedBytesPerSecond,
        connections: request.progress.connections ?? 0,
        ...(request.progress.diskBytesPerSecond != null
          ? { diskBytesPerSecond: request.progress.diskBytesPerSecond }
          : {}),
      };
      next[request.id] = {
        stored: previous?.stored ?? [],
        tail: [...(previous?.tail ?? []), point].slice(-MAX_LIVE_TAIL),
      };
    }
    this.speedHistory.set(next);
    for (const id of added) void this.fetchSpeedHistory(id);
  }
  private async fetchSpeedHistory(id: string): Promise<void> {
    try {
      const samples = await this.api.get<SpeedSample[]>(`/downloads/${id}/speed-history`);
      const stored = samplesToPoints(samples);
      const newest = stored.at(-1)?.at ?? Number.NEGATIVE_INFINITY;
      this.speedHistory.update((current) =>
        current[id]
          ? {
              ...current,
              [id]: { stored, tail: current[id].tail.filter((point) => point.at > newest) },
            }
          : current,
      );
    } catch {
      // The chart keeps showing live points; losing stored samples must not disturb the page.
    }
  }
  async loadHistory(id: string): Promise<void> {
    await this.run(async () => {
      const events = await this.api.get<DownloadEvent[]>(`/downloads/${id}/history`);
      this.history.update((current) => ({ ...current, [id]: events }));
    });
  }
  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await action();
    } catch (error) {
      this.error.set(
        (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
