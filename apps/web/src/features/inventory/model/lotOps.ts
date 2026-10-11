import dayjs from 'dayjs';
import { decimal, type Body, type OperationId, type Schema } from '@/shared/api/types';

type Lot = Schema<'LotView'>;
type LotStatus = Lot['status'];

/** The manual operations on one lot, in the order the lot page offers them. */
export const LOT_ACTIONS = [
  'transfer',
  'registryCancel',
  'reserve',
  'release',
  'split',
  'correct',
  'cancel',
  'withdraw',
] as const;
export type LotAction = (typeof LOT_ACTIONS)[number];

/** The operation behind each action, for the role table (`useCan`). */
export const LOT_ACTION_OPS: Record<LotAction, OperationId> = {
  transfer: 'registry_transfer',
  registryCancel: 'registry_cancellation',
  reserve: 'inventory_reserve',
  release: 'inventory_release',
  split: 'inventory_split',
  correct: 'inventory_correct',
  cancel: 'inventory_cancel',
  withdraw: 'inventory_withdraw',
};

/** Actions that send an instruction to the registry, as opposed to changing our books only. */
export const REGISTRY_ACTIONS: ReadonlySet<LotAction> = new Set(['transfer', 'registryCancel']);

const LIVE: LotStatus[] = ['HELD', 'RESERVED', 'IN_TRANSFER'];

/**
 * The actions a lot's status allows (mirrors `CertificateLot` and `RegistryInstructionService.prepareLot`). Registry
 * instructions, reservations, splits and cancellations start from a held lot: a reserved lot is earmarked for an
 * allocation plan and leaves it through the plan. A reservation is released only with its reference.
 */
export function lotActions(lot: Pick<Lot, 'status' | 'reservationRef'>): LotAction[] {
  const held = lot.status === 'HELD';
  const live = LIVE.includes(lot.status);
  const allowed: Record<LotAction, boolean> = {
    transfer: held,
    registryCancel: held,
    reserve: held,
    release: lot.status === 'RESERVED' && Boolean(lot.reservationRef),
    split: held,
    correct: live,
    cancel: held,
    withdraw: live,
  };
  return LOT_ACTIONS.filter((action) => allowed[action]);
}

/**
 * A fresh reservation reference (a version 4 UUID). `crypto.randomUUID` exists only in secure contexts; the
 * showcase runs on plain HTTP until it has a domain, and `getRandomValues` works in both.
 */
export function newReservationRef(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** A reservation of the whole lot (empty quantity) or of its first certificates, which are split off first. */
export function reservationBody(quantity: number | '', reservationRef: string): Body<'ReservationRequest'> {
  return { reservationRef, quantity: quantity === '' ? null : quantity };
}

/** Whether a reservation quantity is acceptable: empty (the whole lot) or between 1 and the lot's size. */
export function validReservation(quantity: number | '', lotQuantity: number): boolean {
  return quantity === '' || (Number.isInteger(quantity) && quantity >= 1 && quantity <= lotQuantity);
}

export interface CorrectionValues {
  expiryDate: string;
  supportProduction: boolean;
  supportInvestment: boolean;
  supportSchemeCode: string | null;
  labels: string[];
  /** The cost per MWh as typed (a decimal string); empty keeps the current one. */
  costBasis: string;
  costCurrency: string;
  reason: string;
}

/** The form's starting values: the lot as it is. */
export function correctionValues(lot: Lot): CorrectionValues {
  return {
    expiryDate: lot.expiryDate,
    supportProduction: Boolean(lot.supportProduction),
    supportInvestment: Boolean(lot.supportInvestment),
    supportSchemeCode: lot.supportSchemeCode ?? null,
    labels: [...lot.labels],
    costBasis: lot.costBasisPerMwh != null ? String(lot.costBasisPerMwh) : '',
    costCurrency: lot.costCurrency ?? 'EUR',
    reason: '',
  };
}

const sameSet = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && [...a].sort().join('\u0000') === [...b].sort().join('\u0000');

/**
 * The correction to send: only the fields that differ from the lot, plus the reason; null when nothing changed
 * (the server would record nothing). The cost travels as the typed decimal string.
 */
export function correctionBody(lot: Lot, values: CorrectionValues): Body<'LotCorrection'> | null {
  const body: Body<'LotCorrection'> = { reason: values.reason.trim() };
  let changed = false;
  if (values.expiryDate && values.expiryDate !== lot.expiryDate) {
    body.expiryDate = values.expiryDate;
    changed = true;
  }
  if (values.supportProduction !== Boolean(lot.supportProduction)) {
    body.supportProduction = values.supportProduction;
    changed = true;
  }
  if (values.supportInvestment !== Boolean(lot.supportInvestment)) {
    body.supportInvestment = values.supportInvestment;
    changed = true;
  }
  if (values.supportSchemeCode && values.supportSchemeCode !== (lot.supportSchemeCode ?? null)) {
    body.supportSchemeCode = values.supportSchemeCode;
    changed = true;
  }
  if (!sameSet(values.labels, lot.labels)) {
    body.labels = values.labels;
    changed = true;
  }
  const cost = values.costBasis.trim();
  const currency = values.costCurrency.trim().toUpperCase();
  const currentCost = lot.costBasisPerMwh != null ? String(lot.costBasisPerMwh) : '';
  if (cost && (cost !== currentCost || currency !== (lot.costCurrency ?? ''))) {
    body.costBasisPerMwh = decimal(cost);
    body.costCurrency = currency || null;
    changed = true;
  }
  return changed ? body : null;
}

/** The expiry ladder's months from [from] on, with the MWh expiring in each (zero where nothing does). */
export function ladderRows(
  ladder: Record<string, number> | undefined,
  from: string,
  months: number,
): { month: string; label: string; mwh: number }[] {
  const start = dayjs(from).startOf('month');
  const byMonth = new Map(Object.entries(ladder ?? {}).map(([day, mwh]) => [day.slice(0, 7), mwh]));
  return Array.from({ length: months }, (_, index) => {
    const month = start.add(index, 'month');
    const key = month.format('YYYY-MM');
    return { month: key, label: month.format('MMM YY'), mwh: byMonth.get(key) ?? 0 };
  });
}

/** The result of the journal check (`{ ok, brokenLots }`), or null while there is none. */
export function journalResult(data: Record<string, unknown> | undefined): { ok: boolean; brokenLots: string[] } | null {
  if (!data) return null;
  const broken = Array.isArray(data.brokenLots)
    ? data.brokenLots.filter((id): id is string => typeof id === 'string')
    : [];
  return { ok: data.ok === true && broken.length === 0, brokenLots: broken };
}
