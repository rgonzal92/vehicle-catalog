import { Component, inject, input, signal } from '@angular/core';
import { Button, ButtonDirective, ButtonLabel } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { CatalogExport, Catalogs } from '../core/catalogs';
import { reasonOf } from './reason-of';

/** How long the dialog waits before it asks again how the export stands. */
export const ASKS_EVERY = 2000;

/**
 * Where a person has a catalog exported to a spreadsheet. The file is built in the background, so
 * the dialog says that it is being prepared and asks how it stands every two seconds, for as long
 * as it is open. Once the file is there it offers it for download.
 */
@Component({
  imports: [Button, ButtonDirective, ButtonLabel, Dialog, Message],
  selector: 'app-export-dialog',
  template: `
    <p-dialog
      header="Export to a spreadsheet"
      closeAriaLabel="Close"
      [modal]="true"
      [style]="{ width: '30rem' }"
      [(visible)]="visible"
    >
      <div class="grid gap-4">
        @if (refusal()) {
          <p-message severity="error">{{ refusal() }}</p-message>
        } @else if (shown()?.status === 'READY') {
          <p role="status" data-export="ready">{{ shown()?.fileName }} is ready.</p>
        } @else if (shown()?.status === 'FAILED') {
          <p-message severity="error"
            >The spreadsheet could not be built. Try again in a while.</p-message
          >
        } @else {
          <p role="status" data-export="waiting">
            The spreadsheet is being prepared, which takes a moment.
          </p>
        }
        <div class="flex justify-end gap-2">
          <p-button label="Close" severity="secondary" (onClick)="visible.set(false)" />
          @if (!refusal() && shown()?.status === 'READY') {
            <a pButton [href]="'/api/exports/' + shown()?.id + '/download'">
              <span pButtonLabel>Download</span>
            </a>
          }
        </div>
      </div>
    </p-dialog>
  `,
})
export class ExportDialog {
  private readonly catalogs = inject(Catalogs);

  /** The catalog to export. */
  readonly catalogId = input.required<number>();

  /** Whether the dialog is open. */
  protected readonly visible = signal(false);

  /** The export as it last stood, or null until the backend has taken it. */
  protected readonly shown = signal<CatalogExport | null>(null);

  /** Why the backend would not export the catalog, shown in place of anything else. */
  protected readonly refusal = signal('');

  /** How many exports this dialog has asked for, so that an earlier one is no longer watched. */
  private asked = 0;

  /** Opens the dialog and asks for the catalog as a spreadsheet. */
  async start(): Promise<void> {
    const mine = ++this.asked;
    this.shown.set(null);
    this.refusal.set('');
    this.visible.set(true);
    try {
      this.shown.set(await this.catalogs.export(this.catalogId()));
    } catch (error) {
      this.refusal.set(reasonOf(error));
      return;
    }
    void this.watch(mine);
  }

  /** Asks how the export stands until its file is there or has failed, or the dialog is closed. */
  private async watch(mine: number): Promise<void> {
    while (this.visible() && mine === this.asked && this.shown()?.status === 'QUEUED') {
      await new Promise((resolve) => setTimeout(resolve, ASKS_EVERY));
      if (!this.visible() || mine !== this.asked) {
        return;
      }
      try {
        this.shown.set(await this.catalogs.exportStatus(this.shown()!.id));
      } catch {
        // The next time may succeed.
      }
    }
  }
}
