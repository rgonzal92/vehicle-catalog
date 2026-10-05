import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role, Session } from './session';

const LANDING = '/';
const DASHBOARD = '/dashboard';
const NO_ROLE = '/no-role';

/** Open to everyone, except that a signed-in person without a role sees only the no-role page. */
export const anyone: CanActivateFn = async () => {
  const session = inject(Session);
  const router = inject(Router);
  const person = await session.load();

  return person && person.roles.length === 0 ? router.parseUrl(NO_ROLE) : true;
};

/**
 * Open to people who hold the role. A visitor goes to the landing page, a person without any role
 * to the no-role page, and a person with a lower role to the dashboard.
 */
export function holding(role: Role): CanActivateFn {
  return async () => {
    const session = inject(Session);
    const router = inject(Router);
    const person = await session.load();

    if (!person) {
      return router.parseUrl(LANDING);
    }
    if (person.roles.length === 0) {
      return router.parseUrl(NO_ROLE);
    }
    return person.roles.includes(role) ? true : router.parseUrl(DASHBOARD);
  };
}

/** Open only to a signed-in person without a role. */
export const withoutRole: CanActivateFn = async () => {
  const session = inject(Session);
  const router = inject(Router);
  const person = await session.load();

  if (!person) {
    return router.parseUrl(LANDING);
  }
  return person.roles.length === 0 ? true : router.parseUrl(DASHBOARD);
};
