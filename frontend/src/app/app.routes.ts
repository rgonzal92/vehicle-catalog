import { Routes } from '@angular/router';
import { holding, noRolePage, publicPage } from './core/session-guard';
import { REGIONS, TRIMS } from './features/admin/library-lists/library-list';

// Each page loads on first visit.
export const routes: Routes = [
  {
    path: '',
    title: 'Vehicle Catalog',
    canActivate: [publicPage],
    loadComponent: () => import('./features/landing/landing-page').then((page) => page.LandingPage),
  },
  {
    path: 'no-role',
    title: 'No role assigned · Vehicle Catalog',
    canActivate: [noRolePage],
    loadComponent: () => import('./features/no-role/no-role-page').then((page) => page.NoRolePage),
  },
  // Every page for a signed-in person with a role renders inside the frame.
  {
    path: '',
    loadComponent: () => import('./core/shell').then((frame) => frame.Shell),
    children: [
      {
        path: 'dashboard',
        title: 'Dashboard · Vehicle Catalog',
        canActivate: [holding('author')],
        loadComponent: () =>
          import('./features/dashboard/dashboard-page').then((page) => page.DashboardPage),
      },
      {
        path: 'approved/:lineageId',
        title: 'Approved catalog · Vehicle Catalog',
        canActivate: [holding('author')],
        loadComponent: () =>
          import('./features/approved/approved-page').then((page) => page.ApprovedPage),
      },
      {
        path: 'catalogs/:id',
        title: 'Catalog · Vehicle Catalog',
        canActivate: [holding('author')],
        loadComponent: () =>
          import('./features/catalog-editor/catalog-editor-page').then(
            (page) => page.CatalogEditorPage,
          ),
      },
      {
        path: 'admin/vehicle-lines',
        title: 'Vehicle lines · Vehicle Catalog',
        canActivate: [holding('admin')],
        loadComponent: () =>
          import('./features/admin/vehicle-lines/vehicle-lines-page').then(
            (page) => page.VehicleLinesPage,
          ),
      },
      {
        path: 'admin/trims',
        title: 'Trims · Vehicle Catalog',
        canActivate: [holding('admin')],
        data: { list: TRIMS },
        loadComponent: () =>
          import('./features/admin/library-lists/library-list-page').then(
            (page) => page.LibraryListPage,
          ),
      },
      {
        path: 'admin/regions',
        title: 'Regions · Vehicle Catalog',
        canActivate: [holding('admin')],
        data: { list: REGIONS },
        loadComponent: () =>
          import('./features/admin/library-lists/library-list-page').then(
            (page) => page.LibraryListPage,
          ),
      },
      {
        path: 'admin/features',
        title: 'Feature library · Vehicle Catalog',
        canActivate: [holding('admin')],
        loadComponent: () =>
          import('./features/admin/feature-library/feature-library-page').then(
            (page) => page.FeatureLibraryPage,
          ),
      },
      {
        path: 'admin/users',
        title: 'Users · Vehicle Catalog',
        canActivate: [holding('admin')],
        loadComponent: () =>
          import('./features/admin/users/users-page').then((page) => page.UsersPage),
      },
    ],
  },
  // The matrix at full size on generated data. A production build has neither the route nor the page.
  ...(ngDevMode
    ? [
        {
          path: 'dev/matrix',
          title: 'Matrix proof · Vehicle Catalog',
          loadComponent: () =>
            import('./features/matrix-proof/matrix-proof-page').then(
              (page) => page.MatrixProofPage,
            ),
        },
      ]
    : []),
  // Any other address leads to the landing page, whose guard sends each person where they belong.
  { path: '**', redirectTo: '' },
];
