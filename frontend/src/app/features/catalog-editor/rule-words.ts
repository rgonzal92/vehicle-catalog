import { Catalog, CatalogRule } from '../../core/catalogs';
import { RULE_KIND_NAMES, RuleKind } from '../../core/global-rules';

/**
 * A rule as a sentence without its full stop: "Tow Package requires Heavy-Duty Cooling (on Sport;
 * in Europe)". A scope that is null covers everything, and is not said.
 */
export function ruleSentence(
  kind: RuleKind,
  source: string,
  targets: string[],
  trims: string[] | null,
  regions: string[] | null,
): string {
  const said = `${source} ${RULE_KIND_NAMES[kind].toLowerCase()} ${targets.join(', ')}`;
  const scopes = [trims && `on ${trims.join(', ')}`, regions && `in ${regions.join(', ')}`]
    .filter(Boolean)
    .join('; ');

  return scopes ? `${said} (${scopes})` : said;
}

/**
 * The rules of a catalog that the test picks, each as a sentence by the names the catalog shows. A
 * pair says the same thing both ways, so it is said once.
 */
export function rulesInWords(
  { featureRows, trims, regions, rules }: Catalog['snapshot'],
  picked: (rule: CatalogRule) => boolean,
): string[] {
  const features = new Map(featureRows.map(({ id, name }) => [id, name]));
  const trimNames = new Map(trims.map(({ id, name }) => [id, name]));
  const regionNames = new Map(regions.map(({ code, name }) => [code, name]));
  const named = <Key>(names: Map<Key, string>, keys: Key[]) =>
    keys.map((key) => names.get(key) ?? String(key));
  const pairsSaid = new Set<string>();
  const notSaidYet = ({ pairKey }: CatalogRule) => {
    if (pairKey === null) {
      return true;
    }
    const said = pairsSaid.has(pairKey);
    pairsSaid.add(pairKey);
    return !said;
  };

  // A backend that is one release behind answers without rules.
  return (rules ?? [])
    .filter(picked)
    .filter(notSaidYet)
    .map((rule) =>
      ruleSentence(
        rule.kind,
        named(features, [rule.sourceFeatureId])[0],
        named(features, rule.targetFeatureIds),
        rule.allTrims ? null : named(trimNames, rule.trimIds),
        rule.allRegions ? null : named(regionNames, rule.regionCodes),
      ),
    );
}
