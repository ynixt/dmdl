import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';
import { ApiAvailabilityService } from '../api-availability.service';

export const registrationGuard: CanActivateFn = (_route, state) => {
  const availability = inject(ApiAvailabilityService);
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.initializeSession().pipe(
    map((authenticated) => {
      if (authenticated) return router.createUrlTree(['/dashboard']);
      return auth.registrationRequired() ? true : router.createUrlTree(['/login']);
    }),
    catchError((error) =>
      of(
        availability.classify(error)
          ? availability.offlineUrl(state.url, error)
          : router.createUrlTree(['/login']),
      ),
    ),
  );
};
