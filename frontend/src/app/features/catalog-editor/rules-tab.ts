import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ArrowRightArrowLeft } from '@primeicons/angular/arrow-right-arrow-left';
import { Plus } from '@primeicons/angular/plus';
import { Button, ButtonDirective, ButtonIcon, ButtonLabel } from 'primeng/button';
import { Checkbox } from 'primeng/checkbox';
import { Dialog } from 'primeng/dialog';
import { Message } from 'primeng/message';
import { MultiSelect } from 'primeng/multiselect';
import { Select } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { Tag } from 'primeng/tag';
import { Catalog, CatalogEdit, CatalogRule, Catalogs } from '../../core/catalogs';
import {
  FEWEST_TARGETS,
  GlobalRule,
  GlobalRules,
  MOST_TARGETS,
  RULE_KIND_NAMES,
  RuleKind,
} from '../../core/global-rules';
import { ReadFailed } from '../../shared/read-state';
import { reasonOf } from '../../shared/reason-of';

/**
 * A rule as the tab lists it: a rule of the catalog, or a global rule that names one of its feature
 * rows.
 */
interface RuleRow {
  id: string;
  origin: 'CATALOG' | 'GLOBAL';
  kind: RuleKind;
  source: string;
  targets: string;
  trims: string;
  regions: string;
  /** What a paired rule shares with its pair, or null for a rule that has none. */
  pairKey: string | null;
  /** The catalog's own rule, which its owner changes and deletes, or null for a global rule. */
  rule: CatalogRule | null;
  /** The rule as a sentence without its full stop, with the scopes that do not cover everything. */
  words: string;
}

/** A row from what a rule names. A scope that is null covers everything. */
function rowOf(
  id: string,
  rule: CatalogRule | null,
  kind: RuleKind,
  source: string,
  targets: string[],
  trims: string[] | null,
  regions: string[] | null,
  pairKey: string | null,
): RuleRow {
  const said = `${source} ${RULE_KIND_NAMES[kind].toLowerCase()} ${targets.join(', ')}`;
  const scopes = [trims && `on ${trims.join(', ')}`, regions && `in ${regions.join(', ')}`]
    .filter(Boolean)
    .join('; ');

  return {
    id,
    origin: rule ? 'CATALOG' : 'GLOBAL',
    kind,
    source,
    targets: targets.join(', '),
    trims: trims?.join(', ') ?? 'Every trim',
    regions: regions?.join(', ') ?? 'Every region',
    pairKey,
    rule,
    words: scopes ? `${said} (${scopes})` : said,
  };
}

/**
 * A catalog's rules: its own, which hold in it alone, and the global rules that name one of its
 * feature rows, which are the library's and cannot be changed here. The owner of a working copy in
 * status Draft adds, changes, and deletes the catalog's own rules, and each change is saved at
 * once.
 */
