import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Catalogs, LineageSummary } from '../../core/catalogs';
import { Session } from '../../core/session';

/** The first page a signed-in person sees, with a section for each thing their role can do. */
@Component({
  imports: [DatePipe, RouterLink, Button, TableModule, Tag],
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
        @if (lineages().length > 0) {
          <p-table class="mt-2 block" [value]="lineages()">
            <ng-template #header>
              <tr>
                <th scope="col">Vehicle line</th>
                <th scope="col">Model year</th>
                <th scope="col">Version</th>
                <th scope="col">Approved</th>
                <th scope="col">Approved by</th>
              </tr>
            </ng-template>
            <ng-template #body let-lineage>
              <tr>
                <td>
                  <a
                    class="text-primary underline"
                    [routerLink]="['/approved', lineage.id]"
                    [attr.aria-label]="'Open ' + lineage.vehicleLine + ' ' + lineage.modelYear"
                  >
                    {{ lineage.vehicleLine }}
                  </a>
                </td>
                <td>{{ lineage.modelYear }}</td>
                <td>{{ lineage.versionNumber }}</td>
                <td>{{ lineage.approvedAt | date: 'mediumDate' }}</td>
                <td>{{ lineage.approvedBy }}</td>
              </tr>
            </ng-template>
          </p-table>
        } @else {
          <p class="mt-2 text-muted-color">There are no Approved catalogs.</p>
        }
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
            <li>
              <a class="text-primary underline" routerLink="/admin/trims">Trims</a>
            </li>
            <li>
              <a class="text-primary underline" routerLink="/admin/regions">Regions</a>
            </li>
            <li>
              <a class="text-primary underline" routerLink="/admin/features">Feature library</a>
            </li>
          </ul>
        </section>
      }
    </main>
  `,
})
export class DashboardPage {
  protected readonly session = inject(Session);

  /** The lineages that have an Approved version, each with its current one. */
  protected readonly lineages = signal<LineageSummary[]>([]);

  constructor() {
    void this.load(inject(Catalogs));
  }

  private async load(catalogs: Catalogs): Promise<void> {
    try {
      this.lineages.set(await catalogs.lineages());
    } catch {
      // The failure has already been shown as a message.
    }
  }
}
