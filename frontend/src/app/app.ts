import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Toast } from 'primeng/toast';
import { ColorScheme } from './core/color-scheme';
import { Tour } from './core/tour';
import { TourDialog } from './core/tour-dialog';

/**
 * The application shell: each page renders in its outlet, messages appear as toasts, and the tour
 * stays open from page to page. The tour's dialog is loaded once there is something to show.
 */
@Component({
  imports: [RouterOutlet, Toast, TourDialog],
  selector: 'app-root',
  template: `
    <router-outlet />
    <p-toast />
    @defer (when tour.shown()) {
      <app-tour-dialog />
    }
  `,
})
export class App {
  protected readonly tour = inject(Tour);

  constructor() {
    inject(ColorScheme);
    void this.tour.resume();
  }
}
