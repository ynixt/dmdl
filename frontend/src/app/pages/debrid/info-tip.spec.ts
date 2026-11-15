import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import { providePrimeNG } from 'primeng/config';
import { InfoTipComponent } from './info-tip.component';

describe('Info tip', () => {
  let fixture: ComponentFixture<InfoTipComponent>;

  async function render(): Promise<HTMLButtonElement> {
    TestBed.configureTestingModule({ providers: [provideTranslateService(), providePrimeNG()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', {
      KZ: {
        INFO_ABOUT: 'About {{field}}',
        DISK_CACHE: 'Disk cache',
        DISK_CACHE_INFO: 'Memory used to group writes.',
      },
    });
    translate.use('en');
    fixture = TestBed.createComponent(InfoTipComponent);
    fixture.componentRef.setInput('text', 'KZ.DISK_CACHE_INFO');
    fixture.componentRef.setInput('field', 'KZ.DISK_CACHE');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('button') as HTMLButtonElement;
  }

  async function tooltipText(): Promise<string | undefined> {
    await vi.waitFor(() => expect(document.querySelector('.p-tooltip')).not.toBeNull());
    return document.querySelector('.p-tooltip')?.textContent?.trim();
  }

  afterEach(() => {
    fixture?.destroy();
    document.querySelectorAll('.p-tooltip').forEach((tooltip) => tooltip.remove());
  });

  it('has an accessible name and describes the setting without the tooltip', async () => {
    const button = await render();
    expect(button.type).toBe('button');
    expect(button.getAttribute('aria-label')).toBe('About Disk cache');
    const description = document.getElementById(button.getAttribute('aria-describedby')!);
    expect(description?.textContent?.trim()).toBe('Memory used to group writes.');
  });

  it('opens the tooltip on hover', async () => {
    const button = await render();
    button.dispatchEvent(new MouseEvent('mouseenter'));
    expect(await tooltipText()).toBe('Memory used to group writes.');
  });

  it('opens the tooltip on keyboard focus, which is also what a tap does', async () => {
    const button = await render();
    button.dispatchEvent(new FocusEvent('focus'));
    expect(await tooltipText()).toBe('Memory used to group writes.');
  });
});
