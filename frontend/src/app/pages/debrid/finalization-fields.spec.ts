import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { FinalizationChecks } from '../../services/debrid-api.service';
import { FinalizationFieldsComponent } from './finalization-fields.component';
import { DEFAULT_FINALIZATION } from './settings-draft.store';

describe('Finalization checks settings', () => {
  async function render(checks: FinalizationChecks) {
    TestBed.configureTestingModule({ providers: [provideTranslateService()] });
    const fixture = TestBed.createComponent(FinalizationFieldsComponent);
    fixture.componentRef.setInput('checks', checks);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('defaults to verifying only the downloaded file', () => {
    expect(DEFAULT_FINALIZATION).toEqual({
      hashDownloadedFile: true,
      verifyCopy: false,
      verifyPlacedFile: false,
    });
  });

  it('toggles each check individually with an info tip and warns when all are off', async () => {
    const checks = { ...DEFAULT_FINALIZATION };
    const fixture = await render(checks);
    const element = fixture.nativeElement as HTMLElement;
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    const inputs = [...element.querySelectorAll('input[type="checkbox"]')] as HTMLInputElement[];
    expect(inputs.map((input) => input.checked)).toEqual([true, false, false]);
    expect(element.querySelectorAll('app-info-tip')).toHaveLength(3);
    expect(element.textContent).not.toContain('KZ.FINALIZATION_NONE_WARNING');

    inputs[2].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(checks).toEqual({ hashDownloadedFile: true, verifyCopy: false, verifyPlacedFile: true });
    expect(changed).toHaveBeenCalled();

    inputs[0].click();
    inputs[2].click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(checks).toEqual({
      hashDownloadedFile: false,
      verifyCopy: false,
      verifyPlacedFile: false,
    });
    expect(element.textContent).toContain('KZ.FINALIZATION_NONE_WARNING');
  });
});