@Component({
  imports: [
    ReactiveFormsModule,
    ArrowRightArrowLeft,
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
    ReadFailed,
  ],
  selector: 'app-rules-tab',
  template: `
    <div class="mb-3 flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
      <p class="text-muted-color">
        The rules of this catalog, and the global rules that name one of its features.
      </p>
      @if (editable()) {
        <button pButton type="button" (click)="startAdding()">
          <svg data-p-icon="plus" pButtonIcon />
          <span pButtonLabel>Add rule</span>
        </button>
      }
    </div>
    @if (globalFailed()) {
      <app-read-failed
        class="mb-3"
        what="The global rules could not be read."
        (again)="readGlobalRules()"
      />
    }
    <p-table size="small" [value]="rows()">
      <ng-template #header>
        <tr>
          <th scope="col">Source</th>
          <th scope="col">Kind</th>
          <th scope="col">Targets</th>
          <th scope="col">Trims</th>
          <th scope="col">Regions</th>
          <th scope="col">Origin</th>
          <th scope="col"><span class="sr-only">Actions</span></th>
        </tr>
      </ng-template>
      <ng-template #body let-row>
        @let shown = row.pairKey && row.pairKey === shownPair();
        <tr
          [class]="shown ? shownPairRow : ''"
          [attr.data-shown-pair]="shown ? '' : null"
          [attr.data-rule]="row.id"
        >
          <td>{{ row.source }}</td>
          <td>
            <span class="flex items-center gap-2">
              <p-tag severity="secondary" [value]="kindNames[row.kind]" />
              @if (row.pairKey) {
                <button
                  pButton
                  type="button"
                  severity="secondary"
                  size="small"
                  [text]="true"
                  [iconOnly]="true"
                  [attr.aria-pressed]="row.pairKey === shownPair()"
                  [attr.aria-label]="'Show the pair of the rule: ' + row.words"
                  title="One of a pair. Show both."
                  (click)="showPair(row)"
                >
                  <svg data-p-icon="arrow-right-arrow-left" pButtonIcon />
                </button>
              }
            </span>
          </td>
          <td>{{ row.targets }}</td>
          <td>{{ row.trims }}</td>
          <td>{{ row.regions }}</td>
          <td>
            <p-tag
              [severity]="row.rule ? 'secondary' : 'info'"
              [value]="row.rule ? 'Catalog' : 'Global'"
            />
          </td>
          <td class="text-right whitespace-nowrap">
            @if (row.rule && editable()) {
              <p-button
                label="Edit"
                severity="secondary"
                size="small"
                [text]="true"
                [ariaLabel]="'Edit the rule: ' + row.words"
                (onClick)="startEditing(row.rule)"
              />
              <p-button
                label="Delete"
                severity="secondary"
                size="small"
                [text]="true"
                [ariaLabel]="'Delete the rule: ' + row.words"
                (onClick)="askToDelete(row)"
              />
            }
          </td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr>
          <td class="surface-empty" colspan="7">
            This catalog has no rules, and no global rule names one of its features.
          </td>
        </tr>
      </ng-template>
    </p-table>

    <p-dialog
      closeAriaLabel="Close"
      [header]="editing() ? 'Edit rule' : 'Add rule'"
      [modal]="true"
      [style]="{ width: '36rem' }"
      [(visible)]="dialogOpen"
    >
      <form class="grid gap-4" [formGroup]="form" (ngSubmit)="save()">
        @if (refusal()) {
          <p-message severity="error">{{ refusal() }}</p-message>
        }
        <div class="grid gap-1">
          <label id="catalog-rule-kind-label" for="catalog-rule-kind">Kind</label>
          <p-select
            inputId="catalog-rule-kind"
            ariaLabelledBy="catalog-rule-kind-label"
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
          <label id="catalog-rule-source-label" for="catalog-rule-source">Source</label>
          <p-select
            inputId="catalog-rule-source"
            ariaLabelledBy="catalog-rule-source-label"
            formControlName="sourceFeatureId"
            optionLabel="name"
            optionValue="id"
            appendTo="body"
            placeholder="Choose a feature row"
            filterPlaceholder="Code or name"
            filterBy="name,code"
            [filter]="true"
            [options]="sources()"
          />
        </div>
        <div class="grid gap-1">
          <label id="catalog-rule-targets-label" for="catalog-rule-targets">Targets</label>
          <p-multiselect
            inputId="catalog-rule-targets"
            ariaLabelledBy="catalog-rule-targets-label"
            formControlName="targetFeatureIds"
            optionLabel="name"
            optionValue="id"
            appendTo="body"
            display="chip"
            placeholder="Choose feature rows"
            filterPlaceHolder="Code or name"
            filterBy="name,code"
            [filter]="true"
            [showToggleAll]="false"
            [selectionLimit]="mostTargets()"
            [options]="targets()"
          />
          <p class="text-sm text-muted-color">{{ targetsNeeded() }}</p>
        </div>
        <label class="flex items-center gap-2">
          <p-checkbox formControlName="allTrims" [binary]="true" />
          The rule holds on every trim
        </label>
        @if (!form.controls.allTrims.value) {
          <div class="grid gap-1">
            <label id="catalog-rule-trims-label" for="catalog-rule-trims">Trims</label>
            <p-multiselect
              inputId="catalog-rule-trims"
              ariaLabelledBy="catalog-rule-trims-label"
              formControlName="trimIds"
              optionLabel="name"
              optionValue="id"
              appendTo="body"
              display="chip"
              placeholder="Choose trims"
              [showToggleAll]="false"
              [options]="catalog().snapshot.trims"
            />
          </div>
        }
        <label class="flex items-center gap-2">
          <p-checkbox formControlName="allRegions" [binary]="true" />
          The rule holds in every region
        </label>
        @if (!form.controls.allRegions.value) {
          <div class="grid gap-1">
            <label id="catalog-rule-regions-label" for="catalog-rule-regions">Regions</label>
            <p-multiselect
              inputId="catalog-rule-regions"
              ariaLabelledBy="catalog-rule-regions-label"
              formControlName="regionCodes"
              optionLabel="name"
              optionValue="code"
              appendTo="body"
              display="chip"
              placeholder="Choose regions"
              [showToggleAll]="false"
              [options]="catalog().snapshot.regions"
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
      header="Delete rule"
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
            Delete the rule that {{ asked.words }}?
            @if (pairOf(asked); as pair) {
              Its pair, {{ pair.words }}, goes with it.
            }
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
  `,
})
export class RulesTab {
  private readonly catalogs = inject(Catalogs);
  private readonly globalRules = inject(GlobalRules);

