import type { Schema } from '@/shared/api/types';

type Trade = Schema<'TradeView'>;
type Leg = Schema<'TradeLegView'>;
type Schedule = Schema<'DeliveryScheduleView'>;

/** Manual delivery actions on one delivery schedule; the server checks status, roles and quantities again. */
export type DeliveryAction = 'record' | 'deliverAllowances' | 'matchReceipt';

/** Mirrors TradeAggregate.recordDelivery: confirmed trades, or approved ones whose obligations open at approval. */
export function deliverable(trade: Trade): boolean {
  if (trade.status === 'CONFIRMED' || trade.status === 'PARTIALLY_DELIVERED') return true;
  return trade.status === 'APPROVED' && trade.obligationsOpenAt === 'APPROVED';
}

export function deliveryActions(trade: Trade, leg: Leg, schedule: Schedule): DeliveryAction[] {
  if (!deliverable(trade) || schedule.status === 'DELIVERED' || schedule.status === 'CANCELLED') return [];
  if (!(schedule.remainingQuantity > 0)) return [];
  const actions: DeliveryAction[] = ['record'];
  const allowance = trade.productFamily === 'EMISSION_ALLOWANCE';
  // An allowance sale is delivered by a Union Registry transfer at a fixed price.
  if (leg.side === 'SELL' && allowance && leg.priceType === 'FIXED') actions.unshift('deliverAllowances');
  if (leg.side === 'BUY') actions.unshift('matchReceipt');
  return actions;
}

export function canSettle(trade: Trade): boolean {
  return trade.status === 'DELIVERED';
}

/** Open trades can be checked against the current limits again. */
export function canCheckLimits(trade: Trade): boolean {
  return ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_DELIVERED'].includes(trade.status);
}
