/** What a step is called in the tab's memory of the tour. */
export type StepId =
  | 'about'
  | 'sign-in'
  | 'dashboard'
  | 'approved'
  | 'copy'
  | 'break'
  | 'fix'
  | 'card';

/** A cell of the matrix, by its feature's code, its trim's name, and its region's code. */
export interface CellAt {
  readonly feature: string;
  readonly trim: string;
  readonly region: string;
}

/** The lineage the tour opens and copies, by its vehicle line's name and its model year. */
export const SEDAN = { vehicleLine: 'Sedan', modelYear: 2027 };

/**
 * The cell the tour breaks a rule with: heated front seats on the Luxury trim in North America.
 * A seeded global rule says heated rear seats need heated front seats, and Luxury offers heated
 * rear seats there, so not offering the front ones is an Error. It holds both in the seeded
 * Approved Sedan 2027 and in the version after it, once the seeded submission is approved.
 */
export const BROKEN: CellAt = { feature: 'SEAT_HEATED_FRONT', trim: 'Luxury', region: 'NA' };
const BROKEN_CELL = `td[data-cell="${BROKEN.feature} ${BROKEN.trim} ${BROKEN.region}"]`;

/** One step of the tour: where it shows, the element it is about, and what it says. */
export interface TourStep {
  readonly id: StepId;
  /**
   * The page the step is shown on; without one, it shows on whatever page the visitor is. In it,
   * `{sedan}` stands for the lineage of Sedan 2027 and `{copy}` for the working copy the tour made.
   */
  readonly path?: string;
  /** The cell of the matrix the step is about, which the catalog's editor brings into view. */
  readonly cell?: CellAt;
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
    id: 'approved',
    path: '/approved/{sedan}',
    target: '[data-tour="approved-matrix"]',
    title: 'A published catalog',
    words:
      'This is the published catalog for the 2027 Sedan. Each row is a feature and each column is a trim level sold in a market. S means standard, A means available as an option, and - means not offered.',
  },
  {
    id: 'copy',
    path: '/approved/{sedan}',
    target: '[data-tour="create-copy"]',
    title: 'Make a working copy',
    words:
      'A published catalog never changes. To change it, you make a working copy, edit that, and send it for review. Press Create working copy, then Create.',
  },
  {
    id: 'break',
    path: '/catalogs/{copy}',
    cell: BROKEN,
    target: BROKEN_CELL,
    title: 'Break a rule',
    words:
      'Rules keep a catalog consistent: some features need others to be offered. Set this cell to - and watch: the app checks every rule on each save and marks what breaks as an Error.',
  },
  {
    id: 'fix',
    path: '/catalogs/{copy}',
    cell: BROKEN,
    target: BROKEN_CELL,
    title: 'Put it right',
    words:
      "Set the cell back to what it was and the Error goes away. A copy with an Error can't be sent for review, so a mistake never reaches the approver.",
  },
  {
    id: 'card',
    title: "That's the tour",
    words:
      'Everything done here is deleted at 03:00 UTC, so feel free to keep exploring. The code, the decision records, and the measurements are on GitHub.',
  },
];
