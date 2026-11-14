import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject, Injector } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { ApiAvailabilityService } from './api-availability.service';
import { AuthService } from './auth/auth.service';

export const availabilityInterceptor: HttpInterceptorFn = (request, next) => {
  const path = new URL(request.url, 'http://localhost').pathname;
  if (!path.startsWith('/api/')) return next(request);

  const availability = inject(ApiAvailabilityService);
  const auth = inject(AuthService);
  const injector = inject(Injector);

  const publicEndpoint = [
    '/api/auth/login',
    '/api/auth/logout',
    '/api/auth/register',
    '/api/auth/registration',
  ].includes(path);
  return next(request).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) availability.reached.set(true);
    }),
    catchError((error: unknown) => {
      if (!publicEndpoint && auth.isAuthenticated() && availability.classify(error)) {
        void availability.confirmAndGoOffline(injector.get(Router).url);
      }
      return throwError(() => error);
    }),
  );
};
