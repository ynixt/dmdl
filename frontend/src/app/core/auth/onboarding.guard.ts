import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, from, map, of } from 'rxjs';
import { DebridApi, Setup } from '../../services/debrid-api.service';
import { AuthService } from './auth.service';
import { ApiAvailabilityService } from '../api-availability.service';

export const onboardingGuard: CanActivateFn = (_route, state) => {
  const availability = inject(ApiAvailabilityService);
  const router = inject(Router);
  const completed = inject(AuthService).currentOnboardingCompleted();
  if (completed !== null) return completed ? true : router.createUrlTree(['/onboarding']);
  return from(inject(DebridApi).get<Setup>('/setup')).pipe(
    map((setup) => (setup.completed ? true : router.createUrlTree(['/onboarding']))),
    catchError((error) =>
      of(
        availability.classify(error)
          ? availability.offlineUrl(state.url, error)
          : router.createUrlTree(['/onboarding']),
      ),
    ),
  );
};
