import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';
import { LoginPageComponent } from '../../pages/login/login.page';
import { RegisterPageComponent } from '../../pages/register/register.page';
import { I18nService } from '../i18n/i18n.service';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';
import { guestGuard } from './guest.guard';
import { registrationGuard } from './registration.guard';

describe('First account registration', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: I18nService, useValue: { currentLanguage: () => 'pt-BR' } },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  function runGuard(guard: CanActivateFn) {
    return firstValueFrom(
      TestBed.runInInjectionContext(() =>
        guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
      ) as Observable<boolean | UrlTree>,
    );
  }

  it('opens registration from protected and login routes while the installation has no account', async () => {
    const result = runGuard(authGuard);
    http.expectOne('/api/auth/registration').flush({ registrationRequired: true });
    expect((await result).toString()).toBe('/register');
    expect((await runGuard(guestGuard)).toString()).toBe('/register');
    expect(await runGuard(registrationGuard)).toBe(true);
    http.expectNone('/api/auth/session');
  });

  it('opens login and closes registration when an account already exists', async () => {
    const result = runGuard(authGuard);
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    http.expectOne('/api/auth/session').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect((await result).toString()).toBe('/login');
    expect(await runGuard(guestGuard)).toBe(true);
    expect((await runGuard(registrationGuard)).toString()).toBe('/login');
  });

  it('keeps authenticated users on the application routes', async () => {
    const result = runGuard(registrationGuard);
    http.expectOne('/api/auth/registration').flush({ registrationRequired: false });
    http.expectOne('/api/auth/session').flush({ username: 'owner', onboardingCompleted: false });
    expect((await result).toString()).toBe('/dashboard');
    expect(await runGuard(authGuard)).toBe(true);
  });

  it('returns to login after registration without creating an authenticated session', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const page = TestBed.runInInjectionContext(() => new RegisterPageComponent());
    page.form.setValue({
      username: ' owner ',
      password: 'long-password-123',
      confirmation: 'long-password-123',
    });
    const submitted = page.submit();
    const request = http.expectOne('/api/auth/register');
    expect(request.request.body).toEqual({
      username: 'owner',
      password: 'long-password-123',
      locale: 'pt-BR',
    });
    request.flush({ status: 'REGISTERED' }, { status: 201, statusText: 'Created' });
    await submitted;
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.registrationRequired()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { registered: true } });
  });

  it('returns to login if another browser finishes registration first', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const page = TestBed.runInInjectionContext(() => new RegisterPageComponent());
    page.form.setValue({
      username: 'owner',
      password: 'long-password-123',
      confirmation: 'long-password-123',
    });
    const submitted = page.submit();
    http.expectOne('/api/auth/register').flush({}, { status: 409, statusText: 'Conflict' });
    await submitted;
    expect(auth.registrationRequired()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { registrationClosed: true },
    });
  });

  it('validates confirmation and the UTF-8 password limit before submitting', () => {
    const page = TestBed.runInInjectionContext(() => new RegisterPageComponent());
    page.form.setValue({ username: 'owner', password: 'short', confirmation: 'short' });
    expect(page.form.controls.password.hasError('minlength')).toBe(true);
    page.form.setValue({ username: 'owner', password: 'secret', confirmation: 'secret' });
    expect(page.form.valid).toBe(true);
    page.form.setValue({
      username: 'owner',
      password: 'é'.repeat(40),
      confirmation: 'é'.repeat(40),
    });
    expect(page.form.controls.password.hasError('byteLimit')).toBe(true);
    page.form.setValue({
      username: 'owner',
      password: 'long-password-123',
      confirmation: 'other-password-123',
    });
    expect(page.form.hasError('passwordMismatch')).toBe(true);
    expect(page.form.invalid).toBe(true);
  });

  it('opens configuration onboarding after the first login', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const page = TestBed.runInInjectionContext(() => new LoginPageComponent());
    page.form.setValue({ username: 'owner', password: 'long-password-123' });
    const submitted = page.submit();
    http
      .expectOne('/api/auth/login')
      .flush({ status: 'AUTHENTICATED', onboardingCompleted: false });
    await submitted;
    expect(navigate).toHaveBeenCalledWith(['/onboarding']);
  });

  it('opens discovery on subsequent logins after onboarding is complete', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const page = TestBed.runInInjectionContext(() => new LoginPageComponent());
    page.form.setValue({ username: 'owner', password: 'long-password-123' });
    const submitted = page.submit();
    http.expectOne('/api/auth/login').flush({ status: 'AUTHENTICATED', onboardingCompleted: true });
    await submitted;
    expect(navigate).toHaveBeenCalledWith(['/discovery']);
  });
});
