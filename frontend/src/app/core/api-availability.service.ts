import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable, Injector, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  catchError,
  finalize,
  firstValueFrom,
  Observable,
  shareReplay,
  tap,
  throwError,
  timeout,
  TimeoutError,
} from 'rxjs';
import { environment } from '../../environments/environment';
import { RegistrationStatus } from './auth/auth.models';

export type ApiUnavailableReason = 'starting' | 'not-responding' | 'unreachable';

@Injectable({ providedIn: 'root' })
export class ApiAvailabilityService {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private probeRequest?: Observable<RegistrationStatus>;
  private confirmation?: Promise<boolean>;
  readonly reached = signal(false);
  readonly reason = signal<ApiUnavailableReason>('unreachable');

  classify(error: unknown): ApiUnavailableReason | null {
    if (error instanceof TimeoutError) return 'unreachable';
    if (!(error instanceof HttpErrorResponse) || ![0, 502, 503, 504].includes(error.status)) {
      return null;
    }
    if (error.error?.code === 'BACKEND_UNAVAILABLE') {
      if (error.error.state === 'starting') return 'starting';
      if (error.error.state === 'ready') return 'not-responding';
    }
    return 'unreachable';
  }

  offlineUrl(returnUrl: string, error?: unknown) {
    const reason = this.classify(error);
    if (reason) this.reason.set(reason);
    return this.injector.get(Router).createUrlTree(['/offline'], { queryParams: { returnUrl } });
  }

  probe(): Observable<RegistrationStatus> {
    this.probeRequest ??= this.http
      .get<RegistrationStatus>(`${environment.apiBaseUrl}/auth/registration`)
      .pipe(
        timeout(12000),
        tap(() => this.reached.set(true)),
        catchError((error: unknown) => {
          const reason = this.classify(error);
          if (reason) this.reason.set(reason);
          return throwError(() => error);
        }),
        finalize(() => (this.probeRequest = undefined)),
        shareReplay({ bufferSize: 1, refCount: true }),
      );
    return this.probeRequest;
  }

  confirmAndGoOffline(returnUrl: string): Promise<boolean> {
    if (this.injector.get(Router).url.split('?')[0] === '/offline') return Promise.resolve(false);
    this.confirmation ??= this.confirm(returnUrl).finally(() => (this.confirmation = undefined));
    return this.confirmation;
  }

  private async confirm(returnUrl: string): Promise<boolean> {
    try {
      await firstValueFrom(this.probe());
      return false;
    } catch (error) {
      if (!this.classify(error)) return false;
      return this.injector.get(Router).navigateByUrl(this.offlineUrl(returnUrl));
    }
  }
}
