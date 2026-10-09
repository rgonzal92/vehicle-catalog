import { TestBed } from '@angular/core/testing';
import { ColorScheme } from './color-scheme';

describe('ColorScheme', () => {
  /** A system that prefers dark or light, and can be told to change its mind. */
  function system(dark: boolean) {
    const listeners: ((event: { matches: boolean }) => void)[] = [];
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(prefers-color-scheme: dark)' && dark,
      addEventListener: (_: string, listener: (event: { matches: boolean }) => void) =>
        listeners.push(listener),
    }));
    return {
      prefers: (dark: boolean) => listeners.forEach((listener) => listener({ matches: dark })),
    };
  }

  const shownDark = () => document.documentElement.classList.contains('app-dark');

  afterEach(() => {
    vi.unstubAllGlobals();
    document.documentElement.classList.remove('app-dark');
  });

  it('shows the app dark on a system that prefers dark', () => {
    system(true);

    TestBed.inject(ColorScheme);

    expect(shownDark()).toBe(true);
  });

  it('shows the app light on a system that prefers light', () => {
    system(false);

    TestBed.inject(ColorScheme);

    expect(shownDark()).toBe(false);
  });

  it('follows the system when its preference changes', () => {
    const preference = system(false);
    TestBed.inject(ColorScheme);

    preference.prefers(true);
    expect(shownDark()).toBe(true);

    preference.prefers(false);
    expect(shownDark()).toBe(false);
  });

  it('shows the app light where nothing says what the system prefers', () => {
    TestBed.inject(ColorScheme);

    expect(shownDark()).toBe(false);
  });
});
