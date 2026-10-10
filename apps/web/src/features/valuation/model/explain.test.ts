import { explainBars } from './explain';

describe('explainBars', () => {
  it('orders components by size of effect and labels them', () => {
    expect(explainBars({ NEW_TRADES: 100, PRICE_MOVE: -1890, FX: 0 })).toEqual([
      { component: 'price move', amount: -1890 },
      { component: 'new trades', amount: 100 },
      { component: 'fx', amount: 0 },
    ]);
  });
});
