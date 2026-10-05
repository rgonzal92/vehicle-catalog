import { Component, inject } from '@angular/core';
import { Button } from 'primeng/button';
import { SessionService } from '../../core/session.service';

/** The first page a signed-in person sees. */
@Component({
  imports: [Button],
  selector: 'app-dashboard-page',
  template: `
    <main class="mx-auto max-w-5xl px-6 py-10">
      <header class="flex items-center justify-between gap-4">
        <h1 class="text-2xl font-semibold">Dashboard</h1>
        <div class="flex items-center gap-4">
          <span>{{ session.person()?.name }}</span>
          <p-button label="Sign out" severity="secondary" (onClick)="session.signOut()" />
        </div>
      </header>
    </main>
  `,
})
export class DashboardPage {
  protected readonly session = inject(SessionService);
}
