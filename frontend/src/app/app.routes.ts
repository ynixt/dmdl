import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { guestGuard } from './core/auth/guest.guard';
import { onboardingGuard } from './core/auth/onboarding.guard';
import { registrationGuard } from './core/auth/registration.guard';
import { mediaShowRoutes } from './pages/debrid/media-show.routes';
import { settingsLeaveGuard } from './pages/debrid/settings-leave.guard';

export const routes: Routes = [
  {
    path: 'offline',
    title: 'PAGES.OFFLINE.TITLE',
    loadComponent: () =>
      import('./pages/offline/offline.page').then((module) => module.OfflinePageComponent),
  },
  { path: '', pathMatch: 'full', redirectTo: 'discovery' },
  { path: 'dashboard', pathMatch: 'full', redirectTo: 'discovery' },
  {
    path: 'register',
    title: 'PAGES.REGISTER.TITLE',
    canActivate: [registrationGuard],
    loadComponent: () =>
      import('./pages/register/register.page').then((module) => module.RegisterPageComponent),
  },
  {
    path: 'login',
    title: 'PAGES.LOGIN.TITLE',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./pages/login/login.page').then((module) => module.LoginPageComponent),
  },
  {
    path: 'movie/:id',
    title: 'KZ.DISCOVERY',
    canActivate: [authGuard, onboardingGuard],
    loadComponent: () => import('./pages/debrid/movie.page').then((m) => m.MoviePage),
  },
  ...mediaShowRoutes().map((route) =>
    route.redirectTo !== undefined
      ? route
      : {
          ...route,
          title: 'KZ.DISCOVERY',
          canActivate: [authGuard, onboardingGuard],
        },
  ),

  {
    path: 'search',
    title: 'KZ.SEARCH',
    canActivate: [authGuard, onboardingGuard],
    loadComponent: () => import('./pages/debrid/search.page').then((module) => module.SearchPage),
  },
  {
    path: 'discovery',
    title: 'KZ.DISCOVERY',
    canActivate: [authGuard, onboardingGuard],
    loadComponent: () =>
      import('./pages/debrid/discovery.page').then((module) => module.DiscoveryPage),
  },
  {
    path: 'downloads',
    title: 'KZ.DOWNLOADS',
    canActivate: [authGuard, onboardingGuard],
    loadComponent: () =>
      import('./pages/debrid/downloads.page').then((module) => module.DownloadsPage),
  },
  {
    path: 'logs',
    title: 'LOGS.TITLE',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/logs/logs.page').then((module) => module.LogsPage),
  },
  {
    path: 'settings',
    title: 'KZ.SETTINGS',
    canActivate: [authGuard],
    canDeactivate: [settingsLeaveGuard],
    loadComponent: () =>
      import('./pages/debrid/settings.page').then((module) => module.SettingsPage),
  },
  {
    path: 'onboarding',
    title: 'KZ.SETUP',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./pages/debrid/onboarding.page').then((module) => module.OnboardingPage),
  },
  { path: '**', redirectTo: 'discovery' },
];
