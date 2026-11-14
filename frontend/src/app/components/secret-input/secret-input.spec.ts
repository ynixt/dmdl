import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import { SecretInputComponent, SecretInputKind } from './secret-input.component';

@Component({
  imports: [ReactiveFormsModule, SecretInputComponent],
  template: `
    <label for="reactive-secret">Password</label>
    <app-secret-input
      inputId="reactive-secret"
      [kind]="kind()"
      [formControl]="control"
      [describedBy]="describedBy()"
      [invalid]="invalid()"
      placeholder="Type it"
    />
  `,
})
class ReactiveHost {
  readonly control = new FormControl('initial', { nonNullable: true });
  readonly kind = signal<SecretInputKind>('secret');
  readonly describedBy = signal<string | null>(null);
  readonly invalid = signal(false);
}

@Component({
  imports: [FormsModule, SecretInputComponent],
  template: `
    <app-secret-input inputId="model-secret" [(ngModel)]="value" />
    <app-secret-input inputId="other-secret" [(ngModel)]="other" />
  `,
})
class ModelHost {
  value = 'from-model';
  other = 'second';
}

describe('Secret input', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideTranslateService({ lang: 'en-US' })] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en-US', {
      COMMON: { SHOW_SECRET: 'Show value', HIDE_SECRET: 'Hide value' },
    });
    translate.setTranslation('pt-BR', {
      COMMON: { SHOW_SECRET: 'Mostrar valor', HIDE_SECRET: 'Ocultar valor' },
    });
    translate.use('en-US');
  });

  async function render<T>(host: new () => T): Promise<ComponentFixture<T>> {
    const fixture = TestBed.createComponent(host);
    await settle(fixture);
    return fixture;
  }

  // An outer ngModel writes asynchronously, then the inner one does: allow both hops.
  async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
    for (let pass = 0; pass < 2; pass++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  const field = (fixture: ComponentFixture<unknown>, id: string) =>
    fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
  const toggle = (fixture: ComponentFixture<unknown>, index = 0) =>
    fixture.nativeElement.querySelectorAll('.secret-input-toggle')[index] as HTMLButtonElement;

  function type(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('works with a reactive form control and starts masked', async () => {
    const fixture = await render(ReactiveHost);
    const input = field(fixture, 'reactive-secret');
    expect(input.tagName).toBe('INPUT');
    expect(input.type).toBe('password');
    expect(input.value).toBe('initial');
    expect(input.placeholder).toBe('Type it');

    type(input, 'typed');
    expect(fixture.componentInstance.control.value).toBe('typed');
    expect(fixture.componentInstance.control.touched).toBe(false);
    input.dispatchEvent(new Event('blur'));
    expect(fixture.componentInstance.control.touched).toBe(true);

    fixture.componentInstance.control.setValue('from-form');
    await settle(fixture);
    expect(input.value).toBe('from-form');

    fixture.componentInstance.control.disable();
    await settle(fixture);
    expect(field(fixture, 'reactive-secret').disabled).toBe(true);
    expect(toggle(fixture).disabled).toBe(true);
  });

  it('works with ngModel and toggles each field on its own', async () => {
    const fixture = await render(ModelHost);
    const input = field(fixture, 'model-secret');
    const other = field(fixture, 'other-secret');
    expect(input.value).toBe('from-model');

    type(input, 'changed');
    expect(fixture.componentInstance.value).toBe('changed');

    toggle(fixture, 0).click();
    fixture.detectChanges();
    expect(field(fixture, 'model-secret').type).toBe('text');
    expect(field(fixture, 'other-secret').type).toBe('password');
    expect(other.value).toBe('second');
  });

  it('reveals and hides with a labelled button without changing the value', async () => {
    const fixture = await render(ReactiveHost);
    const input = field(fixture, 'reactive-secret');
    const show = toggle(fixture);
    expect(show.tagName).toBe('BUTTON');
    expect(show.type).toBe('button');
    expect(show.tabIndex).toBe(0);
    expect(show.getAttribute('aria-label')).toBe('Show value');
    expect(show.getAttribute('aria-controls')).toBe('reactive-secret');
    expect(show.querySelector('.pi-eye')).not.toBeNull();

    show.focus();
    show.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.type).toBe('text');
    expect(input.value).toBe('initial');
    const hide = toggle(fixture);
    expect(hide.getAttribute('aria-label')).toBe('Hide value');
    expect(hide.querySelector('.pi-eye-slash')).not.toBeNull();
    // The activated button was replaced; focus follows to its replacement.
    expect(document.activeElement).toBe(hide);

    hide.click();
    fixture.detectChanges();
    expect(input.type).toBe('password');
    expect(input.value).toBe('initial');
    expect(fixture.componentInstance.control.value).toBe('initial');
    expect(fixture.componentInstance.control.dirty).toBe(false);
  });

  it('uses keyboard activation of the native button to toggle', async () => {
    const fixture = await render(ReactiveHost);
    const input = field(fixture, 'reactive-secret');
    const show = toggle(fixture);
    show.focus();
    // Browsers turn Enter/Space on a <button type="button"> into a click; jsdom does not
    // synthesize that activation, so dispatch the resulting click the way the browser would.
    show.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    show.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.type).toBe('text');
    const hide = toggle(fixture);
    expect(document.activeElement).toBe(hide);
    hide.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true }));
    hide.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }));
    fixture.detectChanges();
    expect(input.type).toBe('password');
    expect(input.value).toBe('initial');
  });

  it('labels the control in the active language', async () => {
    const fixture = await render(ReactiveHost);
    TestBed.inject(TranslateService).use('pt-BR');
    await settle(fixture);
    expect(toggle(fixture).getAttribute('aria-label')).toBe('Mostrar valor');
  });

  it('forwards description and invalid state to the inner input', async () => {
    const fixture = await render(ReactiveHost);
    const input = field(fixture, 'reactive-secret');
    expect(input.hasAttribute('aria-describedby')).toBe(false);
    expect(input.hasAttribute('aria-invalid')).toBe(false);

    fixture.componentInstance.describedBy.set('help-text');
    fixture.componentInstance.invalid.set(true);
    fixture.detectChanges();
    expect(input.getAttribute('aria-describedby')).toBe('help-text');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(fixture.nativeElement.querySelector('label').htmlFor).toBe(input.id);

    fixture.componentInstance.describedBy.set(null);
    fixture.componentInstance.invalid.set(false);
    fixture.detectChanges();
    expect(input.hasAttribute('aria-describedby')).toBe(false);
    expect(input.hasAttribute('aria-invalid')).toBe(false);
  });

  it.each<[SecretInputKind, string, boolean]>([
    ['current-password', 'current-password', false],
    ['new-password', 'new-password', false],
    ['secret', 'new-password', true],
  ])('sets password-manager hints for kind %s', async (kind, autocomplete, ignored) => {
    const fixture = TestBed.createComponent(ReactiveHost);
    fixture.componentInstance.kind.set(kind);
    await settle(fixture);
    const input = field(fixture, 'reactive-secret');
    expect(input.getAttribute('autocomplete')).toBe(autocomplete);
    for (const attribute of ['data-1p-ignore', 'data-lpignore', 'data-bwignore']) {
      expect(input.getAttribute(attribute)).toBe(ignored ? 'true' : null);
    }
  });
});
