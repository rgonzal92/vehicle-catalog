import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Toast } from 'primeng/toast';

/** The application shell: each page renders in its outlet, and messages appear as toasts. */
@Component({
  imports: [RouterOutlet, Toast],
  selector: 'app-root',
  template: '<router-outlet /><p-toast />',
})
export class App {}
