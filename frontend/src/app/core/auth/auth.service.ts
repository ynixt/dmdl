import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, Injector, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  catchError,
  finalize,
  lastValueFrom,
  map,
  Observable,
  of,
  shareReplay,
  switchMap,
  take,
  tap,
  throwError,
} from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiAvailabilityService } from '../api-availability.service';
import { LoginRequest, LoginResponse, RegisterRequest, SessionResponse } from './auth.models';

type AuthStatus = 'unknown' | 'authenticated' | 'unauthenticated';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly availability = inject(ApiAvailabilityService);
  private readonly injector = inject(Injector);
  private readonly authStatus = signal<AuthStatus>('unknown');
  private readonly username = signal<string | null>(null);
  private readonly backendLocale = signal<string | null>(null);
  private readonly onboardingCompleted = signal<boolean | null>(null);
  private readonly registrationNeeded = signal<boolean | null>(null);
  private sessionCheckRequest?: Observable<boolean>;
  private loginRedirect?: Promise<boolean>;

  readonly isAuthenticated = computed(() => this.authStatus() === 'authenticated');
  readonly currentUsername = this.username.asReadonly();
  readonly currentBackendLocale = this.backendLocale.asReadonly();
  readonly currentOnboardingCompleted = this.onboardingCompleted.asReadonly();
  readonly registrationRequired = this.registrationNeeded.asReadonly();

  initializeSession(): Observable<boolean> {
    if (this.authStatus() !== 'unknown') {
      return of(this.isAuthenticated());
    }

    return this.fetchSession();
  }

  register(request: RegisterRequest): Promise<{ status: string }> {
    return lastValueFrom(
      this.http.post<{ status: string }>(`${environment.apiBaseUrl}/auth/register`, request).pipe(
        tap(() => {
          this.registrationNeeded.set(false);
          this.clearSession();
        }),
        catchError((error: HttpErrorResponse) => {
          if (error.status === 409) this.registrationNeeded.set(false);
          return throwError(() => error);
        }),
      ),
    );
  }

  login(request: LoginRequest): Promise<LoginResponse> {
    return lastValueFrom(
      this.http.post<LoginResponse>(`${environment.apiBaseUrl}/auth/login`, request).pipe(
        take(1),
        tap((response) => {
          this.authStatus.set('authenticated');
          this.registrationNeeded.set(false);
          this.username.set(request.username);
          this.backendLocale.set(response.locale ?? null);
          this.onboardingCompleted.set(response.onboardingCompleted ?? null);
        }),
      ),
    );
  }

  logout(): Promise<void> {
    return lastValueFrom(
      this.http.post(`${environment.apiBaseUrl}/auth/logout`, {}).pipe(
        take(1),
        map(() => void 0),
        catchError(() => of(void 0)),
        tap(() => this.clearSession()),
      ),
    );
  }

  expireSession(): Promise<boolean> {
    this.clearSession();
    if (this.loginRedirect) return this.loginRedirect;
    const router = this.injector.get(Router);
    if (router.url.split('?')[0] === '/login') return Promise.resolve(true);
    this.loginRedirect = router.navigate(['/login'], { replaceUrl: true }).finally(() => {
      this.loginRedirect = undefined;
    });
    return this.loginRedirect;
  }

  refreshSession(): Promise<boolean> {
    return this.resolveSession();
  }

  invalidateSession(): void {
    this.authStatus.set('unknown');
  }

  private fetchSession(): Observable<boolean> {
    if (this.sessionCheckRequest) {
      return this.sessionCheckRequest;
    }

    this.sessionCheckRequest = this.availability.probe().pipe(
      tap((status) => this.registrationNeeded.set(status.registrationRequired)),
      switchMap((status) =>
        status.registrationRequired
          ? of(null)
          : this.http.get<SessionResponse>(`${environment.apiBaseUrl}/auth/session`),
      ),
      map((session) => {
        if (session === null) {
          this.clearSession();
          return false;
        }
        this.authStatus.set('authenticated');
        this.username.set(session.username);
        this.backendLocale.set(session.locale ?? null);
        this.onboardingCompleted.set(session.onboardingCompleted ?? null);
        return true;
      }),
      catchError((error: HttpErrorResponse) => {
        if (error.status === 401 || error.status === 403) {
          this.clearSession();
          return of(false);
        }

        return throwError(() => error);
      }),
      finalize(() => {
        this.sessionCheckRequest = undefined;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    return this.sessionCheckRequest;
  }

  private async resolveSession(): Promise<boolean> {
    try {
      const session = await lastValueFrom(
        this.http.get<SessionResponse>(`${environment.apiBaseUrl}/auth/session`).pipe(take(1)),
      );
      this.authStatus.set('authenticated');
      this.username.set(session.username);
      this.backendLocale.set(session.locale ?? null);
      this.onboardingCompleted.set(session.onboardingCompleted ?? null);
      return true;
    } catch (error) {
      if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
        this.clearSession();
        return false;
      }

      throw error;
    }
  }

  private clearSession(): void {
    this.authStatus.set('unauthenticated');
    this.username.set(null);
    this.backendLocale.set(null);
    this.onboardingCompleted.set(null);
  }
}
