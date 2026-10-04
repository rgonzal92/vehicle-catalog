import { Routes } from '@angular/router';

// Each page loads on first visit.
export const routes: Routes = [
  {
    path: '',
    title: 'Vehicle Catalog',
    loadComponent: () => import('./features/landing/landing-page').then((page) => page.LandingPage),
  },
];
