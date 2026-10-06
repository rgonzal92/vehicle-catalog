import { startPointInWords } from './catalogs';

describe('startPointInWords', () => {
  it("names the lineage's own Approved version", () => {
    expect(startPointInWords({ kind: 'COPY', modelYear: 2027, versionNumber: 3 })).toBe(
      'Starts from Approved v3',
    );
  });

  it('names the earlier model year of a carryover', () => {
    expect(startPointInWords({ kind: 'CARRYOVER', modelYear: 2026, versionNumber: 2 })).toBe(
      'Starts from 2026 Approved v2 (carryover)',
    );
  });

  it('says when there is nothing to start from', () => {
    expect(startPointInWords({ kind: 'EMPTY' })).toBe('Starts empty');
  });
});
