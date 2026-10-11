import { explainBars } from './explain';

describe('explainBars', () => {
  it('orders components as they are explained, labels them and leaves out causes with no effect', () => {
    expect(explainBars({ PRICE: -1890, NEW: 100, FX: 0, UNEXPLAINED: 3, OTHER: 1 }, (c) => c.toLowerCase())).toEqual([
      { component: 'new', amount: 100 },
      { component: 'price', amount: -1890 },
      { component: 'unexplained', amount: 3 },
      { component: 'other', amount: 1 },
    ]);
  });
});