  /** The catalog whose rules are shown. */
  readonly catalog = input.required<Catalog>();

  /**
   * Sends an edit behind the saves on their way and reads the catalog again once it is saved. It
   * fails with the backend's refusal when the edit is not saved.
   */
  readonly run = input.required<(edit: CatalogEdit) => Promise<void>>();

  /** Whether the catalog can be edited. The tab is read-only, and its dialogs close, when not. */
  readonly editable = input.required<boolean>();

  protected readonly kindNames: Record<string, string> = RULE_KIND_NAMES;
  protected readonly kinds = Object.entries(RULE_KIND_NAMES).map(([code, name]) => ({
    code: code as RuleKind,
    name,
  }));

  /** The library's global rules, of which the tab lists the ones that name a feature row. */
  private readonly everyGlobalRule = signal<GlobalRule[]>([]);

  /** Whether the global rules could not be read, which the tab then says above its own rules. */
  protected readonly globalFailed = signal(false);

  /** The catalog's own rules first, then the global rules that name one of its feature rows. */
  protected readonly rows = computed(() => {
    const { featureRows, trims, regions, rules } = this.catalog().snapshot;
    const features = new Map(featureRows.map(({ id, name }) => [id, name]));
    const trimNames = new Map(trims.map(({ id, name }) => [id, name]));
    const regionNames = new Map(regions.map(({ code, name }) => [code, name]));
    const named = <Key>(names: Map<Key, string>, keys: Key[]) =>
      keys.map((key) => names.get(key) ?? String(key));

    return [
      // A backend that is one release behind answers without rules.
      ...(rules ?? []).map((rule) =>
        rowOf(
          rule.key,
          rule,
          rule.kind,
          named(features, [rule.sourceFeatureId])[0],
          named(features, rule.targetFeatureIds),
          rule.allTrims ? null : named(trimNames, rule.trimIds),
          rule.allRegions ? null : named(regionNames, rule.regionCodes),
          rule.pairKey,
        ),
      ),
      ...this.everyGlobalRule()
        .filter(({ source, targets }) => [source, ...targets].some(({ id }) => features.has(id)))
        .map((rule) =>
          rowOf(
            `global-${rule.id}`,
            null,
            rule.kind,
            rule.source.name,
            rule.targets.map(({ name }) => name),
            null,
            rule.allRegions ? null : rule.regions.map(({ name }) => name),
            rule.pairKey,
          ),
        ),
    ];
  });

  protected readonly dialogOpen = signal(false);

  /** The rule the dialog is changing, or null while it adds one. */
  protected readonly editing = signal<CatalogRule | null>(null);

  /** Why the backend refused the last save, shown in the dialog. */
  protected readonly refusal = signal('');

  /** Whether a save is on its way, so that a second click saves nothing more. */
  protected readonly saving = signal(false);

