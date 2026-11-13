import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService } from '../../core/i18n/i18n.service';

@Component({
  selector: 'app-register-page',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ButtonModule,
    CardModule,
    InputTextModule,
    MessageModule,
  ],
  templateUrl: './register.page.html',
  styleUrl: '../login/login.page.scss',
  changeDetection: ChangeDetectionStrategy.Default,
})
export class RegisterPageComponent {
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly builder = inject(FormBuilder);

  readonly submitting = signal(false);
  readonly errorKey = signal<string | null>(null);
  readonly form = this.builder.nonNullable.group(
    {
      username: [
        '',
        [Validators.required, Validators.minLength(3), Validators.maxLength(64), trimmedUsername],
      ],
      password: ['', [Validators.required, Validators.minLength(6), passwordByteLimit]],
      confirmation: ['', Validators.required],
    },
    { validators: matchingPasswords },
  );

  async submit(): Promise<void> {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.errorKey.set(null);
    const { username, password } = this.form.getRawValue();
    try {
      await this.auth.register({
        username: username.trim(),
        password,
        locale: this.i18n.currentLanguage(),
      });
      await this.router.navigate(['/login'], { queryParams: { registered: true } });
    } catch (error) {
      const status = (error as HttpErrorResponse).status;
      if (status === 409) {
        await this.router.navigate(['/login'], { queryParams: { registrationClosed: true } });
      } else {
        this.errorKey.set(
          status === 400 ? 'PAGES.REGISTER.INVALID_REQUEST' : 'PAGES.REGISTER.UNEXPECTED',
        );
      }
    } finally {
      this.submitting.set(false);
    }
  }
}

function trimmedUsername(control: AbstractControl) {
  const length = String(control.value ?? '').trim().length;
  return length >= 3 && length <= 64 ? null : { username: true };
}

function passwordByteLimit(control: AbstractControl) {
  return new TextEncoder().encode(String(control.value ?? '')).length <= 72
    ? null
    : { byteLimit: true };
}

function matchingPasswords(control: AbstractControl) {
  return control.get('password')?.value === control.get('confirmation')?.value
    ? null
    : { passwordMismatch: true };
}
