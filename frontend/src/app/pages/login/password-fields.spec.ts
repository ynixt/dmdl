import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { RegisterPageComponent } from '../register/register.page';
import { LoginPageComponent } from './login.page';

describe('Password fields on login and registration', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideTranslateService(),
        { provide: AuthService, useValue: {} },
        { provide: I18nService, useValue: { currentLanguage: () => 'en-US' } },
      ],
    });
  });

  async function render<T>(page: new () => T) {
    const fixture = TestBed.createComponent(page);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      element,
      query: <E extends Element>(s: string) => element.querySelector<E>(s)!,
    };
  }

  it('keeps login autofill semantics and shows the required error after touch', async () => {
    const { fixture, element, query } = await render(LoginPageComponent);
    const password = query<HTMLInputElement>('#login-password');
    expect(password.type).toBe('password');
    expect(password.getAttribute('autocomplete')).toBe('current-password');
    expect(password.hasAttribute('data-1p-ignore')).toBe(false);
    expect(query('label[for="login-password"]')).not.toBeNull();
    expect(element.textContent).not.toContain('PAGES.LOGIN.FORM.PASSWORD.REQUIRED');

    password.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(element.textContent).toContain('PAGES.LOGIN.FORM.PASSWORD.REQUIRED');
    expect(password.getAttribute('aria-invalid')).toBe('true');
    expect(password.getAttribute('aria-describedby')).toBe('login-password-error');
    expect(query('#login-password-error')).not.toBeNull();
  });

  it('reveals only the registration field whose control was activated', async () => {
    const { fixture, query } = await render(RegisterPageComponent);
    const password = query<HTMLInputElement>('#register-password');
    const confirmation = query<HTMLInputElement>('#register-confirmation');
    expect(password.getAttribute('autocomplete')).toBe('new-password');
    expect(confirmation.getAttribute('autocomplete')).toBe('new-password');
    expect(password.getAttribute('aria-describedby')).toBe('password-help');
    expect(query('#password-help')).not.toBeNull();
    expect(query('label[for="register-password"]')).not.toBeNull();
    expect(query('label[for="register-confirmation"]')).not.toBeNull();

    password.value = 'long-password-123';
    password.dispatchEvent(new Event('input'));
    const toggles = fixture.nativeElement.querySelectorAll('.secret-input-toggle');
    expect(toggles).toHaveLength(2);
    (toggles[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(password.type).toBe('text');
    expect(confirmation.type).toBe('password');
    expect(fixture.componentInstance.form.controls.password.value).toBe('long-password-123');
  });

  it('associates the mismatch error with the confirmation field', async () => {
    const { fixture, query } = await render(RegisterPageComponent);
    fixture.componentInstance.form.setValue({
      username: 'owner',
      password: 'long-password-123',
      confirmation: 'other-password-123',
    });
    fixture.componentInstance.form.markAllAsTouched();
    fixture.detectChanges();
    const confirmation = query<HTMLInputElement>('#register-confirmation');
    expect(confirmation.getAttribute('aria-invalid')).toBe('true');
    expect(confirmation.getAttribute('aria-describedby')).toBe('confirmation-error');
    expect(query('#confirmation-error').textContent).toContain('PAGES.REGISTER.PASSWORD_MISMATCH');
  });
});
