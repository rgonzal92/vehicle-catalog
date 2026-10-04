import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** The application shell: each page renders in its outlet. */
@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  template: '<router-outlet />',
})
export class App {}
