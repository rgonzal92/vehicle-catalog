import { Injectable } from '@angular/core';

/**
 * Shows the app light or dark, as the system prefers. The page's root element carries the class
 * `app-dark` while it is dark; the theme and Tailwind's `dark:` variant both go by that class.
 */
@Injectable({ providedIn: 'root' })
export class ColorScheme {
  constructor() {
    // A browser that cannot say what the system prefers gets light.
    const system = window.matchMedia?.('(prefers-color-scheme: dark)');
    this.show(system?.matches ?? false);
    system?.addEventListener('change', (preference) => this.show(preference.matches));
  }

  private show(dark: boolean): void {
    document.documentElement.classList.toggle('app-dark', dark);
  }
}
