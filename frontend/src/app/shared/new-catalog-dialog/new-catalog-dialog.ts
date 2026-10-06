import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Button } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { Message } from 'primeng/message';
import { Select } from 'primeng/select';
import { Catalogs, startPointInWords } from '../../core/catalogs';
import { FixedLists } from '../../core/fixed-lists';
import { VehicleLines } from '../../core/vehicle-lines';
import { reasonOf } from '../reason-of';

/**
 * Where a person creates a working copy: a name, and the vehicle line and model year it is for. The
 * dialog says where the working copy will start from before it is created, and the new one opens in
 * the catalog editor.
 */
@Component({
  imports: [ReactiveFormsModule, Button, Dialog, InputText, Message, Select],
  selector: 'app-new-catalog-dialog',
  template: `
    <p-dialog
      header="New catalog"
      closeAriaLabel="Close"
      [modal]="true"
      [style]="{ width: '28rem' }"
      [(visible)]="visible"
    >
      <form class="grid gap-4" [formGroup]="form" (ngSubmit)="create()">
        @if (refusal()) {
          <p-message severity="error">{{ refusal() }}</p-message>
        }
        <div class="grid gap-1">
          <label for="new-catalog-name">Name</label>
          <input pInputText id="new-catalog-name" formControlName="name" autocomplete="off" />
        </div>
        <div class="grid gap-1">
          <label id="new-catalog-type-label" for="new-catalog-type">Vehicle type</label>
          <p-select
            inputId="new-catalog-type"
            ariaLabelledBy="new-catalog-type-label"
            formControlName="vehicleTypeCode"
            optionLabel="name"
            optionValue="code"
            appendTo="body"
            [options]="fixedLists.vehicleTypes()"
            (onChange)="typeChosen()"
          />
        </div>
        <div class="grid gap-1">
          <label id="new-catalog-line-label" for="new-catalog-line">Vehicle line</label>
          <p-select
            inputId="new-catalog-line"
            ariaLabelledBy="new-catalog-line-label"
            formControlName="vehicleLineId"
            optionLabel="name"
            optionValue="id"
            appendTo="body"
            emptyMessage="Choose a vehicle type that has vehicle lines."
            [options]="lines()"
            (onChange)="askStartPoint()"
          />
        </div>
        <div class="grid gap-1">
          <label id="new-catalog-year-label" for="new-catalog-year">Model year</label>
          <p-select
            inputId="new-catalog-year"
            ariaLabelledBy="new-catalog-year-label"
            formControlName="modelYear"
            appendTo="body"
            [options]="fixedLists.modelYears()"
            (onChange)="askStartPoint()"
          />
        </div>
        <p class="min-h-6" aria-live="polite" data-start-point>{{ startPoint() }}</p>
        <div class="flex justify-end gap-2">
          <p-button label="Cancel" severity="secondary" (onClick)="visible.set(false)" />
          <p-button
            type="submit"
            label="Create"
            [disabled]="form.invalid || !startPoint()"
            [loading]="creating()"
          />
        </div>
      </form>
    </p-dialog>
  `,
})
export class NewCatalogDialog {
  private readonly catalogs = inject(Catalogs);
  private readonly vehicleLines = inject(VehicleLines);
  protected readonly fixedLists = inject(FixedLists);
  private readonly router = inject(Router);

  /** Whether the dialog is open. */
  protected readonly visible = signal(false);

  /** Why the backend refused the last request, shown in the dialog. */
  protected readonly refusal = signal('');

  /** Where the working copy would start from, in words, once a vehicle line and a year are set. */
  protected readonly startPoint = signal('');

  /** Whether the working copy is being created, so that a second click creates no second one. */
  protected readonly creating = signal(false);

  protected readonly form = inject(FormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(80)]],
    vehicleTypeCode: [''],
    vehicleLineId: [null as number | null, Validators.required],
    modelYear: [null as number | null, Validators.required],
  });

  private readonly type = toSignal(this.form.controls.vehicleTypeCode.valueChanges);

  /** The vehicle lines on offer: the active ones of the chosen vehicle type. */
  protected readonly lines = computed(() =>
    this.vehicleLines.lines().filter((line) => line.active && line.vehicleTypeCode === this.type()),
  );

  /** How many starting points have been asked for, so that a slow answer to an earlier one is dropped. */
  private asked = 0;

  /** Opens the dialog, set to a lineage's vehicle line and model year when one is given. */
  async open(lineage?: { vehicleLineId: number; modelYear: number }): Promise<void> {
    this.form.reset();
    this.refusal.set('');
    this.startPoint.set('');
    this.asked++;
    this.visible.set(true);

    try {
      await Promise.all([this.vehicleLines.load(), this.fixedLists.load()]);
    } catch {
      // The failure has already been shown as a message.
      return;
    }
    const line = this.vehicleLines.lines().find(({ id }) => id === lineage?.vehicleLineId);
    if (lineage && line) {
      this.form.patchValue({
        vehicleTypeCode: line.vehicleTypeCode,
        vehicleLineId: lineage.vehicleLineId,
        modelYear: lineage.modelYear,
      });
      await this.askStartPoint();
    }
  }

  /** A vehicle line belongs to one vehicle type, so another type starts the choice of line over. */
  protected typeChosen(): void {
    this.form.controls.vehicleLineId.reset();
    void this.askStartPoint();
  }

  /** Asks where a working copy for the chosen vehicle line and model year would start from. */
  protected async askStartPoint(): Promise<void> {
    const { vehicleLineId, modelYear } = this.form.getRawValue();
    const mine = ++this.asked;
    this.startPoint.set('');
    this.refusal.set('');
    if (vehicleLineId === null || modelYear === null) {
      return;
    }

    try {
      const start = await this.catalogs.startPoint(vehicleLineId, modelYear);
      if (mine === this.asked) {
        this.startPoint.set(startPointInWords(start));
      }
    } catch (error) {
      if (mine === this.asked) {
        this.refusal.set(reasonOf(error));
      }
    }
  }

  /** Creates the working copy and opens it, or stays open with the reason it was refused. */
  protected async create(): Promise<void> {
    const { name, vehicleLineId, modelYear } = this.form.getRawValue();
    if (!name || vehicleLineId === null || modelYear === null || this.creating()) {
      return;
    }

    this.creating.set(true);
    try {
      const created = await this.catalogs.create({ name, vehicleLineId, modelYear });
      this.visible.set(false);
      await this.router.navigate(['/catalogs', created.id]);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    } finally {
      this.creating.set(false);
    }
  }
}
