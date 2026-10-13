import dayjs from 'dayjs';
import type { Schema } from '@/shared/api/types';
import type { LifecycleStep } from '@/shared/components/LifecycleTimeline';

type Trade = Schema<'TradeView'>;
type Version = Pick<Schema<'TradeVersionView'>, 'version' | 'status' | 'recordedAt' | 'recordedBy'>;
type Status = Trade['status'];
type Obligation = Pick<Schema<'DeliveryObligationView'>, 'deliveryDate'>;

export type TradeStepKey =
  'captured' | 'approved' | 'confirmed' | 'delivered' | 'settled' | 'expired' | 'cancelled' | 'disputed';
export type TradeStepLabel = TradeStepKey | 'partlyDelivered';

const PATH: TradeStepKey[] = ['captured', 'approved', 'confirmed', 'delivered', 'settled'];
/** An option delivers nothing: it ends when it is exercised or abandoned at expiry, and the trade closes (ADR 0047). */
const OPTION_PATH: TradeStepKey[] = ['captured', 'approved', 'confirmed', 'expired'];

/** How far along the path a status is; cancelled and disputed trades leave the path. */
const RANK: Partial<Record<Status, number>> = {
  DRAFT: 0,
  PENDING_APPROVAL: 0,
  APPROVED: 1,
  CONFIRMED: 2,
  PARTIALLY_DELIVERED: 2,
  DELIVERED: 3,
  SETTLED: 4,
  CLOSED: 4,
};

function firstVersion(versions: Version[], statuses: Status[]): Version | undefined {
  return [...versions].sort((a, b) => a.version - b.version).find((version) => statuses.includes(version.status));
}

/**
 * The trade's way from capture to settlement for the lifecycle timeline: when and by whom each step was reached,
 * read from the trade and its versions (every status change is a version). A cancelled or disputed trade ends in a
 * failed step after the last step it reached.
 */
export function tradeSteps(
  trade: Pick<Trade, 'status' | 'createdBy' | 'approvedAt' | 'approvedBy' | 'confirmedAt' | 'confirmationRef'> & {
    instrumentType?: Trade['instrumentType'];
  },
  versions: Version[],
  label: (key: TradeStepLabel) => string,
): LifecycleStep[] {
  const ended = trade.status === 'CANCELLED' || trade.status === 'DISPUTED';
  const option = trade.instrumentType === 'OPTION';
  const path = option ? OPTION_PATH : PATH;
  const rank = (status: Status) => (option && status === 'CLOSED' ? OPTION_PATH.length - 1 : (RANK[status] ?? 0));
  const fromVersions = Math.max(0, ...versions.map((version) => rank(version.status)));
  const fromTrade = trade.confirmedAt ? 2 : trade.approvedBy ? 1 : 0;
  const reached = ended ? Math.max(fromVersions, fromTrade) : rank(trade.status);
  const captured = [...versions].sort((a, b) => a.version - b.version)[0];
  const delivered = firstVersion(versions, ['DELIVERED']);
  const settled = firstVersion(versions, ['SETTLED', 'CLOSED']);
  const when: Record<TradeStepKey, Pick<LifecycleStep, 'at' | 'by' | 'detail'>> = {
    captured: { at: captured?.recordedAt, by: trade.createdBy },
    approved: { at: trade.approvedAt, by: trade.approvedBy },
    confirmed: { at: trade.confirmedAt, detail: trade.confirmationRef },
    delivered: { at: delivered?.recordedAt, by: delivered?.recordedBy },
    settled: { at: settled?.recordedAt, by: settled?.recordedBy },
    expired: { at: settled?.recordedAt, by: settled?.recordedBy },
    cancelled: {},
    disputed: {},
  };
  const partly = trade.status === 'PARTIALLY_DELIVERED';
  const steps: LifecycleStep[] = [];
  path.forEach((key, index) => {
    if (ended && index > reached) return;
    if (index < reached || (index === reached && index === path.length - 1)) {
      steps.push({ label: label(key), state: 'done', ...when[key] });
    } else if (index === reached) {
      steps.push({ label: label(key), state: ended || partly ? 'done' : 'current', ...when[key] });
    } else if (partly && key === 'delivered') {
      steps.push({ label: label(key), state: 'current', detail: label('partlyDelivered') });
    } else {
      steps.push({ label: label(key), state: 'pending' });
    }
  });
  if (ended) {
    const key: TradeStepKey = trade.status === 'CANCELLED' ? 'cancelled' : 'disputed';
    const version = firstVersion(versions, [trade.status]);
    steps.push({ label: label(key), state: 'failed', at: version?.recordedAt, by: version?.recordedBy });
  }
  return steps;
}

/** Open deliveries due on or before `days` from today (counted, never summed: quantities stay on the server). */
export function dueWithin(obligations: Obligation[], today: string, days: number): number {
  const limit = dayjs(today).add(days, 'day').format('YYYY-MM-DD');
  return obligations.filter((obligation) => obligation.deliveryDate <= limit).length;
}
