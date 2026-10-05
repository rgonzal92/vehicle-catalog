import { Routes } from '@angular/router';
import { anyone, holding, withoutRole } from './core/session-guard';

// Each page loads on first visit.
export const routes: Routes = [
  {
    path: '',
    title: 'Vehicle Catalog',
    canActivate: [anyone],
    loadComponent: () => import('./features/landing/landing-page').then((page) => page.LandingPage),
  },
  {
    path: 'dashboard',
    title: 'Dashboard · Vehicle Catalog',
    canActivate: [holding('author')],
    loadComponent: () =>
      import('./features/dashboard/dashboard-page').then((page) => page.DashboardPage),
  },
  {
    path: 'no-role',
    title: 'No role assigned · Vehicle Catalog',
    canActivate: [withoutRole],
    loadComponent: () => import('./features/no-role/no-role-page').then((page) => page.NoRolePage),
  },
];
