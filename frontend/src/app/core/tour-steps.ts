/** What a step is called in the tab's memory of the tour. */
export type StepId = 'about' | 'sign-in' | 'dashboard' | 'card';

/** One step of the tour: where it shows, the element it is about, and what it says. */
export interface TourStep {
  readonly id: StepId;
  /** The page the step is shown on; without one, it shows on whatever page the visitor is. */
  readonly path?: string;
  /** The element the step is about, which gets a ring; without one, it is about no one element. */
  readonly target?: string;
  readonly title: string;
  readonly words: string;
}

/** The tour's steps in order. The sign-in step leaves the app and the steps after it need a session. */
export const STEPS: readonly TourStep[] = [
  {
    id: 'about',
    path: '/',
    target: '[data-tour="about"]',
    title: 'What this app is for',
    words:
      'Carmakers decide which features each trim level of a vehicle gets in each market they sell it in: standard, optional, or not offered at all. This app is where that list is written, checked, and approved. I built catalog tools like this at a major US carmaker.',
  },
  {
    id: 'sign-in',
    path: '/',
    target: '[data-tour="sign-in"]',
    title: 'Sign in as the demo manager',
    words:
      "Everyone shares these demo logins. Copy the manager's username and password below, then sign in. The tour carries on once you're in.",
  },
  {
    id: 'dashboard',
    path: '/dashboard',
    target: '[data-tour="dashboard"]',
    title: 'Your dashboard',
    words:
      "My catalogs holds the copies being edited. Approved catalogs holds the published versions. The review queue holds the copies waiting for a manager's decision.",
  },
  {
    id: 'card',
    title: "That's the tour",
    words:
      'Everything done here is deleted at 03:00 UTC, so feel free to keep exploring. The code, the decision records, and the measurements are on GitHub.',
  },
];
