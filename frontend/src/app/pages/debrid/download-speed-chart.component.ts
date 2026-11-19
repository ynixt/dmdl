import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import type { ChartData, ChartOptions, TooltipItem } from 'chart.js';
import { UIChart } from '@openng/optimus-ui/chart';
import type { DiskActivityAvailability, SpeedSample } from '../../services/debrid-api.service';

/** One chart point: epoch milliseconds, bytes per second and open connections. */
export interface SpeedPoint {
  at: number;
  speedBytesPerSecond: number;
  connections: number;
  /** Container-wide disk write speed; absent when disk activity is unavailable. */
  diskBytesPerSecond?: number | null;
}

/** Longest gap between two stored samples that is still averaged from their cumulative bytes. */
const MAX_CUMULATIVE_GAP_MS = 90_000;

/**
 * Turns stored samples into chart points. Each point is the true average since the previous sample, from the
 * cumulative byte counters; legacy samples, long gaps (pauses), counters that went backwards and a previous value of 0
 * (a restarted aria2 reports 0 before reading its control file) fall back to the stored speed.
 */
export function samplesToPoints(samples: SpeedSample[]): SpeedPoint[] {
  return samples.map((sample, index) => {
    const at = Date.parse(sample.at);
    const previous = samples[index - 1];
    const gap = previous ? at - Date.parse(previous.at) : 0;
    const rate = (current?: number | null, before?: number | null): number | undefined =>
      gap > 0 &&
      gap <= MAX_CUMULATIVE_GAP_MS &&
      current != null &&
      before != null &&
      before > 0 &&
      current >= before
        ? ((current - before) * 1000) / gap
        : undefined;
    const disk = rate(sample.diskBytes, previous?.diskBytes);
    return {
      at,
      speedBytesPerSecond:
        rate(sample.completedBytes, previous?.completedBytes) ?? sample.speedBytesPerSecond,
      connections: sample.connections,
      ...(disk !== undefined ? { diskBytesPerSecond: disk } : {}),
    };
  });
}

interface ChartColors {
  speed: string;
  speedFill: string;
  disk: string;
  connections: string;
  text: string;
  muted: string;
  grid: string;
}

const MIB = 1048576;

/** Local HH:mm (or HH:mm:ss) without a date adapter, since the X axis is linear epoch milliseconds. */
export function clockTime(epochMillis: number, seconds = false): string {
  const date = new Date(epochMillis);
  const pad = (value: number) => String(value).padStart(2, '0');
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return seconds ? `${time}:${pad(date.getSeconds())}` : time;
}

export function mebibytesPerSecond(bytesPerSecond: number): number {
  return Math.round((bytesPerSecond / MIB) * 100) / 100;
}

/** Makes a translucent fill from a hex or rgb() color; other formats are returned unchanged. */
export function withAlpha(color: string, alpha: number): string {
  const value = color.trim();
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value)?.[1];
  if (hex) {
    const full = hex.length === 3 ? [...hex].map((digit) => digit + digit).join('') : hex;
    const [red, green, blue] = [0, 2, 4].map((offset) =>
      parseInt(full.slice(offset, offset + 2), 16),
    );
    return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
  }
  const rgb = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i.exec(value);
  return rgb ? `rgba(${rgb[1]}, ${rgb[2]}, ${rgb[3]}, ${alpha})` : value;
}

