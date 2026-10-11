import dayjs from 'dayjs';
import type { Schema } from '@/shared/api/types';

export type Break = Schema<'ReconciliationBreakView'>;
export type BreakStatus = Break['status'];
export type BreakType = Break['type'];
export type Statement = Schema<'StatementView'>;
type LotStatus = Schema<'LotView'>['status'];

export const BREAK_TYPES: BreakType[] = [
  'MISSING_INTERNALLY',
  'MISSING_IN_REGISTRY',
  'ATTRIBUTE_MISMATCH',
  'BALANCE_MISMATCH',
];
export const OPEN_STATUSES: BreakStatus[] = ['OPEN', 'INVESTIGATING', 'PENDING_ACCEPTANCE'];
export const CLOSED_STATUSES: BreakStatus[] = ['RESOLVED', 'ACCEPTED', 'AUTO_CLEARED'];

export const BREAK_STATUS_COLORS: Record<BreakStatus, string> = {
  OPEN: 'red',
  INVESTIGATING: 'orange',
  PENDING_ACCEPTANCE: 'yellow',
  RESOLVED: 'teal',
  ACCEPTED: 'grape',
  AUTO_CLEARED: 'gray',
};

export const STATEMENT_STATUS_COLORS: Record<Statement['status'], string> = {
  IMPORTED: 'gray',
  RECONCILED: 'teal',
  BREAKS: 'orange',
};

export interface BreakActions {
  /** Take the break into investigation (assigns it to the user). */
  take: boolean;
  /** The certificates left in a transfer the registry executed: confirm the instruction. */
  confirmInstruction: boolean;
  /** The certificates are gone from the registry while we hold them: withdraw our lot. */
  withdrawLot: boolean;
  /** The registry shows certificates we have no lot for: receive them. */
  recordReceipt: boolean;
  /** Our lot's attributes differ from the registry's: replace the lot, or correct its correctable fields. */
  replaceLot: boolean;
  correctLot: boolean;
  /** An allowance balance differs: book the difference. */
  correctHolding: boolean;
  /** Accept the difference as it is: a second person decides in the inbox. */
  accept: boolean;
  awaitingAcceptance: boolean;
}

/** The resolutions the server allows for a break of this type, given the status of its lot (mirrors `BreakResolutionService`). */
export function breakActions(
  brk: Pick<Break, 'type' | 'status' | 'lotId'>,
  lotStatus?: LotStatus | null,
): BreakActions {
  const open = brk.status === 'OPEN' || brk.status === 'INVESTIGATING';
  const held = lotStatus === 'HELD';
  return {
    take: brk.status === 'OPEN',
    confirmInstruction: open && brk.type === 'MISSING_IN_REGISTRY' && lotStatus === 'IN_TRANSFER',
    withdrawLot: open && brk.type === 'MISSING_IN_REGISTRY' && held,
    recordReceipt: open && brk.type === 'MISSING_INTERNALLY',
    replaceLot: open && brk.type === 'ATTRIBUTE_MISMATCH' && held,
    correctLot: open && Boolean(brk.lotId) && held,
    correctHolding: open && brk.type === 'BALANCE_MISMATCH',
    accept: open,
    awaitingAcceptance: brk.status === 'PENDING_ACCEPTANCE',
  };
}

/** What the break is about, in one line: certificates (issuance and range) or an allowance balance. */
export function breakSubject(brk: Break): string {
  if (brk.type === 'BALANCE_MISMATCH') {
    return `${brk.productCode ?? ''}: ${brk.registryQuantity ?? '?'} / ${brk.internalQuantity ?? '?'}`;
  }
  const range = brk.range ? ` ${brk.range.start}–${brk.range.end}` : '';
  return `${brk.issuanceRef ?? ''}${range}`.trim();
}

/** Days since the registry first showed the difference. */
export function ageInDays(brk: Pick<Break, 'firstSeenOn' | 'detectedAt'>, today: string): number | null {
  const first = brk.firstSeenOn ?? brk.detectedAt;
  return first ? Math.max(0, dayjs(today).startOf('day').diff(dayjs(first).startOf('day'), 'day')) : null;
}

export interface Mismatch {
  field: string;
  registry: string;
  internal: string;
}

/** Attribute mismatches recorded in the break's details as `{field: {registry, internal}}`. */
export function mismatches(details: Record<string, unknown>): Mismatch[] {
  return Object.entries(details)
    .filter(([, value]) => typeof value === 'object' && value !== null && 'registry' in value && 'internal' in value)
    .map(([field, value]) => {
      const pair = value as { registry: unknown; internal: unknown };
      return { field, registry: String(pair.registry), internal: String(pair.internal) };
    });
}

/** Other details (line number, lot status, reserved quantity) shown as plain facts. */
export function facts(details: Record<string, unknown>): [string, string][] {
  const mismatched = new Set(mismatches(details).map((m) => m.field));
  const text = (value: unknown): string =>
    typeof value === 'string'
      ? value
      : typeof value === 'object' && value !== null
        ? JSON.stringify(value)
        : `${value as number}`;
  return Object.entries(details)
    .filter(([key]) => !mismatched.has(key))
    .map(([key, value]) => [key, text(value)]);
}
