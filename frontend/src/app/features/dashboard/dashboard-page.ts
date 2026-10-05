import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from 'primeng/button';
import { Tag } from 'primeng/tag';
import { Session } from '../../core/session';

/** The first page a signed-in person sees, with a section for each thing their role can do. */
@Component({
  imports: [RouterLink, Button, Tag],
  selector: 'app-dashboard-page',
  template: `
    <main class="mx-auto max-w-5xl px-6 py-10">
      <header class="flex items-center justify-between gap-4">
        <h1 class="text-2xl font-semibold">Dashboard</h1>
        <div class="flex items-center gap-4">
          <span>{{ session.person()?.name }}</span>
          <p-tag data-role severity="secondary" [value]="session.role() ?? undefined" />
          <p-button label="Sign out" severity="secondary" (onClick)="session.signOut()" />
        </div>
      </header>

      <section class="mt-10" aria-labelledby="my-catalogs">
        <h2 id="my-catalogs" class="text-xl font-semibold">My catalogs</h2>
        <p class="mt-2 text-muted-color">You have no catalogs.</p>
      </section>

      <section class="mt-10" aria-labelledby="approved-catalogs">
        <h2 id="approved-catalogs" class="text-xl font-semibold">Approved catalogs</h2>
        <p class="mt-2 text-muted-color">There are no Approved catalogs.</p>
      </section>

      @if (session.holds('manager')) {
        <section class="mt-10" aria-labelledby="review-queue">
          <h2 id="review-queue" class="text-xl font-semibold">Review queue</h2>
          <p class="mt-2 text-muted-color">Nothing is waiting for review.</p>
        </section>
      }

      @if (session.holds('admin')) {
        <section class="mt-10" aria-labelledby="admin-links">
          <h2 id="admin-links" class="text-xl font-semibold">Admin links</h2>
          <ul class="mt-2">
            <li>
              <a class="text-primary underline" routerLink="/admin/vehicle-lines">Vehicle lines</a>
            </li>
          </ul>
        </section>
      }
    </main>
  `,
})
export class DashboardPage {
  protected readonly session = inject(Session);
}
