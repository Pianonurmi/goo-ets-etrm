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

  it('puts the passage of time before the price and the discount rate moves between price and FX', () => {
    const all = {
      UNEXPLAINED: 1,
      FX: 2,
      VOL: 10,
      RATES: 3,
      PRICE: 4,
      TIME: 5,
      DELIVERED: 6,
      CANCELLED: 7,
      AMENDED: 8,
      NEW: 9,
    };
    expect(explainBars(all, (c) => c).map((bar) => bar.component)).toEqual([
      'NEW',
      'AMENDED',
      'CANCELLED',
      'DELIVERED',
      'TIME',
      'PRICE',
      'VOL',
      'RATES',
      'FX',
      'UNEXPLAINED',
    ]);
  });
});
