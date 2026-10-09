import { computed, Injectable, signal } from '@angular/core';

/** Where the browser keeps the scheme a person chose. */
const CHOICE = 'color-scheme';

/**
 * Shows the app light or dark: as the person chose, or without a choice as the system prefers. The
 * page's root element carries the class `app-dark` while it is dark; the theme and Tailwind's
 * `dark:` variant both go by that class.
 */
@Injectable({ providedIn: 'root' })
export class ColorScheme {
  // A browser that cannot say what the system prefers gets light.
  private readonly system = signal(false);
  private readonly choice = signal(localStorage.getItem(CHOICE));

  /** Whether the app is shown dark. */
  readonly dark = computed(() => (this.choice() ?? (this.system() ? 'dark' : 'light')) === 'dark');

  constructor() {
    const preference = window.matchMedia?.('(prefers-color-scheme: dark)');
    this.system.set(preference?.matches ?? false);
    preference?.addEventListener('change', (changed) => {
      this.system.set(changed.matches);
      this.show();
    });
    this.show();
  }

  /** Shows the other scheme, and remembers that the person chose it. */
  toggle(): void {
    const choice = this.dark() ? 'light' : 'dark';
    localStorage.setItem(CHOICE, choice);
    this.choice.set(choice);
    this.show();
  }

  private show(): void {
    document.documentElement.classList.toggle('app-dark', this.dark());
  }
}
