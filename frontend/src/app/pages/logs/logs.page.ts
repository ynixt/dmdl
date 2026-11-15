import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { Subscription } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ApiAvailabilityService } from '../../core/api-availability.service';
import { LogFilters, LogLevel, LogPage, LogRecord, LogsService, logLevels } from './logs.service';

@Component({
  selector: 'app-logs',
  imports: [DatePipe, FormsModule, TranslatePipe, ButtonModule],
  templateUrl: './logs.page.html',
  styleUrl: './logs.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogsPage {
  private readonly api = inject(LogsService);
  private readonly auth = inject(AuthService);
  private readonly availability = inject(ApiAvailabilityService);
  private readonly viewport = viewChild<ElementRef<HTMLElement>>('viewport');
  private request?: Subscription;
  private stream?: EventSource;
  private debounce?: ReturnType<typeof setTimeout>;
  private reconnect?: ReturnType<typeof setTimeout>;
  private generation = 0;
  private cursor = '';
  private olderCursor: string | null = null;
  private activeFilters: LogFilters = { source: 'all', text: '', levels: [] };
  readonly levels = logLevels;
  readonly records = signal<LogRecord[]>([]);
  readonly sortOrder = signal<'asc' | 'desc'>('asc');
  readonly sortedRecords = computed(() => {
    const records = [...this.records()].sort(
      (a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp),
    );
    return this.sortOrder() === 'asc' ? records : records.reverse();
  });
  readonly sources = signal<LogPage['sources']>([]);
  readonly paused = signal(false);
  readonly loading = signal(true);
  readonly loadingOlder = signal(false);
  readonly hasOlder = signal(false);
  readonly evicted = signal(false);
  readonly gap = signal<'reset' | 'retention' | null>(null);
  readonly status = signal<'connecting' | 'live' | 'disconnected' | 'failure'>('connecting');
  source: LogFilters['source'] = 'all';
  text = '';
  selectedLevels: LogLevel[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cancel());
    this.replace();
  }

  filtersChanged(debounce = false) {
    clearTimeout(this.debounce);
    this.cancel();
    if (debounce) this.debounce = setTimeout(() => this.replace(), 300);
    else this.replace();
  }

  toggleLevel(level: LogLevel, checked: boolean) {
    this.selectedLevels = checked
      ? [...this.selectedLevels, level]
      : this.selectedLevels.filter((value) => value !== level);
    this.filtersChanged();
  }

  changeSortOrder(order: 'asc' | 'desc') {
    this.sortOrder.set(order);
    requestAnimationFrame(() => {
      const viewport = this.viewport()?.nativeElement;
      if (viewport) viewport.scrollTop = order === 'asc' ? viewport.scrollHeight : 0;
    });
  }

  togglePause() {
    this.paused.update((paused) => !paused);
    if (this.paused()) {
      this.cancel();
      this.loading.set(false);
      this.loadingOlder.set(false);
    } else if (
      this.cursor &&
      this.activeFilters.source === this.source &&
      this.activeFilters.text === this.text &&
      this.activeFilters.levels.join() === this.selectedLevels.join()
    ) {
      this.connect(this.generation);
    } else {
      this.replace();
    }
  }

  retry() {
    if (this.cursor && !this.paused()) this.connect(this.generation);
    else this.replace();
  }

  loadOlder() {
    if (!this.olderCursor || this.loadingOlder()) return;
    const generation = this.generation;
    this.loadingOlder.set(true);
    this.request = this.api.history(this.activeFilters, this.olderCursor).subscribe({
      next: (page) => {
        if (generation !== this.generation) return;
        this.sources.set(page.sources);
        if (page.gap) {
          this.replace(page.gap);
          return;
        }
        this.merge(page.records, true);
        this.olderCursor = page.olderCursor;
        this.hasOlder.set(page.hasMore);
        this.loadingOlder.set(false);
      },
      error: () => {
        if (generation !== this.generation) return;
        this.loadingOlder.set(false);
        this.status.set('failure');
      },
    });
  }

  private replace(recoveryGap: LogPage['gap'] = null) {
    this.cancel();
    const generation = this.generation;
    this.activeFilters = { source: this.source, text: this.text, levels: [...this.selectedLevels] };
    this.records.set([]);
    this.cursor = '';
    this.olderCursor = null;
    this.hasOlder.set(false);
    this.evicted.set(false);
    this.gap.set(recoveryGap);
    this.loading.set(true);
    this.loadingOlder.set(false);
    this.status.set('connecting');
    this.request = this.api.history(this.activeFilters).subscribe({
      next: (page) => {
        if (generation !== this.generation) return;
        this.records.set(page.records);
        this.sources.set(page.sources);
        this.cursor = page.cursor;
        this.olderCursor = page.olderCursor;
        this.hasOlder.set(page.hasMore);
        this.gap.set(page.gap ?? recoveryGap);
        this.loading.set(false);
        if (!this.paused()) this.connect(generation);
      },
      error: () => {
        if (generation !== this.generation) return;
        this.loading.set(false);
        this.status.set('failure');
      },
    });
  }

  private connect(generation: number) {
    this.stream?.close();
    clearTimeout(this.reconnect);
    this.status.set('connecting');
    const stream = this.api.events(this.activeFilters, this.cursor);
    this.stream = stream;
    stream.addEventListener('logs', (event) => {
      if (generation !== this.generation || this.paused() || this.stream !== stream) return;
      const page: LogPage = JSON.parse((event as MessageEvent).data);
      if (page.gap) {
        this.records.set([]);
        this.gap.set(page.gap);
        this.olderCursor = page.olderCursor;
        this.hasOlder.set(page.hasMore);
      }
      this.merge(page.records);
      this.sources.set(page.sources);
      this.cursor = page.cursor;
      this.status.set('live');
    });
    stream.addEventListener('session-ended', () => {
      if (generation !== this.generation || this.stream !== stream) return;
      this.cancel();
      void this.auth.expireSession();
    });
    stream.onerror = () => {
      if (generation !== this.generation || this.paused() || this.stream !== stream) return;
      stream.close();
      this.status.set('disconnected');
      void this.recover(generation);
    };
  }

  private async recover(generation: number) {
    // HttpClient applies the existing unauthorized and availability interceptors to this probe.
    try {
      const authenticated = await this.auth.refreshSession();
      if (generation !== this.generation || this.paused()) return;
      if (!authenticated) {
        this.cancel();
        await this.auth.expireSession();
        return;
      }
    } catch {
      if (generation !== this.generation) return;
      await this.availability.confirmAndGoOffline('/logs');
    }
    if (generation === this.generation && !this.paused()) {
      this.reconnect = setTimeout(() => this.connect(generation), 2000);
    }
  }

  private merge(incoming: LogRecord[], older = false) {
    if (!incoming.length) return;
    const viewport = this.viewport()?.nativeElement;
    const follow =
      viewport &&
      (this.sortOrder() === 'asc'
        ? viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 48
        : viewport.scrollTop < 48);
    const current = this.records();
    const merged = new Map(
      (older ? [...incoming, ...current] : [...current, ...incoming]).map((record) => [
        record.id,
        record,
      ]),
    );
    const values = [...merged.values()];
    if (values.length > 2000) this.evicted.set(true);
    this.records.set(older ? values.slice(0, 2000) : values.slice(-2000));
    if (follow && !older) {
      requestAnimationFrame(() => {
        if (viewport && !this.paused()) {
          viewport.scrollTop = this.sortOrder() === 'asc' ? viewport.scrollHeight : 0;
        }
      });
    }
  }

  private cancel() {
    this.generation++;
    this.request?.unsubscribe();
    this.stream?.close();
    clearTimeout(this.debounce);
    clearTimeout(this.reconnect);
  }
}
