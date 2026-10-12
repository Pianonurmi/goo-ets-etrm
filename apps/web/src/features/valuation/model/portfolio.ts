/** The causes of a portfolio value change, in the order the server explains them. */
export const PORTFOLIO_CAUSES = [
  'TRADE_MTM',
  'INVENTORY_PRICE',
  'INVENTORY_IN_OUT',
  'GIVE_AWAY',
  'REALLOCATION',
  'EXPIRY',
  'UNEXPLAINED',
];

/** The figures of the bridge from inventory value to portfolio value; the costs are positive amounts, as the server sends them. */
export interface BridgeFigures {
  inventoryValue: number;
  tradeMtm: number;
  giveAway: number;
  transferFees: number;
  expiryHaircut: number;
  portfolioValue: number;
}

export type WaterfallKind = 'total' | 'gain' | 'loss';

export interface WaterfallStep {
  key: keyof BridgeFigures;
  kind: WaterfallKind;
  /** The server's amount: signed for movements (costs are negative), the level for totals. */
  amount: number;
  /** The bar spans `range`, from the lower to the upper edge; it can cross zero. */
  range: [number, number];
}

const MOVEMENTS: { key: keyof BridgeFigures; sign: 1 | -1 }[] = [
  { key: 'tradeMtm', sign: 1 },
  { key: 'giveAway', sign: -1 },
  { key: 'transferFees', sign: -1 },
  { key: 'expiryHaircut', sign: -1 },
];

/**
 * The bars of the bridge as a waterfall: the inventory value, each movement floating from the running level, and the
 * portfolio value. Presentation only: the offsets use plain numbers for chart geometry, the amounts shown are the
 * server's, and the closing bar is the server's portfolio value rather than the running sum.
 */
export function waterfallSteps(bridge: BridgeFigures): WaterfallStep[] {
  const steps: WaterfallStep[] = [
    { key: 'inventoryValue', kind: 'total', amount: bridge.inventoryValue, range: totalRange(bridge.inventoryValue) },
  ];
  let level = bridge.inventoryValue;
  for (const { key, sign } of MOVEMENTS) {
    // `|| 0` keeps a zero cost from showing as -0.00.
    const amount = sign * bridge[key] || 0;
    const next = level + amount;
    steps.push({
      key,
      kind: amount < 0 ? 'loss' : 'gain',
      amount,
      range: [Math.min(level, next), Math.max(level, next)],
    });
    level = next;
  }
  steps.push({
    key: 'portfolioValue',
    kind: 'total',
    amount: bridge.portfolioValue,
    range: totalRange(bridge.portfolioValue),
  });
  return steps;
}

function totalRange(value: number): [number, number] {
  return [Math.min(0, value), Math.max(0, value)];
}