  /** The rule the owner is asked to confirm the deletion of, or null while there is none. */
  protected readonly deleting = signal<RuleRow | null>(null);
  protected readonly deletionRefusal = signal('');
  protected readonly deletingNow = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    kind: ['REQUIRES' as RuleKind, Validators.required],
    sourceFeatureId: [null as number | null, Validators.required],
    targetFeatureIds: [[] as number[]],
    allTrims: [true],
    trimIds: [[] as number[]],
    allRegions: [true],
    regionCodes: [[] as string[]],
  });

  /** What the form holds, as a signal, so that what it offers follows what is chosen. */
  private readonly chosen = signal(this.form.getRawValue());

  /** Only a package includes other features, so an Includes offers packages as its source. */
  protected readonly sources = computed(() =>
    this.catalog().snapshot.featureRows.filter(
      (feature) => this.chosen().kind !== 'INCLUDES' || feature.kind === 'PACKAGE',
    ),
  );

  /** A rule's source cannot be one of its targets. */
  protected readonly targets = computed(() =>
    this.catalog().snapshot.featureRows.filter(
      (feature) => feature.id !== this.chosen().sourceFeatureId,
    ),
  );

  /**
   * The most targets the dialog takes: a paired rule being changed keeps to one, since the two
   * rules of a pair mirror each other.
   */
  protected readonly mostTargets = computed(() =>
    this.editing() && this.chosen().kind === 'EXCLUDES' ? 1 : MOST_TARGETS,
  );

  protected readonly targetsNeeded = computed(() => {
    const fewest = FEWEST_TARGETS[this.chosen().kind];
    if (this.mostTargets() === 1) {
      return 'Choose 1 feature row. The pair of this rule changes with it.';
    }
    const range = `Choose ${fewest} to ${MOST_TARGETS} feature rows.`;
    return this.chosen().kind === 'EXCLUDES'
      ? `${range} Each makes a pair of its own: the rule, and the same rule the other way round.`
      : range;
  });

  /** The pair that is shown highlighted, by its key, or null while none is. */
  protected readonly shownPair = signal<string | null>(null);

  /**
   * How a row of the shown pair looks: a bar in the primary color at its start, and bold text. Its
   * ground stays as it is, since the muted words of a row's actions would not stand out enough
   * from a tinted one.
   */
  protected readonly shownPairRow =
    '[&>td]:font-semibold [&>td:first-child]:shadow-[inset_4px_0_0_var(--p-primary-color)]';

  /** Whether the form holds everything a rule needs. The backend checks the rest. */
  protected readonly complete = computed(() => {
    const { kind, sourceFeatureId, targetFeatureIds, allTrims, trimIds, allRegions, regionCodes } =
      this.chosen();
    return (
      sourceFeatureId !== null &&
      targetFeatureIds.length >= FEWEST_TARGETS[kind] &&
      targetFeatureIds.length <= this.mostTargets() &&
      (allTrims || trimIds.length > 0) &&
      (allRegions || regionCodes.length > 0)
    );
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.chosen.set(this.form.getRawValue()));
    effect(() => {
      if (!this.editable()) {
        this.dialogOpen.set(false);
        this.deleting.set(null);
      }
    });
    void this.readGlobalRules();
  }

  protected async readGlobalRules(): Promise<void> {
    this.globalFailed.set(false);
    try {
      this.everyGlobalRule.set(await this.globalRules.list());
    } catch {
      // The failure has been shown as a message too, which goes away; the tab goes on saying so.
      this.globalFailed.set(true);
    }
  }

  /** Highlights both rules of a pair, or neither when they already are. */
  protected showPair(row: RuleRow): void {
    this.shownPair.update((shown) => (shown === row.pairKey ? null : row.pairKey));
  }

  /** The other rule of a paired rule's pair. */
  protected pairOf(row: RuleRow): RuleRow | undefined {
    return row.pairKey
      ? this.rows().find((other) => other.pairKey === row.pairKey && other.id !== row.id)
      : undefined;
  }

  protected startAdding(): void {
    this.open(null);
    this.form.reset();
    this.form.controls.kind.enable();
  }

  protected startEditing(rule: CatalogRule): void {
    this.open(rule);
    this.form.reset({
      kind: rule.kind,
      sourceFeatureId: rule.sourceFeatureId,
      targetFeatureIds: rule.targetFeatureIds,
      allTrims: rule.allTrims,
      trimIds: rule.trimIds,
      allRegions: rule.allRegions,
      regionCodes: rule.regionCodes,
    });
    // A rule's kind never changes, so it takes no part in an edit.
    this.form.controls.kind.disable();
  }

  /** Saves the rule and closes, or stays open with the refusal. */
  protected async save(): Promise<void> {
    const { sourceFeatureId, allTrims, trimIds, allRegions, regionCodes, ...rest } =
      this.form.getRawValue();
    if (!this.complete() || sourceFeatureId === null || this.saving()) {
      return;
    }
    const content = {
      ...rest,
      sourceFeatureId,
      allTrims,
      trimIds: allTrims ? [] : trimIds,
      allRegions,
      regionCodes: allRegions ? [] : regionCodes,
    };
    const id = this.catalog().snapshot.catalogId;
    const rule = this.editing();

    this.refusal.set('');
    this.saving.set(true);
    try {
      await this.run()((revision) =>
        rule
          ? this.catalogs.changeRule(id, revision, rule.key, content)
          : this.catalogs.addRule(id, revision, content),
      );
      this.dialogOpen.set(false);
    } catch (error) {
      this.refusal.set(reasonOf(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected askToDelete(row: RuleRow): void {
    this.deletionRefusal.set('');
    this.deleting.set(row);
  }

  /** Deletes the rule the owner confirmed, or keeps the question open with the refusal. */
  protected async delete(row: RuleRow): Promise<void> {
    if (this.deletingNow()) {
      return;
    }
    this.deletingNow.set(true);
    try {
      await this.run()((revision) =>
        this.catalogs.deleteRule(this.catalog().snapshot.catalogId, revision, row.id),
      );
      this.deleting.set(null);
    } catch (error) {
      this.deletionRefusal.set(reasonOf(error));
    } finally {
      this.deletingNow.set(false);
    }
  }

  private open(rule: CatalogRule | null): void {
    this.editing.set(rule);
    this.refusal.set('');
    this.dialogOpen.set(true);
  }
}
