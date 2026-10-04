import { Component } from '@angular/core';

/** The public page a visitor sees first. */
@Component({
  selector: 'app-landing-page',
  template: `
    <main class="mx-auto max-w-3xl px-6 py-16">
      <h1 class="text-4xl font-semibold">Vehicle Catalog</h1>
      <p class="mt-4 text-lg text-muted-color">
        State which features each trim of a vehicle line offers in each region, and the rules that
        relate them.
      </p>
    </main>
  `,
})
export class LandingPage {}
