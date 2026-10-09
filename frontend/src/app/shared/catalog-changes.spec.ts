import { TestBed } from '@angular/core/testing';
import { CatalogChanges } from '../core/catalogs';
import { CatalogChangesList } from './catalog-changes';

const nothing: CatalogChanges = {
  trimsAdded: [],
  trimsRemoved: [],
  regionsAdded: [],
  regionsRemoved: [],
  offeringsAdded: [],
  offeringsRemoved: [],
  featureRowsAdded: [],
  featureRowsRemoved: [],
  cellsChanged: [],
  rulesAdded: [],
  rulesRemoved: [],
  rulesChanged: [],
};

describe('CatalogChangesList', () => {
  async function list(changes: CatalogChanges): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(CatalogChangesList);
    fixture.componentRef.setInput('changes', changes);
    fixture.componentRef.setInput('none', 'Nothing differs between the two versions.');
    await fixture.whenStable();

    return fixture.nativeElement as HTMLElement;
  }

  const rows = (element: HTMLElement, heading: string) => {
    const section = Array.from(element.querySelectorAll('section')).find(
      (candidate) => candidate.querySelector('h3')?.textContent?.trim() === heading,
    );
    return Array.from(section?.querySelectorAll('tbody tr') ?? [], (row) =>
      Array.from(row.querySelectorAll('td'), (cell) =>
        cell.textContent?.replace(/\s+/g, ' ').trim(),
      ),
    );
  };

  it('says so when nothing changed', async () => {
    const element = await list(nothing);

    expect(element.textContent).toContain('Nothing differs between the two versions.');
    expect(element.querySelector('table')).toBeNull();
  });

  it('lists what was added and removed, each with what kind of thing it is', async () => {
    const element = await list({
      ...nothing,
      trimsAdded: [{ id: 3, name: 'Touring', sortOrder: 3 }],
      regionsRemoved: [{ code: 'EU', name: 'Europe' }],
      offeringsAdded: [{ trimId: 3, trim: 'Touring', regionCode: 'NA', region: 'North America' }],
      featureRowsAdded: [
        {
          id: 7,
          code: 'POWERTRAIN_HYBRID',
          kind: 'FEATURE',
          name: 'Hybrid Powertrain',
          categoryCode: 'POWERTRAIN',
        },
      ],
    });

    expect(rows(element, 'Trims, regions, offerings, and feature rows')).toEqual([
      ['Added', 'Trim', 'Touring'],
      ['Removed', 'Region', 'Europe'],
      ['Added', 'Offering', 'Touring in North America'],
      ['Added', 'Feature row', 'Hybrid Powertrain (POWERTRAIN_HYBRID)'],
    ]);
    expect(element.textContent).not.toContain('Nothing differs');
  });

  it('lists each changed cell with its availability before and after, in words', async () => {
    const element = await list({
      ...nothing,
      cellsChanged: [
        {
          featureId: 8,
          featureCode: 'PACKAGE_TOW',
          feature: 'Tow Package',
          trimId: 2,
          trim: 'Sport',
          regionCode: 'NA',
          region: 'North America',
          before: 'N',
          after: 'A',
        },
      ],
    });

    expect(rows(element, 'Cells')).toEqual([
      ['Tow Package (PACKAGE_TOW)', 'Sport in North America', 'Not offered', 'Available'],
    ]);
  });

  it('lists the rules added, removed, and changed, a changed one with what it said before', async () => {
    const element = await list({
      ...nothing,
      rulesAdded: [{ key: 'a', rule: 'Tow Package requires Heavy-Duty Cooling' }],
      rulesRemoved: [{ key: 'b', rule: 'Panoramic Roof excludes Removable Roof' }],
      rulesChanged: [
        {
          key: 'c',
          before: 'Tow Package includes Trailer Hitch Receiver',
          after: 'Tow Package includes Trailer Hitch Receiver (in Europe)',
        },
      ],
    });

    expect(rows(element, 'Rules')).toEqual([
      ['Added', 'Tow Package requires Heavy-Duty Cooling'],
      ['Removed', 'Panoramic Roof excludes Removable Roof'],
      [
        'Changed',
        'Tow Package includes Trailer Hitch Receiver (in Europe) Before: Tow Package includes Trailer Hitch Receiver',
      ],
    ]);
  });
});
