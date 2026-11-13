import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { guestGuard } from './core/auth/guest.guard';
import { onboardingGuard } from './core/auth/onboarding.guard';
import { registrationGuard } from './core/auth/registration.guard';
import { ReleasesRoutePage } from './pages/debrid/releases-route.page';

export const routes: Routes = [
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
    path: 'discovery/releases/:addonId/:type/:id/:season/:episode',
    title: 'KZ.RELEASES',
    canActivate: [authGuard, onboardingGuard],
    component: ReleasesRoutePage,
  },
  {
    path: 'discovery/releases/:addonId/:type/:id',
    title: 'KZ.RELEASES',
    canActivate: [authGuard, onboardingGuard],
    component: ReleasesRoutePage,
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
    path: 'settings',
    title: 'KZ.SETTINGS',
    canActivate: [authGuard],
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
