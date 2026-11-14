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
} from '../../services/debrid-api.service';
import { DownloadSpeedChartComponent, SpeedPoint } from './download-speed-chart.component';

/** Stored samples from the backend plus live points received since the newest one. */
interface SpeedHistory {
  stored: SpeedPoint[];
  tail: SpeedPoint[];
}

const SPEED_HISTORY_REFRESH_MS = 30_000;
const MAX_LIVE_TAIL = 2880;

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
  readonly items = signal<DownloadStatus[]>([]);
  readonly hasRemovableHistory = computed(() =>
    this.items().some((item) => this.canRemoveFromHistory(item.request)),
  );
  readonly error = signal('');
  readonly busy = signal(false);
  readonly history = signal<Record<string, DownloadEvent[]>>({});
  readonly cancellingIds = signal<Set<string>>(new Set());
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
      untracked(() => this.syncSpeedHistory(items));
    });
    const refresh = setInterval(() => void this.refreshSpeedHistory(), SPEED_HISTORY_REFRESH_MS);
    this.destroy.onDestroy(() => clearInterval(refresh));
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
        const items = await this.api.get<DownloadStatus[]>('/downloads');
        if (!this.destroy.destroyed) {
          this.items.set(items);
          if (this.error() === 'KZ.RECONNECTING') this.error.set('');
        }
      } catch {
        // Interceptors handle authentication and confirmed connectivity failures.
      } finally {
        refreshing = false;
      }
    };
    stream.addEventListener('downloads', (event) => {
      this.items.set(JSON.parse((event as MessageEvent).data) as DownloadStatus[]);
      lastEventAt = Date.now();
      if (this.error() === 'KZ.RECONNECTING') this.error.set('');
    });
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
    await this.run(async () => this.items.set(await this.api.get<DownloadStatus[]>('/downloads')));
  }
  async removeFromHistory(id?: string): Promise<void> {
    if (this.busy()) return;
    await this.run(async () => {
      await this.api.delete(id ? `/downloads/${id}` : '/downloads/completed');
      this.items.set(await this.api.get<DownloadStatus[]>('/downloads'));
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
  connectionRate(bytesPerSecond: number): { value: number; unit: string } {
    if (bytesPerSecond < 1024) return { value: bytesPerSecond, unit: 'B/s' };
    if (bytesPerSecond < 1048576) return { value: bytesPerSecond / 1024, unit: 'KiB/s' };
    return { value: bytesPerSecond / 1048576, unit: 'MiB/s' };
  }
  preparingTransfer(item: Download): boolean {
    return (
      item.state === 'TRANSFERRING' &&
      !item.progress.allocation &&
      item.progress.completedBytes === 0 &&
      item.progress.speedBytesPerSecond === 0
    );
  }
  remainingSeconds(item: Download): number | undefined {
    if (item.progress.allocation) return item.progress.allocation.secondsRemaining ?? undefined;
    if (!item.progress.totalBytes || item.progress.speedBytesPerSecond <= 0) return undefined;
    return Math.ceil(
      Math.max(0, item.progress.totalBytes - item.progress.completedBytes) /
        item.progress.speedBytesPerSecond,
    );
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
      this.items.set(await this.api.get<DownloadStatus[]>('/downloads'));
    });
    this.cancellingIds.update((ids) => new Set([...ids].filter((id) => id !== item.id)));
  }
  async notification(id: string): Promise<void> {
    await this.run(async () => {
      await this.api.post(`/downloads/${id}/notification/retry`);
      this.items.set(await this.api.get<DownloadStatus[]>('/downloads'));
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
      const stored = samples.map((sample) => ({
        at: Date.parse(sample.at),
        speedBytesPerSecond: sample.speedBytesPerSecond,
        connections: sample.connections,
      }));
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
