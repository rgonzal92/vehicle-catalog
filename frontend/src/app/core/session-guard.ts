import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Session } from './session';

/** Lets a signed-in person through and leads anyone else to the landing page. */
export const signedIn: CanActivateFn = async () => {
  const session = inject(Session);
  const landing = inject(Router).parseUrl('/');

  return (await session.load()) ? true : landing;
};
