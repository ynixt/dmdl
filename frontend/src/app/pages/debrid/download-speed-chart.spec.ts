import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import type { Scale, Tick } from 'chart.js';
import {
  clockTime,
  DownloadSpeedChartComponent,
  SpeedPoint,
  withAlpha,
} from './download-speed-chart.component';

describe('Download speed chart', () => {
  let fixture: ComponentFixture<DownloadSpeedChartComponent>;
  const start = new Date(2026, 10, 14, 14, 41, 0).getTime();
  const samples: SpeedPoint[] = [
    { at: start, speedBytesPerSecond: 8 * 1048576, connections: 8 },
    { at: start + 30_000, speedBytesPerSecond: 4 * 1048576, connections: 3 },
    { at: start + 60_000, speedBytesPerSecond: 1048576 / 2, connections: 1 },
  ];

  async function render(points: SpeedPoint[]): Promise<void> {
    fixture = TestBed.createComponent(DownloadSpeedChartComponent);
    fixture.componentRef.setInput('samples', points);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideTranslateService()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', {
      KZ: {
        SPEED_CHART_SPEED: 'Speed',
        OPEN_CONNECTIONS: 'Open connections',
        SPEED_CHART_SUMMARY:
          'Now {{current}} MiB/s, peak {{peak}} MiB/s, {{connections}} connections, peak {{peakConnections}}',
        SPEED_CHART_EMPTY: 'Waiting',
      },
    });
    translate.use('en');
  });

  afterEach(() => fixture?.destroy());

  it('plots speed on the left axis and stepped connections on the right axis', async () => {
    await render(samples);
    const [speed, connections] = fixture.componentInstance.data.datasets;
    expect(speed.label).toBe('Speed');
    expect(speed.yAxisID).toBe('speed');
    expect(speed.fill).toBe('origin');
    expect(speed.data).toEqual([
      { x: start, y: 8 },
      { x: start + 30_000, y: 4 },
      { x: start + 60_000, y: 0.5 },
    ]);
    expect(connections.label).toBe('Open connections');
    expect(connections.yAxisID).toBe('connections');
    expect(connections.stepped).toBe(true);
    expect(connections.data.map((point) => point.y)).toEqual([8, 3, 1]);

    const options = fixture.componentInstance.options;
    expect(options.animation).toBe(false);
    expect(options.maintainAspectRatio).toBe(false);
    const scales = options.scales!;
    expect(scales['x']!.type).toBe('linear');
    expect(scales['speed']!.position).toBe('left');
    expect(scales['connections']!.position).toBe('right');
    expect(scales['connections']!.ticks).toMatchObject({ stepSize: 1, precision: 0 });
    const tick = scales['x']!.ticks!.callback! as (
      this: Scale,
      value: number,
      index: number,
      ticks: Tick[],
    ) => string;
    expect(tick.call({} as Scale, start + 60_000, 0, [])).toBe('14:42');
  });

  it('summarizes current and peak speed and connections for assistive technology', async () => {
    await render(samples);
    const canvas = fixture.nativeElement.querySelector('canvas') as HTMLCanvasElement;
    expect(canvas.getAttribute('role')).toBe('img');
    expect(canvas.getAttribute('aria-label')).toBe(
      'Now 0.5 MiB/s, peak 8 MiB/s, 1 connections, peak 8',
    );
  });

  it('shows a placeholder instead of an empty chart', async () => {
    await render([]);
    expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
    expect(fixture.nativeElement.querySelector('.speed-chart-empty').textContent.trim()).toBe(
      'Waiting',
    );
  });

  it('formats clock times and translucent theme colors without extra libraries', () => {
    expect(clockTime(new Date(2026, 0, 1, 9, 5, 7).getTime())).toBe('09:05');
    expect(clockTime(new Date(2026, 0, 1, 9, 5, 7).getTime(), true)).toBe('09:05:07');
    expect(withAlpha('#10b981', 0.5)).toBe('rgba(16, 185, 129, 0.5)');
    expect(withAlpha('#fff', 0.2)).toBe('rgba(255, 255, 255, 0.2)');
    expect(withAlpha('rgb(1, 2, 3)', 0.1)).toBe('rgba(1, 2, 3, 0.1)');
    expect(withAlpha('oklch(0.7 0.1 150)', 0.1)).toBe('oklch(0.7 0.1 150)');
  });
});
