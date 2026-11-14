import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';
import { ApiAvailabilityService } from '../api-availability.service';

export const guestGuard: CanActivateFn = (_route, state) => {
  const availability = inject(ApiAvailabilityService);
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.initializeSession().pipe(
    map((isAuthenticated) => {
      if (authService.registrationRequired()) return router.createUrlTree(['/register']);
      return isAuthenticated ? router.createUrlTree(['/dashboard']) : true;
    }),
    catchError((error) =>
      of(availability.classify(error) ? availability.offlineUrl(state.url, error) : true),
    ),
  );
};
