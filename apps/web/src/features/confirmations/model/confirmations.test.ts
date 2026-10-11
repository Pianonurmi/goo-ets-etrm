import { describe, expect, it } from 'vitest';
import {
  asQueueView,
  enteredTerms,
  headerDifferences,
  oursFor,
  queueStatuses,
  termRows,
  type Confirmation,
  type Terms,
} from './confirmations';

const terms: Terms = {
  tradeRef: 'T-1',
  tradeDate: '2026-10-09',
  productFamily: 'GUARANTEE_OF_ORIGIN',
  legs: [
    {
      legNo: 1,
      seller: { name: 'Demo Energia Oy', lei: '529900DEMOFI00000137' },
      buyer: { name: 'Rheinstrom' },
      product: 'ELECTRICITY',
      quantity: 250,
      unit: 'MWH',
      price: 2,
      currency: 'EUR',
      amount: 500,
      deliveries: [{ deliveryDate: '2026-10-11', quantity: 250 }],
      attributes: { energySource: 'HYDRO' },
    },
  ],
};

describe('confirmations', () => {
  it('maps queue views to statuses', () => {
    expect(queueStatuses('open')).toEqual(['RECEIVED', 'MISMATCHED', 'DISPUTED']);
    expect(asQueueView('settled')).toBe('settled');
    expect(asQueueView(null)).toBe('open');
  });

  it('lines up our terms with theirs and marks what the matcher found', () => {
    const theirs = enteredTerms(terms, { quantity: '250', price: '2.10', amount: '525.00', deliveryDate: '' });
    const rows = termRows(terms, theirs, [
      { field: 'legs[1].price', ours: '2', theirs: '2.10', severity: 'MISMATCH' },
      { field: 'legs[1].amount', ours: '500', theirs: '525.00', severity: 'MISMATCH' },
      { field: 'legs[1].buyer.lei', ours: null, theirs: 'X', severity: 'NOTE' },
      { field: 'legs[1].deliveries[0].deliveryDate', ours: 'a', theirs: 'b', severity: 'MISMATCH' },
    ]);
    const byKey = Object.fromEntries(rows.map((row) => [row.key, row]));
    expect(byKey.price).toEqual({ key: 'price', ours: '2 EUR', theirs: '2.10 EUR', severity: 'MISMATCH' });
    expect(byKey.quantity?.severity).toBeNull();
    expect(byKey['buyer.lei']).toBeUndefined();
    expect(byKey.deliveries?.severity).toBe('MISMATCH');
    expect(byKey['attributes.energySource']?.ours).toBe('HYDRO');
  });

  it('keeps our terms except the first leg the counterparty states', () => {
    const entered = enteredTerms(terms, {
      quantity: '240',
      price: '2.10',
      amount: '504.00',
      deliveryDate: '2026-10-12',
    });
    const leg = entered.legs[0];
    expect(leg?.price).toBe('2.10');
    expect(leg?.deliveries[0]).toEqual({ deliveryDate: '2026-10-12', quantity: '240' });
    expect(leg?.seller).toEqual(terms.legs[0]?.seller);
    expect(entered.tradeRef).toBe('T-1');
  });

  it('separates header differences and finds the confirmation of ours to compare with', () => {
    expect(
      headerDifferences([
        { field: 'tradeDate', ours: 'a', theirs: 'b', severity: 'MISMATCH' },
        { field: 'legs[1].price', ours: 'a', theirs: 'b', severity: 'MISMATCH' },
      ]).map((d) => d.field),
    ).toEqual(['tradeDate']);
    const ours1 = { id: 'o1', direction: 'OURS', status: 'SUPERSEDED', tradeVersion: 1 } as Confirmation;
    const ours2 = { id: 'o2', direction: 'OURS', status: 'ISSUED', tradeVersion: 2 } as Confirmation;
    const theirs = { id: 't', direction: 'THEIRS', matchedAgainst: 'o1' } as Confirmation;
    expect(oursFor(theirs, [ours1, ours2, theirs])?.id).toBe('o1');
    expect(oursFor({ ...theirs, matchedAgainst: null }, [ours1, ours2, theirs])?.id).toBe('o2');
  });
});
