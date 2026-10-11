import type { Body, Schema } from '@/shared/api/types';

export type Confirmation = Schema<'ConfirmationView'>;
export type ConfirmationStatus = Confirmation['status'];
export type Terms = Confirmation['terms'];
export type Difference = Confirmation['differences'][number];
type ReceivedTerms = Body<'ReceiveConfirmation'>['terms'];

export const STATUS_COLORS: Record<ConfirmationStatus, string> = {
  ISSUED: 'blue',
  SUPERSEDED: 'gray',
  RECEIVED: 'yellow',
  MATCHED: 'teal',
  MISMATCHED: 'red',
  OVERRIDDEN: 'grape',
  DISPUTED: 'orange',
};

export const QUEUE_VIEWS = ['open', 'settled', 'all'] as const;
export type QueueView = (typeof QUEUE_VIEWS)[number];

const VIEW_STATUSES: Record<QueueView, ConfirmationStatus[]> = {
  open: ['RECEIVED', 'MISMATCHED', 'DISPUTED'],
  settled: ['MATCHED', 'OVERRIDDEN'],
  all: ['RECEIVED', 'MISMATCHED', 'DISPUTED', 'MATCHED', 'OVERRIDDEN', 'SUPERSEDED'],
};

export function queueStatuses(view: QueueView): ConfirmationStatus[] {
  return VIEW_STATUSES[view];
}

export function asQueueView(value: string | null): QueueView {
  return QUEUE_VIEWS.find((view) => view === value) ?? 'open';
}

export interface TermRow {
  key: string;
  ours: string;
  theirs: string;
  /** A difference the matcher recorded for this field (MISMATCH or NOTE). */
  severity: Difference['severity'] | null;
}

type Leg = Terms['legs'][number];

function legFields(leg: Leg | undefined): Record<string, string> {
  if (!leg) return {};
  const fields: Record<string, string> = {
    'seller.name': leg.seller.name ?? '',
    'seller.lei': leg.seller.lei ?? '',
    'buyer.name': leg.buyer.name ?? '',
    'buyer.lei': leg.buyer.lei ?? '',
    product: leg.product,
    quantity: `${leg.quantity} ${leg.unit}`,
    price: `${leg.price} ${leg.currency}`,
    amount: `${leg.amount} ${leg.currency}`,
    sellerAccount: leg.sellerAccount ?? '',
    buyerAccount: leg.buyerAccount ?? '',
    deliveries: leg.deliveries.map((d) => `${d.deliveryDate} ${d.quantity}`).join(', '),
  };
  Object.entries(leg.attributes ?? {}).forEach(([key, value]) => {
    fields[`attributes.${key}`] = value;
  });
  return fields;
}

/**
 * Our terms and theirs side by side for one leg, with the matcher's verdict on each field. Fields are named as the
 * matcher names them (`legs[1].price`); quantity, price and amount carry their unit or currency.
 */
export function termRows(ours: Terms | undefined, theirs: Terms, differences: Difference[], legNo = 1): TermRow[] {
  const mine = legFields(ours?.legs.find((leg) => (leg.legNo ?? 1) === legNo));
  const other = legFields(theirs.legs.find((leg) => (leg.legNo ?? 1) === legNo));
  const prefix = `legs[${legNo}].`;
  const verdicts = new Map<string, Difference['severity']>();
  differences
    .filter((difference) => difference.field.startsWith(prefix))
    .forEach((difference) => {
      const field = difference.field.slice(prefix.length);
      const key = field.startsWith('deliveries') ? 'deliveries' : field;
      if (verdicts.get(key) !== 'MISMATCH') verdicts.set(key, difference.severity ?? 'MISMATCH');
    });
  const keys = [...new Set([...Object.keys(mine), ...Object.keys(other)])];
  return keys
    .filter((key) => mine[key] || other[key])
    .map((key) => ({ key, ours: mine[key] ?? '', theirs: other[key] ?? '', severity: verdicts.get(key) ?? null }));
}

/** Differences outside the legs (trade date, product family, number of legs). */
export function headerDifferences(differences: Difference[]): Difference[] {
  return differences.filter((difference) => !difference.field.startsWith('legs['));
}

export interface LegEdits {
  quantity: string;
  price: string;
  amount: string;
  deliveryDate: string;
}

/**
 * The counterparty's terms entered by hand: our terms with the first leg's quantity, price, amount and delivery
 * date as the counterparty states them. Values stay strings (decimals for the server); nothing is computed here.
 */
export function enteredTerms(ours: Terms, edits: LegEdits): ReceivedTerms {
  const [first, ...rest] = ours.legs;
  if (!first) return ours;
  const deliveries = first.deliveries.map((delivery, index) =>
    index === 0
      ? {
          ...delivery,
          deliveryDate: edits.deliveryDate || delivery.deliveryDate,
          quantity: edits.quantity as unknown as number,
        }
      : delivery,
  );
  const leg = {
    ...first,
    quantity: edits.quantity as unknown as number,
    price: edits.price as unknown as number,
    amount: edits.amount as unknown as number,
    deliveries,
  };
  return { ...ours, legs: [leg, ...rest] };
}

/** The confirmation of ours a received one is compared with: the one it was matched against, else the latest issued. */
export function oursFor(theirs: Confirmation, all: Confirmation[]): Confirmation | undefined {
  const issued = all.filter((c) => c.direction === 'OURS');
  return (
    issued.find((c) => c.id === theirs.matchedAgainst) ??
    issued.filter((c) => c.status === 'ISSUED').sort((a, b) => b.tradeVersion - a.tradeVersion)[0]
  );
}
