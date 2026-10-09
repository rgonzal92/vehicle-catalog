import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Plus } from '@primeicons/angular/plus';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { MultiSelect } from 'primeng/multiselect';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import {
  GlobalRule,
  GlobalRules,
  RULE_KIND_NAMES,
  RuleKind,
  ruleInWords,
} from '../../../core/global-rules';
import { Library, LibraryFeature, LibraryRegion } from '../../../core/library';
import { Loading, ReadFailed } from '../../../shared/read-state';
import { reasonOf } from '../../../shared/reason-of';

/** The fewest targets a rule of each kind has. */
const FEWEST_TARGETS: Record<RuleKind, number> = { REQUIRES: 1, REQUIRES_ONE_OF: 2, INCLUDES: 1 };

/** The most targets a rule has. */
const MOST_TARGETS = 20;

/** Where an admin adds, changes, and deletes the library's global rules. */
@Component({
  imports: [
    ReactiveFormsModule,
    Plus,
    Button,
    ButtonDirective,
    ButtonIcon,
    ButtonLabel,
    Checkbox,
    Dialog,
    Message,
    MultiSelect,
    Select,
    TableModule,
    Tag,
    Loading,
    ReadFailed,
  ],
  selector: 'app-global-rules-page',
  template: `
    <div class="max-w-6xl">
      <div class="surface">
        <div class="surface-header">
          <p class="text-muted-color">These rules hold in every catalog.</p>
          <button pButton type="button" (click)="startAdding()">
            <svg data-p-icon="plus" pButtonIcon />
            <span pButtonLabel>Add global rule</span>
          </button>
        </div>
        @if (rules(); as rules) {
          <p-table [value]="rules">
            <ng-template #header>
              <tr>
                <th scope="col">Source</th>
                <th scope="col">Kind</th>
                <th scope="col">Targets</th>
                <th scope="col">Regions</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </ng-template>
            <ng-template #body let-rule>
              <tr>
                <td>{{ rule.source.name }}</td>
                <td><p-tag severity="secondary" [value]="kindNames[rule.kind]" /></td>
                <td>{{ named(rule.targets) }}</td>
                <td>{{ rule.allRegions ? 'Every region' : named(rule.regions) }}</td>
                <td class="text-right whitespace-nowrap">
                  <p-button
                    label="Edit"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [ariaLabel]="'Edit the rule: ' + inWords(rule)"
                    (onClick)="startEditing(rule)"
                  />
                  <p-button
                    label="Delete"
                    severity="secondary"
                    size="small"
                    [text]="true"
                    [ariaLabel]="'Delete the rule: ' + inWords(rule)"
                    (onClick)="askToDelete(rule)"
                  />
                </td>
              </tr>
            </ng-template>
            <ng-template #emptymessage>
              <tr>
                <td class="surface-empty" colspan="5">There are no global rules.</td>
              </tr>
            </ng-template>
          </p-table>
        } @else if (failed()) {
          <app-read-failed
            class="m-4"
            what="The global rules could not be read."
            (again)="load()"
          />
        } @else {
          <app-loading />
        }
      </div>

      <p-dialog
        closeAriaLabel="Close"
        [header]="editing() ? 'Edit global rule' : 'Add global rule'"
        [modal]="true"
        [style]="{ width: '36rem' }"
        [(visible)]="dialogOpen"
      >
        <form class="grid gap-4" [formGroup]="form" (ngSubmit)="save()">
          @if (refusal()) {
            <p-message severity="error">{{ refusal() }}</p-message>
          }
          <div class="grid gap-1">
            <label id="rule-kind-label" for="rule-kind">Kind</label>
            <p-select
              inputId="rule-kind"
              ariaLabelledBy="rule-kind-label"
              formControlName="kind"
              optionLabel="name"
              optionValue="code"
              appendTo="body"
              [options]="kinds"
            />
            @if (editing()) {
              <p class="text-sm text-muted-color">
                A rule's kind cannot be changed. Delete the rule and add another.
              </p>
            }
          </div>
          <div class="grid gap-1">
            <label id="rule-source-label" for="rule-source">Source</label>
            <p-select
              inputId="rule-source"
              ariaLabelledBy="rule-source-label"
              formControlName="sourceFeatureId"
              optionLabel="name"
              optionValue="id"
              appendTo="body"
              placeholder="Choose a feature"
              filterPlaceholder="Code or name"
              filterBy="name,code"
              [filter]="true"
              [options]="sources()"
              [loading]="features() === null"
            />
          </div>
          <div class="grid gap-1">
            <label id="rule-targets-label" for="rule-targets">Targets</label>
            <p-multiselect
              inputId="rule-targets"
              ariaLabelledBy="rule-targets-label"
              formControlName="targetFeatureIds"
              optionLabel="name"
              optionValue="id"
              appendTo="body"
              display="chip"
              placeholder="Choose features"
              filterPlaceHolder="Code or name"
              filterBy="name,code"
              [filter]="true"
              [showToggleAll]="false"
              [options]="targets()"
              [loading]="features() === null"
            />
            <p class="text-sm text-muted-color">{{ targetsNeeded() }}</p>
          </div>
          <label class="flex items-center gap-2">
            <p-checkbox formControlName="allRegions" [binary]="true" />
            The rule holds in every region
          </label>
          @if (!form.controls.allRegions.value) {
            <div class="grid gap-1">
              <label id="rule-regions-label" for="rule-regions">Regions</label>
              <p-multiselect
                inputId="rule-regions"
                ariaLabelledBy="rule-regions-label"
                formControlName="regionCodes"
                optionLabel="name"
                optionValue="code"
                appendTo="body"
                display="chip"
                placeholder="Choose regions"
                [showToggleAll]="false"
                [options]="regions()"
              />
            </div>
          }
          <div class="flex justify-end gap-2">
            <p-button label="Cancel" severity="secondary" (onClick)="dialogOpen.set(false)" />
            <p-button type="submit" label="Save" [disabled]="!complete()" [loading]="saving()" />
          </div>
        </form>
      </p-dialog>

      <p-dialog
        header="Delete global rule"
        closeAriaLabel="Close"
        [modal]="true"
        [style]="{ width: '30rem' }"
        [closable]="!deletingNow()"
        [visible]="deleting() !== null"
        (visibleChange)="deleting.set(null)"
      >
        @if (deleting(); as asked) {
          <div class="grid gap-4">
            @if (deletionRefusal()) {
              <p-message severity="error">{{ deletionRefusal() }}</p-message>
            }
            <p data-question>
              Delete the rule that {{ inWords(asked) }}? Every catalog stops being checked against
              it.
            </p>
            <div class="flex justify-end gap-2">
              <p-button
                label="Keep"
                severity="secondary"
                [autofocus]="true"
                [disabled]="deletingNow()"
                (onClick)="deleting.set(null)"
              />
              <p-button label="Delete" [loading]="deletingNow()" (onClick)="delete(asked)" />
            </div>
          </div>
        }
      </p-dialog>
    </div>
  `,
})
export class GlobalRulesPage {
  private readonly globalRules = inject(GlobalRules);
  private readonly library = inject(Library);

