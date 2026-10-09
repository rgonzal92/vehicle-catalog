import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { tap } from 'rxjs';
import { Session } from './session';

/**
 * Marks a request that the person did not ask for, such as looking for new notifications every
 * minute. When the server or the network fails it, nothing is shown: the person was not waiting
 * for it, and the next one may succeed.
 */
export const IN_THE_BACKGROUND = new HttpContextToken(() => false);

/**
 * Handles the API failures no caller can explain. A server or network failure is shown as a
 * message, and a session that has ended sends the person back to the landing page. Refusals with a
 * reason, such as a conflict, are left to the caller.
 */
export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(Session);
  const router = inject(Router);
  const messages = inject(MessageService);

  return next(request).pipe(
    tap({
      error: (error: unknown) => {
        if (!(error instanceof HttpErrorResponse)) {
          return;
        }

        if (error.status === 0 || error.status >= 500) {
          if (request.context.get(IN_THE_BACKGROUND)) {
            return;
          }
          messages.add({
            severity: 'error',
            summary: 'Something went wrong',
            detail: 'The request could not be completed.',
          });
        } else if (error.status === 401 && !request.url.endsWith('/api/me')) {
          // Asking who is signed in answers 401 without a session; that is not an ended session.
          session.expire();
          void router.navigateByUrl('/');
        }
      },
    }),
  );
};
