import { Component, inject } from '@angular/core';
import { Button } from 'primeng/button';
import { Session } from '../../core/session';

/** All that a signed-in person without a role can see. */
@Component({
  imports: [Button],
  selector: 'app-no-role-page',
  template: `
    <main class="mx-auto max-w-3xl px-6 py-16">
      <h1 class="text-4xl font-semibold">No role assigned</h1>
      <p class="mt-4 text-lg text-muted-color">
        You are signed in, but your account has no role yet. An admin can assign one.
      </p>
      <p-button class="mt-8 block" label="Sign out" (onClick)="session.signOut()" />
    </main>
  `,
})
export class NoRolePage {
  protected readonly session = inject(Session);
}
