import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { Settings } from '../../services/debrid-api.service';
import { TransferFieldsComponent } from './transfer-fields.component';

describe('Shared aria2 transfer settings', () => {
  it('exposes transfer limits and keeps connections synchronized with split', async () => {
    TestBed.configureTestingModule({ providers: [provideTranslateService()] });
    const settings: Pick<Settings, 'maxConcurrentDownloads' | 'transfer'> = {
      maxConcurrentDownloads: 1,
      transfer: {
        split: 8,
        maxConnectionPerServer: 8,
        minSplitSizeMib: 64,
        maxTries: 5,
        retryWaitSeconds: 0,
        fileAllocation: 'FALLOC',
        ipv6Enabled: true,
      },
    };
    const fixture = TestBed.createComponent(TransferFieldsComponent);
    fixture.componentRef.setInput('settings', settings);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[id$="-debrid-concurrency"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[id$="-connections"]').max).toBe('16');
    expect(fixture.nativeElement.querySelector('[id$="-lowest-speed"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[id$="-disk-cache"]').value).toBe('64');
    expect(fixture.nativeElement.querySelector('[id$="-timeout"]').value).toBe('60');
    expect(fixture.nativeElement.querySelector('[id$="-file-not-found"]').value).toBe('2');
    const connections = fixture.nativeElement.querySelector(
      '[id$="-connections"]',
    ) as HTMLInputElement;
    connections.value = '16';
    connections.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(settings.transfer.split).toBe(16);
    expect(settings.transfer.maxConnectionPerServer).toBe(16);
    const concurrency = fixture.nativeElement.querySelector(
      '[id$="-concurrency"]',
    ) as HTMLInputElement;
    concurrency.value = '3';
    concurrency.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[id$="-debrid-concurrency"]').value).toBe('1');
    expect(fixture.nativeElement.textContent).toContain('KZ.DEBRID_CONCURRENCY_WARNING');
    settings.transfer.maxConcurrentPerDebrid = 3;
    concurrency.value = '1';
    concurrency.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(settings.transfer.maxConcurrentPerDebrid).toBe(1);
    expect(fixture.nativeElement.querySelector('[id$="-debrid-concurrency"]')).toBeNull();
  });

  it('shows the minimum speed only while required and keeps its value when unchecked', async () => {
    TestBed.configureTestingModule({ providers: [provideTranslateService()] });
    const settings: Pick<Settings, 'maxConcurrentDownloads' | 'transfer'> = {
      maxConcurrentDownloads: 1,
      transfer: {
        split: 8,
        maxConnectionPerServer: 8,
        minSplitSizeMib: 64,
        maxTries: 5,
        retryWaitSeconds: 10,
        fileAllocation: 'FALLOC',
        ipv6Enabled: true,
        lowestSpeedLimitKib: 120,
      },
    };
    const fixture = TestBed.createComponent(TransferFieldsComponent);
    fixture.componentRef.setInput('settings', settings);
    fixture.detectChanges();
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const label = element.querySelector('label[for="transfer-lowest-speed-enabled"]');
    expect(label?.textContent).toContain('KZ.LOWEST_SPEED_ENABLED');
    expect(element.textContent).toContain('KZ.LOWEST_SPEED_ENABLED_HINT');
    const checkbox = element.querySelector('#transfer-lowest-speed-enabled') as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
    expect(element.querySelector('#transfer-lowest-speed')).toBeNull();

    const toggle = async () => {
      checkbox.click();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    await toggle();
    expect(settings.transfer.lowestSpeedLimitEnabled).toBe(true);
    const speed = element.querySelector('#transfer-lowest-speed') as HTMLInputElement;
    expect(speed.value).toBe('120');
    expect(speed.min).toBe('1');
    expect(speed.max).toBe('1048576');
    const before = structuredClone(settings.transfer);

    await toggle();
    expect(settings.transfer.lowestSpeedLimitEnabled).toBe(false);
    expect(element.querySelector('#transfer-lowest-speed')).toBeNull();
    expect(settings.transfer.lowestSpeedLimitKib).toBe(120);

    await toggle();
    expect(settings.transfer).toEqual(before);
    expect((element.querySelector('#transfer-lowest-speed') as HTMLInputElement).value).toBe('120');
  });
});