  protected readonly kindNames: Record<string, string> = RULE_KIND_NAMES;
  protected readonly kinds = Object.entries(RULE_KIND_NAMES).map(([code, name]) => ({
    code: code as RuleKind,
    name,
  }));
  protected readonly inWords = ruleInWords;

  /** The rules, or null until they have been read. */
  protected readonly rules = signal<GlobalRule[] | null>(null);

  /** Whether the last reading of the rules failed, which the page then says. */
  protected readonly failed = signal(false);

  /** The library's active features, which a rule names, or null until they have been read. */
  protected readonly features = signal<LibraryFeature[] | null>(null);

  /** The library's active regions, which a rule's scope lists. */
  protected readonly regions = signal<LibraryRegion[]>([]);

  protected readonly dialogOpen = signal(false);

  /** The rule the dialog is changing, or null while it adds one. */
  protected readonly editing = signal<GlobalRule | null>(null);

  /** Why the backend refused the last save, shown in the dialog. */
  protected readonly refusal = signal('');

  /** Whether a save is on its way, so that a second click saves nothing more. */
  protected readonly saving = signal(false);

  /** The rule the admin is asked to confirm the deletion of, or null while there is none. */
  protected readonly deleting = signal<GlobalRule | null>(null);
  protected readonly deletionRefusal = signal('');
  protected readonly deletingNow = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    kind: ['REQUIRES' as RuleKind, Validators.required],
    sourceFeatureId: [null as number | null, Validators.required],
    targetFeatureIds: [[] as number[]],
    allRegions: [true],
    regionCodes: [[] as string[]],
  });

  /** What the form holds, as a signal, so that what it offers follows what is chosen. */
  private readonly chosen = signal(this.form.getRawValue());

  /** Only a package includes other features, so an Includes offers packages as its source. */
  protected readonly sources = computed(() =>
    (this.features() ?? []).filter(
      (feature) => this.chosen().kind !== 'INCLUDES' || feature.kind === 'PACKAGE',
    ),
  );

  /** A rule's source cannot be one of its targets. */
  protected readonly targets = computed(() =>
    (this.features() ?? []).filter((feature) => feature.id !== this.chosen().sourceFeatureId),
  );

  protected readonly targetsNeeded = computed(() => {
    const fewest = FEWEST_TARGETS[this.chosen().kind];
    return `Choose ${fewest === 1 ? '1' : fewest} to ${MOST_TARGETS} features.`;
  });

  /** Whether the form holds everything a rule needs. The backend checks the rest. */
  protected readonly complete = computed(() => {
    const { kind, sourceFeatureId, targetFeatureIds, allRegions, regionCodes } = this.chosen();
    return (
      sourceFeatureId !== null &&
      targetFeatureIds.length >= FEWEST_TARGETS[kind] &&
      targetFeatureIds.length <= MOST_TARGETS &&
      (allRegions || regionCodes.length > 0)
    );
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.chosen.set(this.form.getRawValue()));
    void this.load();
  }

  protected async load(): Promise<void> {
    this.failed.set(false);
    try {
      this.rules.set(await this.globalRules.list());
    } catch {
      // The failure has been shown as a message too, which goes away; the page goes on saying so.
      this.failed.set(true);
    }
  }

  protected named(things: { name: string }[]): string {
    return things.map(({ name }) => name).join(', ');
  }

  protected startAdding(): void {
    this.open(null);
    this.form.reset();
    this.form.controls.kind.enable();
  }

  protected startEditing(rule: GlobalRule): void {
    this.open(rule);
    this.form.reset({
      kind: rule.kind,
      sourceFeatureId: rule.source.id,
      targetFeatureIds: rule.targets.map(({ id }) => id),
      allRegions: rule.allRegions,
      regionCodes: rule.regions.map(({ code }) => code),
    });
    // A rule's kind never changes, so it takes no part in an edit.
    this.form.controls.kind.disable();
  }

  protected async save(): Promise<void> {
    const { kind, sourceFeatureId, targetFeatureIds, allRegions, regionCodes } =
      this.form.getRawValue();
    if (!this.complete() || sourceFeatureId === null || this.saving()) {
      return;
    }
    const content = {
      kind,
      sourceFeatureId,
      targetFeatureIds,
      allRegions,
      regionCodes: allRegions ? [] : regionCodes,
    };
    const rule = this.editing();

    this.saving.set(true);
    try {
      await (rule ? this.globalRules.change(rule.id, content) : this.globalRules.add(content));
      this.dialogOpen.set(false);
      await this.load();
    } catch (error) {
      this.refusal.set(reasonOf(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected askToDelete(rule: GlobalRule): void {
    this.deletionRefusal.set('');
    this.deleting.set(rule);
  }

  protected async delete(rule: GlobalRule): Promise<void> {
    if (this.deletingNow()) {
      return;
    }
    this.deletingNow.set(true);
    try {
      await this.globalRules.delete(rule.id);
      this.deleting.set(null);
      await this.load();
    } catch (error) {
      this.deletionRefusal.set(reasonOf(error));
    } finally {
      this.deletingNow.set(false);
    }
  }

  /** Opens the dialog, and reads what it offers the first time it is opened. */
  private open(rule: GlobalRule | null): void {
    this.editing.set(rule);
    this.refusal.set('');
    this.dialogOpen.set(true);
    if (this.features() === null) {
      void this.readWhatARuleNames();
    }
  }

  private async readWhatARuleNames(): Promise<void> {
    try {
      const [features, regions] = await Promise.all([
        this.library.everyActiveFeature(),
        this.library.regions(),
      ]);
      this.features.set(features);
      this.regions.set(regions.filter(({ active }) => active));
    } catch {
      // The failure has already been shown as a message.
    }
  }
}
