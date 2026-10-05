import { Routes } from '@angular/router';
import { signedIn } from './core/session.guard';

// Each page loads on first visit.
export const routes: Routes = [
  {
    path: '',
    title: 'Vehicle Catalog',
    loadComponent: () => import('./features/landing/landing-page').then((page) => page.LandingPage),
  },
  {
    path: 'dashboard',
    title: 'Dashboard · Vehicle Catalog',
    canActivate: [signedIn],
    loadComponent: () =>
      import('./features/dashboard/dashboard-page').then((page) => page.DashboardPage),
  },
];