@Component({
  selector: 'app-download-speed-chart',
  imports: [UIChart, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (samples().length) {
      <div class="speed-chart">
        <p-chart
          type="line"
          [data]="data"
          [options]="options"
          [ariaLabel]="'KZ.SPEED_CHART_SUMMARY' | translate: summary()"
        />
      </div>
    } @else {
      <p class="hint speed-chart-empty">{{ 'KZ.SPEED_CHART_EMPTY' | translate }}</p>
    }
    @switch (diskActivity()) {
      @case ('CGROUP_V2_MISSING') {
        <p class="hint disk-activity-note" role="note">
          <i class="pi pi-info-circle" aria-hidden="true"></i>
          {{ 'KZ.DISK_ACTIVITY_CGROUP_V2_MISSING' | translate }}
        </p>
      }
      @case ('NO_BLOCK_STATS') {
        <p class="hint disk-activity-note" role="note">
          <i class="pi pi-info-circle" aria-hidden="true"></i>
          {{ 'KZ.DISK_ACTIVITY_NO_BLOCK_STATS' | translate }}
        </p>
      }
    }
  `,
  styles: `
    .speed-chart {
      position: relative;
      height: 12rem;
      min-width: 0;
    }
    .speed-chart p-chart {
      height: 100%;
    }
    .disk-activity-note {
      display: flex;
      align-items: flex-start;
      gap: 0.35rem;
      margin: 0.25rem 0 0;
      font-size: 0.85rem;
      opacity: 0.8;
      overflow-wrap: anywhere;
    }
  `,
})
export class DownloadSpeedChartComponent {
  readonly samples = input.required<SpeedPoint[]>();
  readonly diskActivity = input<DiskActivityAvailability | null | undefined>(undefined);
  /** The disk line covers every transfer of the container, so say so when several are active. */
  readonly sharedDisk = input(false);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly destroy = inject(DestroyRef);
  private readonly chart = viewChild(UIChart);
  private readonly labels = toSignal(
    inject(TranslateService).stream([
      'KZ.SPEED_CHART_SPEED',
      'KZ.OPEN_CONNECTIONS',
      'KZ.SPEED_CHART_DISK',
      'KZ.SPEED_CHART_DISK_ALL',
    ]),
    { initialValue: {} as Record<string, string> },
  );
  private readonly colors = signal<ChartColors>(this.readColors());

  readonly summary = computed(() => {
    const samples = this.samples();
    const latest = samples.at(-1);
    return {
      current: mebibytesPerSecond(latest?.speedBytesPerSecond ?? 0),
      peak: mebibytesPerSecond(Math.max(0, ...samples.map((sample) => sample.speedBytesPerSecond))),
      connections: latest?.connections ?? 0,
      peakConnections: Math.max(0, ...samples.map((sample) => sample.connections)),
    };
  });

  /** Stable references: p-chart rebuilds the chart whenever these inputs change, so they are mutated in place. */
  readonly data: ChartData<'line', { x: number; y: number }[]> = { datasets: [] };
  readonly options: ChartOptions<'line'> = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    parsing: false,
    normalized: true,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      legend: { display: true, labels: {} },
      tooltip: {
        callbacks: {
          title: (items: TooltipItem<'line'>[]) =>
            items.length ? clockTime(items[0].parsed.x ?? 0, true) : '',
          label: (item: TooltipItem<'line'>) =>
            item.dataset.yAxisID === 'speed'
              ? `${item.dataset.label}: ${(item.parsed.y ?? 0).toFixed(2)} MiB/s`
              : `${item.dataset.label}: ${item.parsed.y ?? 0}`,
        },
      },
    },
    scales: {
      x: {
        type: 'linear',
        ticks: { callback: (value) => clockTime(Number(value)), maxTicksLimit: 8 },
      },
      speed: {
        type: 'linear',
        position: 'left',
        beginAtZero: true,
        ticks: { callback: (value) => `${value} MiB/s` },
      },
      connections: {
        type: 'linear',
        position: 'right',
        beginAtZero: true,
        suggestedMax: 1,
        ticks: { stepSize: 1, precision: 0 },
        grid: { drawOnChartArea: false },
      },
    },
  };

  constructor() {
    effect(() => this.render(this.samples(), this.colors(), this.labels(), this.sharedDisk()));
    afterNextRender(() => {
      this.colors.set(this.readColors());
      const media = window.matchMedia?.('(prefers-color-scheme: dark)');
      const refresh = () => this.colors.set(this.readColors());
      media?.addEventListener?.('change', refresh);
      // The app switches themes by toggling a class on <html>.
      const observer = new MutationObserver(refresh);
      observer.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['class', 'style'],
      });
      this.destroy.onDestroy(() => {
        media?.removeEventListener?.('change', refresh);
        observer.disconnect();
      });
    });
  }

  private render(
    samples: SpeedPoint[],
    colors: ChartColors,
    labels: Record<string, string>,
    sharedDisk: boolean,
  ): void {
    const speed = samples.map((sample) => ({
      x: sample.at,
      y: mebibytesPerSecond(sample.speedBytesPerSecond),
    }));
    const disk = samples
      .filter((sample) => sample.diskBytesPerSecond != null)
      .map((sample) => ({ x: sample.at, y: mebibytesPerSecond(sample.diskBytesPerSecond ?? 0) }));
    const connections = samples.map((sample) => ({ x: sample.at, y: sample.connections }));
    const diskLabel = sharedDisk ? 'KZ.SPEED_CHART_DISK_ALL' : 'KZ.SPEED_CHART_DISK';
    this.data.datasets = [
      {
        label: labels['KZ.SPEED_CHART_SPEED'] ?? 'KZ.SPEED_CHART_SPEED',
        data: speed,
        yAxisID: 'speed',
        borderColor: colors.speed,
        backgroundColor: colors.speedFill,
        fill: 'origin',
        borderWidth: 2,
        pointRadius: 0,
        pointHitRadius: 6,
        tension: 0,
      },
      ...(disk.length
        ? [
            {
              label: labels[diskLabel] ?? diskLabel,
              data: disk,
              yAxisID: 'speed',
              borderColor: colors.disk,
              backgroundColor: colors.disk,
              borderDash: [6, 4],
              fill: false,
              borderWidth: 2,
              pointRadius: 0,
              pointHitRadius: 6,
              tension: 0,
            },
          ]
        : []),
      {
        label: labels['KZ.OPEN_CONNECTIONS'] ?? 'KZ.OPEN_CONNECTIONS',
        data: connections,
        yAxisID: 'connections',
        borderColor: colors.connections,
        backgroundColor: colors.connections,
        stepped: true,
        fill: false,
        borderWidth: 2,
        pointRadius: 0,
        pointHitRadius: 6,
      },
    ];
    const scales = this.options.scales!;
    for (const axis of ['x', 'speed', 'connections'] as const) {
      const scale = scales[axis]!;
      scale.ticks = { ...scale.ticks, color: colors.muted };
      scale.grid = { ...scale.grid, color: colors.grid };
    }
    scales['connections']!.suggestedMax = Math.max(1, ...connections.map((point) => point.y)) + 1;
    this.options.plugins!.legend!.labels = { color: colors.text };
    const chart = this.chart()?.chart;
    // A chart without a drawing context (for example under jsdom) cannot be updated.
    if (chart?.ctx) chart.update('none');
  }

  private readColors(): ChartColors {
    const style = getComputedStyle(this.host.nativeElement as HTMLElement);
    const token = (name: string, fallback: string) =>
      style.getPropertyValue(name).trim() || fallback;
    const speed = token('--p-primary-color', '#10b981');
    return {
      speed,
      speedFill: withAlpha(speed, 0.18),
      disk: token('--p-sky-500', '#0ea5e9'),
      connections: token('--p-orange-500', token('--p-text-muted-color', '#f97316')),
      text: token('--p-text-color', '#334155'),
      muted: token('--p-text-muted-color', '#64748b'),
      grid: token('--p-content-border-color', '#e2e8f0'),
    };
  }
}
