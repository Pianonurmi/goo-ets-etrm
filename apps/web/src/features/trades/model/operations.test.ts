import type { Schema } from '@/shared/api/types';
import { canCheckLimits, canSettle, deliverable, deliveryActions } from './operations';

type Trade = Schema<'TradeView'>;
type Leg = Schema<'TradeLegView'>;
type Schedule = Schema<'DeliveryScheduleView'>;

const trade = (overrides: Partial<Trade>) =>
  ({
    status: 'CONFIRMED',
    productFamily: 'GUARANTEE_OF_ORIGIN',
    obligationsOpenAt: 'CONFIRMED',
    ...overrides,
  }) as Trade;
const leg = (overrides: Partial<Leg>) => ({ side: 'SELL', priceType: 'FIXED', ...overrides }) as Leg;
const schedule = (overrides: Partial<Schedule>) =>
  ({ status: 'PENDING', remainingQuantity: 100, ...overrides }) as Schedule;

describe('trade operations', () => {
  it('delivers confirmed trades, and approved ones only when obligations open at approval', () => {
    expect(deliverable(trade({}))).toBe(true);
    expect(deliverable(trade({ status: 'APPROVED' }))).toBe(false);
    expect(deliverable(trade({ status: 'APPROVED', obligationsOpenAt: 'APPROVED' }))).toBe(true);
    expect(deliverable(trade({ status: 'DELIVERED' }))).toBe(false);
  });

  it('offers a registry transfer for fixed-price allowance sales and receipt matching for purchases', () => {
    const eua = trade({ productFamily: 'EMISSION_ALLOWANCE' });
    expect(deliveryActions(eua, leg({}), schedule({}))).toEqual(['deliverAllowances', 'record']);
    expect(deliveryActions(eua, leg({ priceType: 'INDEX' }), schedule({}))).toEqual(['record']);
    expect(deliveryActions(trade({}), leg({}), schedule({}))).toEqual(['record']);
    expect(deliveryActions(trade({}), leg({ side: 'BUY' }), schedule({}))).toEqual(['matchReceipt', 'record']);
  });

  it('offers nothing for a delivered schedule or a trade that is not deliverable', () => {
    expect(deliveryActions(trade({}), leg({}), schedule({ status: 'DELIVERED', remainingQuantity: 0 }))).toEqual([]);
    expect(deliveryActions(trade({ status: 'PENDING_APPROVAL' }), leg({}), schedule({}))).toEqual([]);
  });

  it('settles delivered trades and checks limits of open ones', () => {
    expect(canSettle(trade({ status: 'DELIVERED' }))).toBe(true);
    expect(canSettle(trade({}))).toBe(false);
    expect(canCheckLimits(trade({ status: 'PENDING_APPROVAL' }))).toBe(true);
    expect(canCheckLimits(trade({ status: 'SETTLED' }))).toBe(false);
  });
});
