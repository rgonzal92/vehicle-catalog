import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ButtonDirective, ButtonLabel } from 'primeng/button';
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
  imports: [ButtonDirective, ButtonLabel, TableModule],
  selector: 'app-landing-page',
  template: `
    <main class="mx-auto max-w-3xl px-6 py-16">
      <h1 class="text-4xl font-semibold">Vehicle Catalog</h1>
      <p class="mt-4 text-lg text-muted-color">
        State which features each trim of a vehicle line offers in each region, and the rules that
        relate them.
      </p>

      <a pButton class="mt-8" href="/api/oauth2/authorization/cognito">
        <span pButtonLabel>Sign in</span>
      </a>

      <h2 id="demo-accounts" class="mt-12 text-xl font-semibold">Demo accounts</h2>
      <p-table class="mt-4 block" [value]="accounts()" aria-labelledby="demo-accounts">
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
