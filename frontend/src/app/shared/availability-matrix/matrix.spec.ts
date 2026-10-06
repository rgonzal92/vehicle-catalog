import { matrixRows, offeringsByRegion } from './matrix';

describe('offeringsByRegion', () => {
  const base = { id: 1, name: 'Base', sortOrder: 1 };
  const sport = { id: 2, name: 'Sport', sortOrder: 2 };
  const luxury = { id: 3, name: 'Luxury', sortOrder: 3 };
  const northAmerica = { code: 'NA', name: 'North America' };
  const europe = { code: 'EU', name: 'Europe' };

  it('puts the regions on top in the order given and, under each, the trims sold there', () => {
    const byRegion = offeringsByRegion({
      trims: [base, sport, luxury],
      regions: [northAmerica, europe],
      offerings: [
        { trimId: 1, regionCode: 'NA' },
        { trimId: 2, regionCode: 'NA' },
        { trimId: 3, regionCode: 'NA' },
        { trimId: 1, regionCode: 'EU' },
        { trimId: 3, regionCode: 'EU' },
      ],
    });

    expect(byRegion).toEqual([
      { region: northAmerica, trims: [base, sport, luxury] },
      { region: europe, trims: [base, luxury] },
    ]);
  });

  it('orders the trims of a region by their sort order, whatever order they arrive in', () => {
    const [onlyRegion] = offeringsByRegion({
      trims: [luxury, base, sport],
      regions: [northAmerica],
      offerings: [
        { trimId: 3, regionCode: 'NA' },
        { trimId: 2, regionCode: 'NA' },
        { trimId: 1, regionCode: 'NA' },
      ],
    });

    expect(onlyRegion.trims).toEqual([base, sport, luxury]);
  });

  it('gives a trim no offering in a region where it is not sold', () => {
    const [onlyRegion] = offeringsByRegion({
      trims: [base, sport],
      regions: [europe],
      offerings: [{ trimId: 2, regionCode: 'EU' }],
    });

    expect(onlyRegion.trims).toEqual([sport]);
  });

  it('leaves out a region where no trim is sold', () => {
    const byRegion = offeringsByRegion({
      trims: [base],
      regions: [northAmerica, europe],
      offerings: [{ trimId: 1, regionCode: 'EU' }],
    });

    expect(byRegion.map(({ region }) => region.code)).toEqual(['EU']);
  });
});

describe('matrixRows', () => {
  const categories = [
    { code: 'POWERTRAIN', name: 'Powertrain' },
    { code: 'EXTERIOR', name: 'Exterior' },
    { code: 'PACKAGES', name: 'Packages' },
  ];
  const feature = (id: number, code: string, categoryCode: string) => ({
    id,
    code,
    name: code.toLowerCase(),
    categoryCode,
  });

  it('groups feature rows under their categories in display order, by code inside each', () => {
    const roof = feature(1, 'ROOF_PANORAMIC', 'EXTERIOR');
    const engine = feature(2, 'ENGINE_20T', 'POWERTRAIN');
    const paint = feature(3, 'PAINT_WHITE', 'EXTERIOR');

    expect(matrixRows([roof, engine, paint], categories)).toEqual([
      { category: categories[0] },
      { feature: engine },
      { category: categories[1] },
      { feature: paint },
      { feature: roof },
    ]);
  });

  it('shows no subheader for a category without feature rows', () => {
    const rows = matrixRows([feature(1, 'TOW_PACKAGE', 'PACKAGES')], categories);

    expect(rows).toEqual([{ category: categories[2] }, { feature: rows[1].feature }]);
  });

  it('keeps a feature row whose category it does not know, under the category code, at the end', () => {
    const rows = matrixRows(
      [feature(1, 'ODD_ONE', 'UNKNOWN'), feature(2, 'ENGINE_20T', 'POWERTRAIN')],
      categories,
    );

    expect(rows[2]).toEqual({ category: { code: 'UNKNOWN', name: 'UNKNOWN' } });
    expect(rows[3].feature?.code).toBe('ODD_ONE');
  });
});
