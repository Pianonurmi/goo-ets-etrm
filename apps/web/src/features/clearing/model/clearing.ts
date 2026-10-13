import dayjs from 'dayjs';
import type { Schema } from '@/shared/api/types';
import type { LifecycleStep } from '@/shared/components/LifecycleTimeline';

export type Account = Schema<'ClearingAccountView'>;
export type Run = Schema<'MarginRunView'>;
export type RunLine = Schema<'MarginLineView'>;
export type Statement = Schema<'ClearingStatementView'>;
export type StatementLine = Schema<'StatementLineView'>;
export type StatementImport = Schema<'StatementImportView'>;
export type ClearingBreak = Schema<'ClearingBreakView'>;
export type Difference = Schema<'MarginDifference'>;
export type MarginCall = Schema<'MarginCallView'>;
export type Posted = Schema<'MarginPostedView'>;

export type BreakStatus = ClearingBreak['status'];
export type CallStatus = MarginCall['status'];
export type DifferenceKind = Difference['kind'];

export const TABS = ['accounts', 'margin', 'statements', 'breaks', 'margin-calls'] as const;
export type ClearingTab = (typeof TABS)[number];

export const BREAK_STATUSES: readonly BreakStatus[] = ['OPEN', 'MATCHED', 'ACCEPTED'];
export const CALL_STATUSES: readonly CallStatus[] = ['OPEN', 'PAID', 'DISPUTED', 'CANCELLED'];
export const DIFFERENCE_KINDS: readonly DifferenceKind[] = [
  'NO_RUN',
  'POSITION',
  'VARIATION_MARGIN',
  'SETTLEMENT_PRICE',
];

/** The tab named by the URL, the first one when it is missing or unknown. */
export function clearingTab(value: string | null): ClearingTab {
  return TABS.find((tab) => tab === value) ?? 'accounts';
}

/** The status filter named by the URL: [fallback] when absent, `null` (all) for `ALL`, `null` when unknown. */
export function statusFilter<S extends string>(
  value: string | null,
  allowed: readonly S[],
  fallback: S | null,
): S | null {
  if (value === null) return fallback;
  return allowed.find((status) => status === value) ?? null;
}

/** The account chosen in the URL, else the first one the entity has. */
export function chooseAccount(accounts: readonly Account[], id: string | null): Account | null {
  return accounts.find((account) => account.id === id) ?? accounts[0] ?? null;
}

export function postedFor(posted: readonly Posted[], accountId: string | undefined): Posted | undefined {
  return accountId ? posted.find((row) => row.accountId === accountId) : undefined;
}

/** How many rows have each status; statuses without rows are left out. */
export function countByStatus<S extends string>(rows: readonly { status: S }[]): Partial<Record<S, number>> {
  const counts: Partial<Record<S, number>> = {};
  rows.forEach((row) => {
    counts[row.status] = (counts[row.status] ?? 0) + 1;
  });
  return counts;
}

export interface DateRange {
  from: string;
  to: string;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function validDate(value: string | null): value is string {
  return value !== null && ISO_DATE.test(value) && dayjs(value).isValid();
}

/** The last [days] days up to and including [today]. */
export function defaultRange(today: string, days = 31): DateRange {
  return { from: dayjs(today).subtract(days, 'day').format('YYYY-MM-DD'), to: today };
}

/** The latest weekday on or before [day]: margin is computed for weekdays (exchange holidays have no price). */
export function lastBusinessDay(day: string): string {
  let current = dayjs(day);
  while (current.day() === 0 || current.day() === 6) current = current.subtract(1, 'day');
  return current.format('YYYY-MM-DD');
}

/** The range named by the URL, the default one for a missing or malformed date. */
export function readRange(from: string | null, to: string | null, today: string): DateRange {
  const fallback = defaultRange(today);
  return { from: validDate(from) ? from : fallback.from, to: validDate(to) ? to : fallback.to };
}

/** The server refuses a period that ends before it starts; the UI does not ask. */
export function rangeIsValid(range: DateRange): boolean {
  return !dayjs(range.to).isBefore(dayjs(range.from));
}

/** Newest business date first; the newest version first within a day. */
export function newestFirst<T extends { businessDate: string; version?: number }>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => b.businessDate.localeCompare(a.businessDate) || (b.version ?? 0) - (a.version ?? 0));
}

/** Whether a decimal from the API is zero; it may arrive as a number or as a string, and is only compared. */
export function isZero(value: number | string): boolean {
  return Number(value) === 0;
}

/** Whether a decimal from the API is above zero (for a tone, never for arithmetic). */
export function isPositive(value: number | string): boolean {
  return Number(value) > 0;
}

