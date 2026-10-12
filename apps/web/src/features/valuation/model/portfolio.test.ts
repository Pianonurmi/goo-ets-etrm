import { explainBars } from './explain';
import { PORTFOLIO_CAUSES, waterfallSteps } from './portfolio';

const bridge = {
  inventoryValue: 1000,
  tradeMtm: 200,
  giveAway: 150,
  transferFees: 20,
  expiryHaircut: 30,
  portfolioValue: 1000,
};

describe('waterfallSteps', () => {
  it('floats each movement from the running level and closes with the server value', () => {
    const steps = waterfallSteps(bridge);
    expect(steps.map((s) => s.key)).toEqual([
      'inventoryValue',
      'tradeMtm',
      'giveAway',
      'transferFees',
      'expiryHaircut',
      'portfolioValue',
    ]);
    expect(steps.map((s) => s.kind)).toEqual(['total', 'gain', 'loss', 'loss', 'loss', 'total']);
    expect(steps.map((s) => s.range)).toEqual([
      [0, 1000],
      [1000, 1200],
      [1050, 1200],
      [1030, 1050],
      [1000, 1030],
      [0, 1000],
    ]);
    expect(steps.map((s) => s.amount)).toEqual([1000, 200, -150, -20, -30, 1000]);
  });

  it('lets a negative trade MtM fall below the inventory value and bars cross zero', () => {
    const steps = waterfallSteps({ ...bridge, inventoryValue: 100, tradeMtm: -300, portfolioValue: -400 });
    expect(steps[1]).toMatchObject({ kind: 'loss', amount: -300, range: [-200, 100] });
    expect(steps[2]?.range).toEqual([-350, -200]);
    expect(steps[5]?.range).toEqual([-400, 0]);
  });
});

describe('portfolio causes', () => {
  it('orders causes as the server explains them and leaves out causes with no effect', () => {
    const bars = explainBars(
      { UNEXPLAINED: 1, EXPIRY: -2, TRADE_MTM: 5, GIVE_AWAY: 0, INVENTORY_PRICE: 3 },
      (c) => c,
      PORTFOLIO_CAUSES,
    );
    expect(bars.map((b) => b.component)).toEqual(['TRADE_MTM', 'INVENTORY_PRICE', 'EXPIRY', 'UNEXPLAINED']);
  });
});
