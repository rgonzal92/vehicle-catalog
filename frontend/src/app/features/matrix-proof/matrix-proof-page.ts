import { ApplicationRef, Component, ElementRef, inject, signal } from '@angular/core';
import { Button } from 'primeng/button';
import { AvailabilityMatrix } from '../../shared/availability-matrix/availability-matrix';
import { Cell, MatrixContents } from '../../shared/availability-matrix/matrix';

const CATEGORIES = [
  'Powertrain',
  'Chassis',
  'Steering',
  'Braking',
  'Wheels and tires',
  'Exterior',
  'Interior',
  'Electrical',
  'Climate',
  'Safety and driver assistance',
  'Infotainment',
  'Thermal',
  'Packages',
].map((name, index) => ({ code: `CATEGORY_${index + 1}`, name }));

/** A generated catalog of the largest size: 500 feature rows by 12 trims sold in 8 regions. */
function largestCatalog(): MatrixContents {
  const trims = Array.from({ length: 12 }, (_, index) => ({
    id: index + 1,
    name: `Trim ${index + 1}`,
    sortOrder: index + 1,
  }));
  const regions = Array.from({ length: 8 }, (_, index) => ({
    code: `R${index + 1}`,
    name: `Region ${index + 1}`,
  }));
  const offerings = regions.flatMap((region) =>
    trims.map((trim) => ({ trimId: trim.id, regionCode: region.code })),
  );
  const features = Array.from({ length: 500 }, (_, index) => ({
    id: index + 1,
    code: `FEATURE_${String(index + 1).padStart(3, '0')}`,
    name: `Feature ${index + 1}`,
    categoryCode: CATEGORIES[index % CATEGORIES.length].code,
  }));
  // A fixed pattern, so every run shows and measures the same matrix: about a quarter Standard, a
  // quarter Available, and half Not offered.
  const cells = features.flatMap((feature) =>
    offerings.flatMap((offering, column): Cell[] => {
      const pick = (feature.id * 7 + column * 3) % 4;
      return pick > 1
        ? []
        : [{ featureId: feature.id, ...offering, availability: pick ? 'A' : 'S' }];
    }),
  );

  return { trims, regions, offerings, features, cells };
}

/** The matrix's row height and offering column width in pixels, which set the scrolling steps. */
const ROW_HEIGHT = 36;
const COLUMN_WIDTH = 96;

const nextFrame = () => new Promise<number>((resolve) => requestAnimationFrame(resolve));

/**
 * The middle, the 95th percentile, and the largest of some durations in whole milliseconds, and how
 * many of them ran past 50 ms, which is where a pause becomes noticeable.
 */
function summary(durations: number[]): string {
  const sorted = [...durations].sort((one, other) => one - other);
  const at = (share: number) => Math.round(sorted[Math.floor((sorted.length - 1) * share)]);
  const slow = durations.filter((duration) => duration > 50).length;

  return `median ${at(0.5)} ms, 95th percentile ${at(0.95)} ms, longest ${at(1)} ms, ${slow} of ${durations.length} over 50 ms`;
}

/**
 * Proves the matrix at the largest size a catalog can reach, on generated data, and measures how it
 * scrolls and edits there. The page exists only in development builds.
 */
@Component({
  imports: [AvailabilityMatrix, Button],
  selector: 'app-matrix-proof-page',
  template: `
    <main class="flex h-screen flex-col gap-4 px-6 py-6">
      <header class="flex flex-wrap items-center gap-4">
        <h1 class="text-2xl font-semibold">Matrix at 500 feature rows by 96 offerings</h1>
        <p-button
          severity="secondary"
          [label]="editable() ? 'Make read-only' : 'Make editable'"
          (onClick)="editable.set(!editable())"
        />
        <p-button label="Measure" [disabled]="measuring()" (onClick)="measure()" />
      </header>
      <p aria-live="polite" data-last-change>{{ lastChange() }}</p>
      @if (results().length) {
        <ul data-results>
          @for (result of results(); track result) {
            <li>{{ result }}</li>
          }
        </ul>
      }
      <app-availability-matrix
        class="min-h-0 flex-1"
        [contents]="contents"
        [categories]="categories"
        [editable]="editable()"
        (cellChange)="show($event)"
      />
    </main>
  `,
})
export class MatrixProofPage {
  private readonly app = inject(ApplicationRef);
  private readonly page: HTMLElement = inject(ElementRef).nativeElement;

  protected readonly contents = largestCatalog();
  protected readonly categories = CATEGORIES;
  protected readonly editable = signal(true);
  protected readonly lastChange = signal('No cell has been changed.');
  protected readonly measuring = signal(false);
  protected readonly results = signal<string[]>([]);

  protected show(change: Cell): void {
    this.lastChange.set(
      `Feature ${change.featureId}, trim ${change.trimId} in ${change.regionCode}: ${change.availability}`,
    );
  }

  /**
   * Scrolls the matrix from top to bottom at two speeds and from side to side, timing every frame;
   * then sets a hundred cells, timing each from the key press until the page is laid out again.
   */
  protected async measure(): Promise<void> {
    this.measuring.set(true);
    this.editable.set(true);
    const viewport = this.page.querySelector<HTMLElement>('.p-virtualscroller')!;
    const atBottom = () => viewport.scrollTop + viewport.clientHeight >= viewport.scrollHeight - 1;
    const atRight = () => viewport.scrollLeft + viewport.clientWidth >= viewport.scrollWidth - 1;
    const toStart = async () => {
      viewport.scrollTo(0, 0);
      await nextFrame();
      await nextFrame();
    };

    await toStart();
    const slowly = await framesUntil(atBottom, () => (viewport.scrollTop += ROW_HEIGHT));
    await toStart();
    const quickly = await framesUntil(atBottom, () => (viewport.scrollTop += 5 * ROW_HEIGHT));
    await toStart();
    const across = await framesUntil(atRight, () => (viewport.scrollLeft += 2 * COLUMN_WIDTH));
    await toStart();

    const edits: number[] = [];
    const cells = Array.from(this.page.querySelectorAll<HTMLElement>('td[tabindex="0"]'));
    for (let edit = 0; edit < 100; edit++) {
      const cell = cells[(edit * 37) % cells.length];
      const key = cell.textContent?.trim() === 'S' ? 'a' : 's';
      const start = performance.now();
      cell.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
      this.app.tick();
      cell.getBoundingClientRect();
      edits.push(performance.now() - start);
      await nextFrame();
    }

    this.results.set([
      `Scrolling down, 1 row a frame: ${summary(slowly)}`,
      `Scrolling down, 5 rows a frame: ${summary(quickly)}`,
      `Scrolling across, 2 columns a frame: ${summary(across)}`,
      `Setting a cell: ${summary(edits)}`,
    ]);
    this.measuring.set(false);
  }
}

/** Repeats a step once a frame until the condition holds, and returns how long each frame took. */
async function framesUntil(done: () => boolean, step: () => void): Promise<number[]> {
  const frames: number[] = [];
  let last = await nextFrame();
  while (!done()) {
    step();
    const now = await nextFrame();
    frames.push(now - last);
    last = now;
  }

  return frames;
}
