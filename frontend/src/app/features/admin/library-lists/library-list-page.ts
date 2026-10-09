import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ArrowDown } from '@primeicons/angular/arrow-down';
import { ArrowUp } from '@primeicons/angular/arrow-up';
import { Plus } from '@primeicons/angular/plus';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { reasonOf } from '../../../shared/reason-of';
import { EntryChange, LibraryEntries } from './library-entries';
import { LibraryEntry, LibraryList } from './library-list';

/**
 * Where an admin adds, renames, moves, activates, and deactivates the entries of one of the
 * library's ordered lists. The route says which list: trims or regions.
 */
@Component({
  imports: [
    ReactiveFormsModule,
    ArrowDown,
    ArrowUp,
    Plus,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Dialog,
    InputText,
    Message,
    TableModule,
    Tag,
  ],
  providers: [LibraryEntries],
  selector: 'app-library-list-page',
  template: `
    <div class="max-w-5xl">
      <div class="surface">
        <div class="surface-header">
          <p class="text-muted-color">{{ list.purpose }}</p>
          <button pButton type="button" (click)="startAdding()">
            <svg data-p-icon="plus" pButtonIcon />
            <span pButtonLabel>Add {{ list.singular }}</span>
          </button>
        </div>
        <p-table [value]="library.entries()">
          <ng-template #header>
            <tr>
              @if (list.hasCode) {
                <th scope="col">Code</th>
              }
              <th scope="col">Name</th>
              <th scope="col">State</th>
              <th scope="col"><span class="sr-only">Actions</span></th>
            </tr>
          </ng-template>
          <ng-template #body let-entry let-index="rowIndex">
            <tr>
              @if (list.hasCode) {
                <td>{{ entry.code }}</td>
              }
              <td>{{ entry.name }}</td>
              <td>
                <p-tag
                  [value]="entry.active ? 'Active' : 'Inactive'"
                  [severity]="entry.active ? 'success' : 'secondary'"
                />
              </td>
              <td>
                <div class="flex items-center justify-end gap-1">
                  <button
                    pButton
                    type="button"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [iconOnly]="true"
                    [disabled]="index === 0"
                    [attr.aria-label]="'Move ' + entry.name + ' up'"
                    (click)="change(entry, { sortOrder: entry.sortOrder - 1 })"
                  >
                    <svg data-p-icon="arrow-up" pButtonIcon />
                  </button>
                  <button
                    pButton
                    type="button"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [iconOnly]="true"
                    [disabled]="index === library.entries().length - 1"
                    [attr.aria-label]="'Move ' + entry.name + ' down'"
                    (click)="change(entry, { sortOrder: entry.sortOrder + 1 })"
                  >
                    <svg data-p-icon="arrow-down" pButtonIcon />
                  </button>
                  <p-button
                    label="Edit"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [ariaLabel]="'Edit ' + entry.name"
                    (onClick)="startEditing(entry)"
                  />
                  <p-button
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [label]="entry.active ? 'Deactivate' : 'Activate'"
                    [ariaLabel]="(entry.active ? 'Deactivate ' : 'Activate ') + entry.name"
                    (onClick)="change(entry, { active: !entry.active })"
                  />
                </div>
              </td>
            </tr>
          </ng-template>
          <ng-template #emptymessage>
            <tr>
              <td class="surface-empty" [attr.colspan]="list.hasCode ? 4 : 3">
                There are no {{ list.title.toLowerCase() }}.
              </td>
            </tr>
          </ng-template>
        </p-table>
      </div>

      <p-dialog
        closeAriaLabel="Close"
        [header]="(editing() ? 'Edit ' : 'Add ') + list.singular"
        [modal]="true"
        [style]="{ width: '28rem' }"
        [(visible)]="dialogOpen"
      >
        <form class="grid gap-4" [formGroup]="form" (ngSubmit)="save()">
          @if (refusal()) {
            <p-message severity="error">{{ refusal() }}</p-message>
          }
          @if (list.hasCode && !editing()) {
            <div class="grid gap-1">
              <label for="library-entry-code">Code</label>
              <input pInputText id="library-entry-code" formControlName="code" autocomplete="off" />
            </div>
          }
          <div class="grid gap-1">
            <label for="library-entry-name">Name</label>
            <input pInputText id="library-entry-name" formControlName="name" autocomplete="off" />
          </div>
          <div class="flex justify-end gap-2">
            <p-button label="Cancel" severity="secondary" (onClick)="dialogOpen.set(false)" />
            <p-button type="submit" label="Save" [disabled]="form.invalid" />
          </div>
        </form>
      </p-dialog>
    </div>
  `,
})
export class LibraryListPage {
  protected readonly list = inject(ActivatedRoute).snapshot.data['list'] as LibraryList;
  protected readonly library = inject(LibraryEntries);
  private readonly messages = inject(MessageService);

  protected readonly dialogOpen = signal(false);

  /** The entry the dialog is renaming, or null while it adds one. */
  protected readonly editing = signal<LibraryEntry | null>(null);

  /** Why the backend refused the last save, shown in the dialog. */
  protected readonly refusal = signal('');

  protected readonly form = inject(NonNullableFormBuilder).group({
    code: ['', Validators.required],
    name: ['', Validators.required],
  });

  constructor() {
    void this.library.load(this.list);
  }

  protected startAdding(): void {
    this.open(null);
    this.form.reset();
    // Only a list whose entries have a code asks for one, and only when adding.
    this.setCodeAsked(this.list.hasCode);
  }

  protected startEditing(entry: LibraryEntry): void {
    this.open(entry);
    this.form.reset({ name: entry.name });
    this.setCodeAsked(false);
  }

  protected async save(): Promise<void> {
    const { code, name } = this.form.getRawValue();
    const entry = this.editing();

    try {
      if (entry) {
        await this.library.change(entry, { ...changeOf(entry), name });
      } else {
        await this.library.add(this.list.hasCode ? { code, name } : { name });
      }
      this.dialogOpen.set(false);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    }
  }

  /** Changes one thing about an entry from its row: its place in the list or whether it is active. */
  protected async change(entry: LibraryEntry, changed: Partial<EntryChange>): Promise<void> {
    try {
      await this.library.change(entry, { ...changeOf(entry), ...changed });
    } catch (error) {
      this.messages.add({ severity: 'error', summary: entry.name, detail: reasonOf(error) });
    }
  }

  private open(entry: LibraryEntry | null): void {
    this.editing.set(entry);
    this.refusal.set('');
    this.dialogOpen.set(true);
  }

  private setCodeAsked(asked: boolean): void {
    if (asked) {
      this.form.controls.code.enable();
    } else {
      this.form.controls.code.disable();
    }
  }
}

/** An entry as it stands, in the shape a change is sent in. */
function changeOf(entry: LibraryEntry): EntryChange {
  return { name: entry.name, sortOrder: entry.sortOrder, active: entry.active };
}
