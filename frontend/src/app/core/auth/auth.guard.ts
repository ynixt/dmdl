import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.initializeSession().pipe(
    map((isAuthenticated) =>
      isAuthenticated
        ? true
        : router.createUrlTree([authService.registrationRequired() ? '/register' : '/login']),
    ),
    catchError(() => of(router.createUrlTree(['/login']))),
  );
};
