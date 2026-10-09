import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { SignIn } from '@primeicons/angular/sign-in';
import { ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { TableModule } from 'primeng/table';
import { catchError, of } from 'rxjs';

/** A shared login a visitor can use, one per role. */
interface DemoAccount {
  role: string;
  username: string;
  password: string | null;
}

/** The public page a visitor sees first. */
@Component({
  imports: [SignIn, ButtonDirective, ButtonIcon, ButtonLabel, TableModule],
  selector: 'app-landing-page',
  template: `
    <main class="mx-auto grid max-w-3xl gap-10 px-6 py-16">
      <header class="grid justify-items-start gap-4">
        <span
          class="grid size-12 place-items-center rounded-border bg-primary text-lg font-semibold text-primary-contrast"
          aria-hidden="true"
          >VC</span
        >
        <h1 class="text-4xl font-semibold tracking-tight">Vehicle Catalog</h1>
        <p class="text-lg text-muted-color">
          State which features each trim of a vehicle line offers in each region, and the rules that
          relate them.
        </p>
        <a pButton size="large" href="/api/oauth2/authorization/cognito">
          <svg data-p-icon="sign-in" pButtonIcon />
          <span pButtonLabel>Sign in</span>
        </a>
      </header>

      <section class="surface" aria-labelledby="demo-accounts">
        <div class="surface-header">
          <h2 id="demo-accounts" class="font-semibold">Demo accounts</h2>
        </div>
        <p-table [value]="accounts()">
          <ng-template #header>
            <tr>
              <th scope="col">Role</th>
              <th scope="col">Username</th>
              <th scope="col">Password</th>
            </tr>
          </ng-template>
          <ng-template #body let-account>
            <tr>
              <td>{{ account.role }}</td>
              <td>{{ account.username }}</td>
              <td>{{ account.password || 'No password' }}</td>
            </tr>
          </ng-template>
        </p-table>
        <p class="border-t border-surface px-4 py-3 text-sm text-muted-color" data-reset-notice>
          This is a demo. Everything visitors do here is deleted every day at 03:00 UTC, including
          what the demo accounts own, their notifications, and the spreadsheets they exported. The
          accounts themselves stay, so you can sign in again.
        </p>
      </section>
    </main>
  `,
})
export class LandingPage {
  /** The list stays empty when the backend cannot be reached; sign-in is still offered. */
  protected readonly accounts = toSignal(
    inject(HttpClient)
      .get<DemoAccount[]>('/api/demo-accounts')
      .pipe(catchError(() => of<DemoAccount[]>([]))),
    { initialValue: [] as DemoAccount[] },
  );
}
