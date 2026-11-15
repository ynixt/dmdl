import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import type { DiskActivityAvailability, SpeedSample } from '../../services/debrid-api.service';
import {
  DownloadSpeedChartComponent,
  samplesToPoints,
  SpeedPoint,
} from './download-speed-chart.component';

const MIB = 1048576;

describe('Speed chart cumulative samples and disk activity', () => {
  let fixture: ComponentFixture<DownloadSpeedChartComponent>;
  const start = Date.parse('2026-11-14T22:48:00Z');
  const iso = (offsetSeconds: number) => new Date(start + offsetSeconds * 1000).toISOString();

  async function render(
    points: SpeedPoint[],
    diskActivity?: DiskActivityAvailability,
    sharedDisk = false,
  ): Promise<void> {
    fixture = TestBed.createComponent(DownloadSpeedChartComponent);
    fixture.componentRef.setInput('samples', points);
    fixture.componentRef.setInput('diskActivity', diskActivity);
    fixture.componentRef.setInput('sharedDisk', sharedDisk);
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
        SPEED_CHART_DISK: 'Disk',
        SPEED_CHART_DISK_ALL: 'Disk (all transfers)',
        OPEN_CONNECTIONS: 'Open connections',
        SPEED_CHART_SUMMARY: 'summary',
        DISK_ACTIVITY_CGROUP_V2_MISSING: 'requires cgroup v2 (Docker 20.10+)',
        DISK_ACTIVITY_NO_BLOCK_STATS: 'not available for this storage',
      },
    });
    translate.use('en');
  });

  afterEach(() => fixture?.destroy());

  it('plots each stored point as the true average since the previous sample', () => {
    const samples: SpeedSample[] = [
      {
        at: iso(0),
        speedBytesPerSecond: 38 * MIB,
        connections: 8,
        completedBytes: 1000 * MIB,
        diskBytes: 2000 * MIB,
      },
      // A 34 s freeze inside this interval: aria2's instantaneous value says 38 MiB/s.
      {
        at: iso(30),
        speedBytesPerSecond: 38 * MIB,
        connections: 8,
        completedBytes: 1300 * MIB,
        diskBytes: 2600 * MIB,
      },
    ];
    expect(samplesToPoints(samples)).toEqual([
      { at: start, speedBytesPerSecond: 38 * MIB, connections: 8 },
      {
        at: start + 30_000,
        speedBytesPerSecond: 10 * MIB,
        connections: 8,
        diskBytesPerSecond: 20 * MIB,
      },
    ]);
  });

  it('falls back to the stored speed for legacy samples, long gaps and counters that went back', () => {
    const samples: SpeedSample[] = [
      { at: iso(0), speedBytesPerSecond: 1000, connections: 1 },
      { at: iso(30), speedBytesPerSecond: 2000, connections: 1, completedBytes: 500 },
      // Paused for an hour: averaging over the pause would hide the real speed.
      { at: iso(3630), speedBytesPerSecond: 3000, connections: 1, completedBytes: 900_000 },
      // A restarted aria2 briefly reported 0, then the restored file.
      { at: iso(3640), speedBytesPerSecond: 0, connections: 1, completedBytes: 0 },
      { at: iso(3650), speedBytesPerSecond: 5000, connections: 1, completedBytes: 900_500 },
      // Resumed from an older control file.
      { at: iso(3660), speedBytesPerSecond: 4000, connections: 1, completedBytes: 800_000 },
    ];
    expect(samplesToPoints(samples).map((point) => point.speedBytesPerSecond)).toEqual([
      1000, 2000, 3000, 0, 5000, 4000,
    ]);
  });

  it('adds a dashed disk line named for all transfers when several are active', async () => {
    const points: SpeedPoint[] = [
      { at: start, speedBytesPerSecond: 30 * MIB, connections: 8, diskBytesPerSecond: 40 * MIB },
      { at: start + 1000, speedBytesPerSecond: 0, connections: 8, diskBytesPerSecond: 40 * MIB },
    ];
    await render(points, 'AVAILABLE');
    const disk = fixture.componentInstance.data.datasets.find((set) => set.label === 'Disk');
    expect(disk?.yAxisID).toBe('speed');
    expect(disk?.borderDash).toEqual([6, 4]);
    expect(disk?.data).toEqual([
      { x: start, y: 40 },
      { x: start + 1000, y: 40 },
    ]);
    fixture.destroy();
    await render(points, 'AVAILABLE', true);
    expect(fixture.componentInstance.data.datasets.map((set) => set.label)).toContain(
      'Disk (all transfers)',
    );
  });

  it('omits the disk line and explains why when disk activity is unavailable', async () => {
    const points: SpeedPoint[] = [{ at: start, speedBytesPerSecond: 30 * MIB, connections: 8 }];
    await render(points, 'CGROUP_V2_MISSING');
    expect(fixture.componentInstance.data.datasets.map((set) => set.label)).toEqual([
      'Speed',
      'Open connections',
    ]);
    expect(fixture.nativeElement.querySelector('.disk-activity-note').textContent).toContain(
      'requires cgroup v2 (Docker 20.10+)',
    );
    fixture.destroy();
    await render(points, 'NO_BLOCK_STATS');
    expect(fixture.nativeElement.querySelector('.disk-activity-note').textContent).toContain(
      'not available for this storage',
    );
    fixture.destroy();
    await render(points, 'AVAILABLE');
    expect(fixture.nativeElement.querySelector('.disk-activity-note')).toBeNull();
  });
});
