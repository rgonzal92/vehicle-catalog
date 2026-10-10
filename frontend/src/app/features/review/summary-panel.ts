import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { Catalogs, SubmissionSummary } from '../../core/catalogs';

/** How often the panel asks whether a summary that is being written is there, in milliseconds. */
export const ASKS_EVERY = 3000;

/**
 * The summary the language model wrote of what a Submitted catalog changes, for its reviewer to
 * read beside the changes themselves. It is a help in reading and not the record: it is marked as
 * a model's, and when there is none the panel says why. While the summary is being written the
 * panel asks for it again every few seconds.
 */
@Component({
  selector: 'app-summary-panel',
  template: `
    <section class="surface" aria-labelledby="summary">
      <div class="surface-header">
        <h2 id="summary" class="font-semibold">Summary</h2>
        <p class="text-sm text-muted-color">
          Written by a language model from the changes, and checked against them. The changes are
          the record.
        </p>
      </div>
      <div class="grid gap-2 px-4 py-3" aria-live="polite" data-summary>
        @if (summary(); as said) {
          @switch (said.status) {
            @case ('READY') {
              <p class="font-medium">{{ said.headline }}</p>
              @if (said.bullets.length) {
                <ul class="list-disc pl-5">
                  @for (bullet of said.bullets; track $index) {
                    <li>{{ bullet }}</li>
                  }
                </ul>
              }
            }
            @case ('PENDING') {
              <p class="text-muted-color">The summary is being written.</p>
            }
            @default {
              <p class="text-muted-color">There is no summary. {{ said.reason }}</p>
            }
          }
        } @else if (asked()) {
          <p class="text-muted-color">There is no summary.</p>
        }
      </div>
    </section>
  `,
})
export class SummaryPanel implements OnInit {
  private readonly catalogs = inject(Catalogs);
  private readonly destroyed = inject(DestroyRef);

  /** The Submitted catalog whose summary this is. */
  readonly catalogId = input.required<number>();

  /** The summary as it last stood, or null when there is none to show. */
  protected readonly summary = signal<SubmissionSummary | null>(null);

  /** Whether the backend has been asked once, so that nothing is said before it has answered. */
  protected readonly asked = signal(false);

  private gone = false;

  ngOnInit(): void {
    this.destroyed.onDestroy(() => (this.gone = true));
    void this.watch();
  }

  /** Asks for the summary until it is there, or there is to be none, or the panel is gone. */
  private async watch(): Promise<void> {
    do {
      try {
        this.summary.set(await this.catalogs.summary(this.catalogId()));
      } catch {
        // The next time may succeed.
      }
      this.asked.set(true);
      if (this.summary()?.status !== 'PENDING') {
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, ASKS_EVERY));
    } while (!this.gone);
  }
}
