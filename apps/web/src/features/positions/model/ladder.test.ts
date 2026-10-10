import { ladderSeries } from './ladder';

describe('ladderSeries', () => {
  it('orders months and shortens labels', () => {
    expect(ladderSeries({ '2026-12-01': 300, '2026-11-01': 125, '2027-01-01': 0 })).toEqual([
      { month: '2026-11', mwh: 125 },
      { month: '2026-12', mwh: 300 },
      { month: '2027-01', mwh: 0 },
    ]);
  });
});
