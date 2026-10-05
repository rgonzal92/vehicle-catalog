import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { reasonOf } from '../../../shared/reason-of';
import { FixedLists } from '../../../core/fixed-lists';
import { VehicleLine, VehicleLines } from './vehicle-lines';

/** Where an admin adds, renames, retypes, activates, and deactivates vehicle lines. */
@Component({
  imports: [
    ReactiveFormsModule,
    RouterLink,
    Button,
    Dialog,
    InputText,
    Message,
    Select,
    TableModule,
    Tag,
  ],
  selector: 'app-vehicle-lines-page',
  template: `
    <main class="mx-auto max-w-5xl px-6 py-10">
      <a class="text-primary underline" routerLink="/dashboard">Dashboard</a>
      <header class="mt-4 flex items-center justify-between gap-4">
        <h1 class="text-2xl font-semibold">Vehicle lines</h1>
        <p-button label="Add vehicle line" (onClick)="startAdding()" />
      </header>

      <p-table class="mt-6 block" [value]="vehicleLines.lines()">
        <ng-template #header>
          <tr>
            <th scope="col">Code</th>
            <th scope="col">Name</th>
            <th scope="col">Vehicle type</th>
            <th scope="col">State</th>
            <th scope="col"><span class="sr-only">Actions</span></th>
          </tr>
        </ng-template>
        <ng-template #body let-line>
          <tr>
            <td>{{ line.code }}</td>
            <td>{{ line.name }}</td>
            <td>{{ fixedLists.vehicleTypeName(line.vehicleTypeCode) }}</td>
            <td>
              <p-tag
                [value]="line.active ? 'Active' : 'Inactive'"
                [severity]="line.active ? 'success' : 'secondary'"
              />
            </td>
            <td class="text-right">
              <p-button
                label="Edit"
                severity="secondary"
                [text]="true"
                [ariaLabel]="'Edit ' + line.name"
                (onClick)="startEditing(line)"
              />
              <p-button
                severity="secondary"
                [text]="true"
                [label]="line.active ? 'Deactivate' : 'Activate'"
                [ariaLabel]="(line.active ? 'Deactivate ' : 'Activate ') + line.name"
                (onClick)="setActive(line, !line.active)"
              />
            </td>
          </tr>
        </ng-template>
        <ng-template #emptymessage>
          <tr>
            <td colspan="5">There are no vehicle lines.</td>
          </tr>
        </ng-template>
      </p-table>

      <p-dialog
        [header]="editing() ? 'Edit vehicle line' : 'Add vehicle line'"
        closeAriaLabel="Close"
        [modal]="true"
        [style]="{ width: '28rem' }"
        [(visible)]="dialogOpen"
      >
        <form class="grid gap-4" [formGroup]="form" (ngSubmit)="save()">
          @if (refusal()) {
            <p-message severity="error">{{ refusal() }}</p-message>
          }
          @if (!editing()) {
            <div class="grid gap-1">
              <label for="vehicle-line-code">Code</label>
              <input pInputText id="vehicle-line-code" formControlName="code" autocomplete="off" />
            </div>
          }
          <div class="grid gap-1">
            <label for="vehicle-line-name">Name</label>
            <input pInputText id="vehicle-line-name" formControlName="name" autocomplete="off" />
          </div>
          <div class="grid gap-1">
            <label id="vehicle-line-type-label" for="vehicle-line-type">Vehicle type</label>
            <p-select
              inputId="vehicle-line-type"
              ariaLabelledBy="vehicle-line-type-label"
              formControlName="vehicleTypeCode"
              optionLabel="name"
              optionValue="code"
              appendTo="body"
              [options]="fixedLists.vehicleTypes()"
            />
          </div>
          <div class="flex justify-end gap-2">
            <p-button label="Cancel" severity="secondary" (onClick)="dialogOpen.set(false)" />
            <p-button type="submit" label="Save" [disabled]="form.invalid" />
          </div>
        </form>
      </p-dialog>
    </main>
  `,
})
export class VehicleLinesPage {
  protected readonly vehicleLines = inject(VehicleLines);
  protected readonly fixedLists = inject(FixedLists);
  private readonly messages = inject(MessageService);

  protected readonly dialogOpen = signal(false);

  /** The vehicle line the dialog is changing, or null while it adds one. */
  protected readonly editing = signal<VehicleLine | null>(null);

  /** Why the backend refused the last save, shown in the dialog. */
  protected readonly refusal = signal('');

  protected readonly form = inject(NonNullableFormBuilder).group({
    code: ['', Validators.required],
    name: ['', Validators.required],
    vehicleTypeCode: ['', Validators.required],
  });

  constructor() {
    void this.vehicleLines.load();
    void this.fixedLists.load();
  }

  protected startAdding(): void {
    this.open(null);
    this.form.reset();
    this.form.controls.code.enable();
  }

  protected startEditing(line: VehicleLine): void {
    this.open(line);
    this.form.reset(line);
    // A code never changes, so it takes no part in an edit.
    this.form.controls.code.disable();
  }

  protected async save(): Promise<void> {
    const { code, name, vehicleTypeCode } = this.form.getRawValue();
    const line = this.editing();

    try {
      if (line) {
        await this.vehicleLines.change(line.id, { name, vehicleTypeCode, active: line.active });
      } else {
        await this.vehicleLines.add({ code, name, vehicleTypeCode });
      }
      this.dialogOpen.set(false);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    }
  }

  protected async setActive(line: VehicleLine, active: boolean): Promise<void> {
    try {
      await this.vehicleLines.change(line.id, {
        name: line.name,
        vehicleTypeCode: line.vehicleTypeCode,
        active,
      });
    } catch (error) {
      this.messages.add({ severity: 'error', summary: line.name, detail: reasonOf(error) });
    }
  }

  private open(line: VehicleLine | null): void {
    this.editing.set(line);
    this.refusal.set('');
    this.dialogOpen.set(true);
  }
}
