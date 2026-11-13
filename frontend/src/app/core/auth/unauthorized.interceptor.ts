import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const unauthorizedInterceptor: HttpInterceptorFn = (request, next) => {
  const authService = inject(AuthService);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      const isUnauthorizedError = error.status === 401 || error.status === 403;
      if (isUnauthorizedError && isApiRequest(request.url) && !isAuthPublicEndpoint(request.url)) {
        return from(authService.expireSession()).pipe(switchMap(() => throwError(() => error)));
      }

      return throwError(() => error);
    }),
  );
};

function isApiRequest(url: string): boolean {
  const path = new URL(url, 'http://localhost').pathname;
  return path === '/api' || path.startsWith('/api/');
}

function isAuthPublicEndpoint(url: string): boolean {
  return [
    '/api/auth/login',
    '/api/auth/logout',
    '/api/auth/register',
    '/api/auth/registration',
  ].includes(new URL(url, 'http://localhost').pathname);
}
