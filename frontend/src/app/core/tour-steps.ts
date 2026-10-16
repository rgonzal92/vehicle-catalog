/** What a step is called in the tab's memory of the tour. */
export type StepId =
  | 'about'
  | 'sign-in'
  | 'dashboard'
  | 'approved'
  | 'copy'
  | 'break'
  | 'fix'
  | 'review'
  | 'analyst'
  | 'card';

/** A cell of the matrix, by its feature's code, its trim's name, and its region's code. */
export interface CellAt {
  readonly feature: string;
  readonly trim: string;
  readonly region: string;
}

/** A few words on how a step's part of the app is built, with the record in the code that says more. */
export interface Note {
  readonly words: string;
  readonly link: string;
}

/** Where the records a note links to are, on the branch the live app is built from. */
const RECORDS = 'https://github.com/rgonzal92/vehicle-catalog/blob/main/';

/** The lineage the tour opens and copies, by its vehicle line's name and its model year. */
export const SEDAN = { vehicleLine: 'Sedan', modelYear: 2027 };

/**
 * The cell the tour breaks a rule with: heated front seats on the Luxury trim in North America.
 * A seeded global rule says heated rear seats need heated front seats, and Luxury offers heated
 * rear seats there, so not offering the front ones is an Error. It holds both in the seeded
 * Approved Sedan 2027 and in the version after it, once the seeded submission is approved.
 */
export const BROKEN: CellAt = { feature: 'SEAT_HEATED_FRONT', trim: 'Luxury', region: 'NA' };

/** Where a cell of the matrix is on the page. */
export const cellOnPage = (cell: CellAt) =>
  `td[data-cell="${cell.feature} ${cell.trim} ${cell.region}"]`;

/** One step of the tour: where it shows, the element it is about, and what it says. */
export interface TourStep {
  readonly id: StepId;
  /**
   * The page the step is shown on; without one, it shows on whatever page the visitor is. In it,
   * `{sedan}` stands for the lineage of Sedan 2027, `{copy}` for the working copy the tour made, and
   * `{review}` for the first catalog waiting for a review that is not the manager's own.
   */
  readonly path?: string;
  /** The cell of the matrix the step is about, which the catalog's editor brings into view. */
  readonly cell?: CellAt;
  /**
   * The elements the step is about, which get a ring, the first of them the one it points to;
   * without any, it is about no one element.
   */
  readonly target?: string;
  readonly title: string;
  readonly words: string;
  /** How the part of the app the step shows is built, for a visitor who opens it. */
  readonly note?: Note;
  /** The words of the review's step when no catalog waits for a review, and for an author. */
  readonly noneWaiting?: string;
  readonly forAuthors?: string;
  /** The question the step fills in for the analyst. */
  readonly question?: string;
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
    note: {
      words:
        'An Angular and PrimeNG frontend, a Spring Boot API and a worker, PostgreSQL, Redis, and an OpenAI model, on one AWS machine behind CloudFront.',
      link: `${RECORDS}README.md`,
    },
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
    note: {
      words:
        'The matrix is one PrimeNG table that draws only the rows on screen, so a catalog of 500 features scrolls smoothly.',
      link: `${RECORDS}docs/adr/0006-the-matrix-is-one-primeng-table-with-its-own-subheaders-and-cell-editing.md`,
    },
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
    target: cellOnPage(BROKEN),
    title: 'Break a rule',
    words:
      'Rules keep a catalog consistent: some features need others to be offered. Set this cell to - and watch: the app checks every rule on each save and marks what breaks as an Error.',
    note: {
      words:
        'Every save checks the whole catalog against every rule in plain Java. A one-cell save on the largest catalog is answered in a median of 57 ms.',
      link: `${RECORDS}docs/largest-catalog-measurements.md`,
    },
  },
  {
    id: 'fix',
    path: '/catalogs/{copy}',
    cell: BROKEN,
    target: `${cellOnPage(BROKEN)}, [data-issue-counts]`,
    title: 'Put it right',
    words:
      "Set the cell back to what it was and the Error goes away. A copy with an Error can't be sent for review, so a mistake never reaches the approver.",
  },
  {
    id: 'review',
    path: '/catalogs/{review}/review',
    target: '[data-tour="decide"]',
    title: "A manager's review",
    words:
      'Here a manager sees what a submitted copy changes, with a short summary written by an AI model, then approves or rejects it. Approving makes it the next published version. The tour leaves this decision for you or the next visitor.',
    noneWaiting: 'Nothing is waiting for review right now. Reviews happen here.',
    forAuthors: 'Managers review submissions here. Sign in as manager to see it.',
    note: {
      words:
        "Every change names the revision it was made from, so two people sharing a login can't overwrite each other's work.",
      link: `${RECORDS}GLOSSARY.md`,
    },
  },
  {
    id: 'analyst',
    path: '/analyst',
    target: '[data-tour="ask"]',
    title: 'Ask the analyst',
    words:
      'The analyst answers questions about the published catalogs. The AI model can only look things up through the app, and each answer lists the lookups it made and the notes it quoted.',
    question: 'What changed between the two Approved versions of the 2026 Compact SUV?',
    note: {
      words:
        'The model proposes, and Java checks: every lookup is one the app offers, and every citation is checked before it is shown.',
      link: `${RECORDS}docs/adr/0013-the-model-proposes-java-checks-and-a-person-saves.md`,
    },
  },
  {
    id: 'card',
    title: "That's the tour",
    words:
      'Everything done here is deleted at 03:00 UTC, so feel free to keep exploring. The code, the decision records, and the measurements are on GitHub.',
    note: {
      words:
        'It runs on AWS, set up with Terraform, with alarms that send email, a nightly backup that has been restored on a clean machine, and traces of slow requests.',
      link: `${RECORDS}infra/README.md`,
    },
  },
];
