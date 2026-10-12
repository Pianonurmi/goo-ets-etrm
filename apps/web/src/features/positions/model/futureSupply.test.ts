import {
  asTier,
  coverageBars,
  isEmptyView,
  sortedItems,
  type FutureSupplyItem,
  type FutureSupplyView,
} from './futureSupply';

const month = (m: string, demand: number, held: number, contracted: number, expected: number) => ({
  month: m,
  demandMwh: demand,
  coveredHeldMwh: held,
  coveredWithContractedMwh: contracted,
  coveredWithExpectedMwh: expected,
});

const item = (ref: string, kind: FutureSupplyItem['kind'], availableFrom: string): FutureSupplyItem => ({
  ref,
  kind,
  description: ref,
  availableFrom,
  quantityMwh: 100,
  p90Mwh: 80,
});

describe('coverageBars', () => {
  it('splits cumulative coverage into held, purchases, expected issuance and still short', () => {
    expect(coverageBars([month('2026-11-01', 1000, 400, 700, 900)])).toEqual([
      { month: '2026-11', held: 400, purchases: 300, expected: 200, short: 100 },
    ]);
  });

  it('keeps the months in order and never goes negative', () => {
    const bars = coverageBars([month('2026-12', 500, 500, 500, 500), month('2026-11', 300, 400, 380, 390)]);
    expect(bars.map((bar) => bar.month)).toEqual(['2026-11', '2026-12']);
    expect(bars[0]).toEqual({ month: '2026-11', held: 400, purchases: 0, expected: 10, short: 0 });
    expect(bars[1]).toEqual({ month: '2026-12', held: 500, purchases: 0, expected: 0, short: 0 });
  });
});

describe('sortedItems', () => {
  it('orders by availability, then contracted before expected', () => {
    const sorted = sortedItems([
      item('b', 'EXPECTED', '2026-12-05'),
      item('c', 'CONTRACTED', '2027-01-01'),
      item('a', 'CONTRACTED', '2026-12-05'),
    ]);
    expect(sorted.map((row) => row.ref)).toEqual(['a', 'b', 'c']);
  });
});

describe('tiers and emptiness', () => {
  it('falls back to held for an unknown tier', () => {
    expect(asTier('EXPECTED')).toBe('EXPECTED');
    expect(asTier('nonsense')).toBe('HELD');
  });

  it('is empty without items and months', () => {
    const view: FutureSupplyView = {
      holderEntityId: 'e',
      stamp: 's',
      computedAt: '2026-10-12T08:00:00Z',
      items: [],
      months: [],
      totalDemandMwh: 0,
      coveredHeldMwh: 0,
      coveredWithContractedMwh: 0,
      coveredWithExpectedMwh: 0,
    };
    expect(isEmptyView(view)).toBe(true);
    expect(isEmptyView({ ...view, items: [item('a', 'EXPECTED', '2026-12-01')] })).toBe(false);
  });
});
