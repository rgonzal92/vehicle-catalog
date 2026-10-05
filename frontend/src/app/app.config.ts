import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import Aura from '@primeuix/themes/aura';
import { MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';
import { routes } from './app.routes';
import { apiErrorInterceptor } from './core/api-error-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // HttpClient echoes the XSRF-TOKEN cookie in the X-XSRF-TOKEN header on writes by default.
    provideHttpClient(withInterceptors([apiErrorInterceptor])),
    // Carries the messages the toast in the application shell shows.
    MessageService,
    providePrimeNG({
      theme: {
        preset: Aura,
        options: {
          cssLayer: { name: 'primeng', order: 'theme, base, primeng, components, utilities' },
        },
      },
      license: typeof PRIMEUI_LICENSE_KEY === 'undefined' ? undefined : PRIMEUI_LICENSE_KEY,
    }),
  ],
};