/** Lines of a run on which something happened that day: a trade, or a position carried over. */
export function tradedContracts(run: Pick<Run, 'lines'>): number {
  return run.lines.filter((line) => !isZero(line.tradedQuantity)).length;
}

/** The kinds of difference a break holds, in the order the screen explains them. */
export function differenceKinds(brk: Pick<ClearingBreak, 'differences'>): DifferenceKind[] {
  return DIFFERENCE_KINDS.filter((kind) => brk.differences.some((difference) => difference.kind === kind));
}

/** How a difference's figures are formatted: a position, an amount of money or a settlement price. */
export type FigureKind = 'quantity' | 'money' | 'price' | 'none';

export function figureKind(kind: DifferenceKind): FigureKind {
  switch (kind) {
    case 'POSITION':
      return 'quantity';
    case 'VARIATION_MARGIN':
      return 'money';
    case 'SETTLEMENT_PRICE':
      return 'price';
    default:
      return 'none';
  }
}

export interface DifferenceRow {
  key: string;
  kind: DifferenceKind;
  contractCode: string | null;
  ours: number | null;
  theirs: number | null;
}

/** The differences of a break by contract, then by kind, a missing run first. */
export function differenceRows(brk: Pick<ClearingBreak, 'differences'>): DifferenceRow[] {
  const order = (kind: DifferenceKind) => DIFFERENCE_KINDS.indexOf(kind);
  return brk.differences
    .map((difference) => ({
      kind: difference.kind,
      contractCode: difference.contractCode ?? null,
      ours: difference.ours ?? null,
      theirs: difference.theirs ?? null,
    }))
    .sort((a, b) => (a.contractCode ?? '').localeCompare(b.contractCode ?? '') || order(a.kind) - order(b.kind))
    .map((row, index) => ({ ...row, key: `${row.kind}:${row.contractCode ?? ''}:${index}` }));
}

/** The contracts a break touches, once each and sorted. */
export function affectedContracts(brk: Pick<ClearingBreak, 'differences'>): string[] {
  const codes = brk.differences.flatMap((difference) => (difference.contractCode ? [difference.contractCode] : []));
  return [...new Set(codes)].sort();
}

export type BreakStepKey = 'opened' | 'matched' | 'accepted' | 'closed';

/**
 * The lifecycle of a break: opened by an imported statement or a new run, then closed as matched (a corrected trade,
 * price or statement made the two agree) or accepted by a second person. The last step reached is the current one.
 */
export function breakSteps(
  brk: Pick<ClearingBreak, 'status' | 'openedAt' | 'openedBy' | 'closedAt' | 'closedBy' | 'resolution'>,
  label: (key: BreakStepKey) => string,
): LifecycleStep[] {
  const opened: LifecycleStep = { label: label('opened'), state: 'done', at: brk.openedAt, by: brk.openedBy };
  if (brk.status === 'OPEN') {
    return [
      { ...opened, state: 'current' },
      { label: label('closed'), state: 'pending' },
    ];
  }
  return [
    opened,
    {
      label: label(brk.status === 'MATCHED' ? 'matched' : 'accepted'),
      state: 'current',
      at: brk.closedAt,
      by: brk.closedBy,
      detail: brk.resolution,
    },
  ];
}

export type CallStepKey = 'requested' | 'paid' | 'disputed' | 'cancelled' | 'decision';

/** The lifecycle of a margin call: requested by a statement, then paid or disputed in the inbox, or cancelled. */
export function callSteps(
  call: Pick<MarginCall, 'status' | 'requestedAt' | 'requestedBy' | 'decidedAt' | 'decidedBy' | 'reason'>,
  label: (key: CallStepKey) => string,
): LifecycleStep[] {
  const requested: LifecycleStep = {
    label: label('requested'),
    state: 'done',
    at: call.requestedAt,
    by: call.requestedBy,
  };
  if (call.status === 'OPEN') {
    return [
      { ...requested, state: 'current' },
      { label: label('decision'), state: 'pending' },
    ];
  }
  const key: CallStepKey = call.status === 'PAID' ? 'paid' : call.status === 'DISPUTED' ? 'disputed' : 'cancelled';
  return [
    requested,
    {
      label: label(key),
      state: call.status === 'DISPUTED' ? 'failed' : 'current',
      at: call.decidedAt,
      by: call.decidedBy,
      detail: call.reason,
    },
  ];
}

/** What an import did, in the order the screen shows it: new versions by date, unchanged files, open breaks. */
export function importSummary(result: StatementImport) {
  const dates = result.imported.map((statement) => statement.businessDate).sort();
  return {
    imported: result.imported.length,
    unchanged: result.unchanged,
    breaks: result.breaks.filter((brk) => brk.status === 'OPEN').length,
    firstDate: dates[0] ?? null,
    lastDate: dates[dates.length - 1] ?? null,
  };
}
