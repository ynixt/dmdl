import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, from, map, of } from 'rxjs';
import { DebridApi, Setup } from '../../services/debrid-api.service';
import { AuthService } from './auth.service';

export const onboardingGuard: CanActivateFn = () => {
  const router = inject(Router);
  const completed = inject(AuthService).currentOnboardingCompleted();
  if (completed !== null) return completed ? true : router.createUrlTree(['/onboarding']);
  return from(inject(DebridApi).get<Setup>('/setup')).pipe(
    map((setup) => (setup.completed ? true : router.createUrlTree(['/onboarding']))),
    catchError(() => of(router.createUrlTree(['/onboarding']))),
  );
};
