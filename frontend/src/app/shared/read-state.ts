import { Component, input, output } from '@angular/core';
import { Button } from 'primeng/button';
import { Message } from 'primeng/message';
import { Skeleton } from 'primeng/skeleton';

/** Stands in for what a part of a page will show, while it is on its way. */
@Component({
  imports: [Skeleton],
  selector: 'app-loading',
  host: { class: 'grid gap-3 p-4', 'aria-hidden': 'true' },
  template: `
    <p-skeleton height="1.25rem" />
    <p-skeleton height="1.25rem" width="85%" />
    <p-skeleton height="1.25rem" width="60%" />
  `,
})
export class Loading {}

/** Says that a part of a page could not be read, and offers to read it again. */
@Component({
  imports: [Button, Message],
  selector: 'app-read-failed',
  host: { class: 'block' },
  template: `
    <p-message severity="error">
      <span>{{ what() }}</span>
      <p-button
        class="ml-4"
        label="Try again"
        severity="secondary"
        size="small"
        (onClick)="again.emit()"
      />
    </p-message>
  `,
})
export class ReadFailed {
  /** What could not be read, as a sentence. */
  readonly what = input.required<string>();

  /** The person asks for it to be read again. */
  readonly again = output<void>();
}
