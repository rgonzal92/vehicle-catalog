import { Component, inject } from '@angular/core';
import { Button } from 'primeng/button';
import { Session } from '../../core/session';

/** All that a signed-in person without a role can see. */
@Component({
  imports: [Button],
  selector: 'app-no-role-page',
  template: `
    <main class="mx-auto max-w-xl px-6 py-24">
      <div class="surface grid justify-items-center gap-4 p-8 text-center">
        <h1 class="text-2xl font-semibold">No role assigned</h1>
        <p class="text-muted-color">
          You are signed in, but your account has no role yet. An admin can assign one.
        </p>
        <p-button label="Sign out" severity="secondary" (onClick)="session.signOut()" />
      </div>
    </main>
  `,
})
export class NoRolePage {
  protected readonly session = inject(Session);
}
