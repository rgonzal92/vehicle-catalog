import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Person, Role, Session } from './session';

/** Where a person belongs when a route is not theirs to open. */
function home(person: Person | null): string {
  if (!person) {
    return '/';
  }
  return person.roles.length === 0 ? '/no-role' : '/dashboard';
}

/** Opens the route to the people `allowed` accepts and sends everyone else where they belong. */
function openTo(allowed: (person: Person | null) => boolean): CanActivateFn {
  return async () => {
    const session = inject(Session);
    const router = inject(Router);
    const person = await session.load();

    return allowed(person) ? true : router.parseUrl(home(person));
  };
}

/** A page for visitors and for people with a role. A person without a role sees only their page. */
export const publicPage = openTo((person) => !person || person.roles.length > 0);

/** A page for people who hold the role, directly or through a higher one. */
export const holding = (role: Role) => openTo((person) => person?.roles.includes(role) ?? false);

/** The one page for a signed-in person without a role. */
export const noRolePage = openTo((person) => !!person && person.roles.length === 0);
