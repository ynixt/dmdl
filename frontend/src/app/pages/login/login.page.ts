import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { firstValueFrom } from 'rxjs';
import { SecretInputComponent } from '../../components/secret-input/secret-input.component';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService, toSupportedLanguage } from '../../core/i18n/i18n.service';

@Component({
  selector: 'app-login-page',
  imports: [
    ReactiveFormsModule,
    CardModule,
    InputTextModule,
    ButtonModule,
    MessageModule,
    ProgressSpinnerModule,
    SecretInputComponent,
    TranslatePipe,
  ],
  templateUrl: './login.page.html',
  changeDetection: ChangeDetectionStrategy.Default,
  styleUrl: './login.page.scss',
})
export class LoginPageComponent {
  private readonly formBuilder = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly i18nService = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly noticeKey = this.route.snapshot.queryParamMap.has('registered')
    ? 'PAGES.LOGIN.REGISTERED'
    : this.route.snapshot.queryParamMap.has('registrationClosed')
      ? 'PAGES.LOGIN.REGISTRATION_CLOSED'
      : null;

  readonly isSubmitting = signal(false);
  readonly errorKey = signal<string | null>(null);
  readonly form = this.formBuilder.nonNullable.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  async submit(): Promise<void> {
    if (this.form.invalid || this.isSubmitting()) {
      this.form.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);
    this.errorKey.set(null);

    try {
      const response = await this.authService.login(this.form.getRawValue());
      const backendLanguage = toSupportedLanguage(response.locale);
      if (
        response.onboardingCompleted &&
        backendLanguage &&
        backendLanguage !== this.i18nService.currentLanguage()
      ) {
        await firstValueFrom(this.i18nService.setLanguage(backendLanguage));
      }
      void this.router.navigate([response.onboardingCompleted ? '/discovery' : '/onboarding']);
    } catch (error) {
      this.errorKey.set(this.resolveErrorKey((error as HttpErrorResponse).status));
    } finally {
      this.isSubmitting.set(false);
    }
  }

  private resolveErrorKey(status: number): string {
    if (status === 401) {
      return 'PAGES.LOGIN.ERRORS.INVALID_CREDENTIALS';
    }

    if (status === 400) {
      return 'PAGES.LOGIN.ERRORS.INVALID_REQUEST';
    }

    return 'PAGES.LOGIN.ERRORS.UNEXPECTED';
  }
}
