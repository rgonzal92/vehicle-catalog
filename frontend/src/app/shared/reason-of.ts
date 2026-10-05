import { HttpErrorResponse } from '@angular/common/http';

/** The reason the API gave for refusing a request, or a general one when it gave none. */
export function reasonOf(error: unknown): string {
  const detail =
    error instanceof HttpErrorResponse ? (error.error as { detail?: unknown })?.detail : null;

  return typeof detail === 'string' && detail ? detail : 'The request was refused.';
}
