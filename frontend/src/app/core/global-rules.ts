import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** What a rule says of its source and its targets. */
export type RuleKind = 'REQUIRES' | 'REQUIRES_ONE_OF' | 'INCLUDES' | 'EXCLUDES';

/** The name shown for each kind of rule. */
export const RULE_KIND_NAMES: Record<RuleKind, string> = {
  REQUIRES: 'Requires',
  REQUIRES_ONE_OF: 'Requires one of',
  INCLUDES: 'Includes',
  EXCLUDES: 'Excludes',
};

/** The fewest targets a rule of each kind has. */
export const FEWEST_TARGETS: Record<RuleKind, number> = {
  REQUIRES: 1,
  REQUIRES_ONE_OF: 2,
  INCLUDES: 1,
  EXCLUDES: 1,
};

/** The most targets a rule has. */
export const MOST_TARGETS = 20;

/** A feature as a rule names it. */
export interface NamedFeature {
  id: number;
  code: string;
  name: string;
}

/**
 * A rule of the library, which applies to every catalog: a source feature, its targets, and the
 * regions it applies in. An exclusion holds both ways, so it is kept as two paired rules, A
 * excludes B and B excludes A, that are made, changed, and deleted as one.
 */
export interface GlobalRule {
  id: number;
  kind: RuleKind;
  source: NamedFeature;
  targets: NamedFeature[];
  allRegions: boolean;
  /** The regions the rule applies in. It is empty when the rule applies in every region. */
  regions: { code: string; name: string }[];
  /** What a paired rule shares with its pair, or null for a rule that has none. */
  pairKey: string | null;
}

/** What an admin gives to add a rule or to change one. */
export interface RuleContent {
  kind: RuleKind;
  sourceFeatureId: number;
  targetFeatureIds: number[];
  allRegions: boolean;
  regionCodes: string[];
}

/** A rule as a sentence without its full stop: "Tow Package requires Heavy-Duty Cooling". */
export function ruleInWords(rule: GlobalRule): string {
  return `${rule.source.name} ${RULE_KIND_NAMES[rule.kind].toLowerCase()} ${rule.targets
    .map(({ name }) => name)
    .join(', ')}`;
}

/** Reads the library's global rules, and adds, changes, and deletes them for an admin. */
@Injectable({ providedIn: 'root' })
export class GlobalRules {
  private readonly http = inject(HttpClient);

  /** Every global rule, by the code of its source. */
  list(): Promise<GlobalRule[]> {
    return firstValueFrom(this.http.get<GlobalRule[]>('/api/global-rules'));
  }

  /**
   * Adds a rule, and answers with what was made. An exclusion makes a pair for each of its targets,
   * and the answer holds one rule of each pair.
   */
  add(content: RuleContent): Promise<GlobalRule[]> {
    return firstValueFrom(this.http.post<GlobalRule[]>('/api/global-rules', content));
  }

  /** Changes a rule, and its pair with it. Its kind stays what it was. */
  change(id: number, content: RuleContent): Promise<GlobalRule> {
    return firstValueFrom(this.http.put<GlobalRule>(`/api/global-rules/${id}`, content));
  }

  /** Deletes a rule, and its pair with it. */
  delete(id: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/global-rules/${id}`));
  }
}
