import dayjs from 'dayjs';
import type { Schema } from '@/shared/api/types';
import type { Invoice } from './settlement';

export type Payment = Schema<'PaymentView'>;
export type PaymentDirection = Payment['direction'];
export type Netting = Schema<'NettingStatementView'>;
export type NettingStatus = Netting['status'];
export type VatRule = Schema<'VatRuleView'>;

export const NETTING_STATUS_COLORS: Record<NettingStatus, string> = {
  DRAFT: 'gray',
  AGREED: 'blue',
  SETTLED: 'teal',
  CANCELLED: 'red',
};

export const VAT_RULE_STATUS_COLORS: Record<VatRule['status'], string> = {
  DRAFT: 'yellow',
  APPROVED: 'teal',
  RETIRED: 'gray',
};

/** Whether an amount the server computed is above, below or at zero; a sign test, not arithmetic. */
export function sign(value: number | string | null | undefined): -1 | 0 | 1 {
  const parsed = Number(value ?? 0);
  return parsed > 0 ? 1 : parsed < 0 ? -1 : 0;
}

/** Positive: the counterparty pays us; negative: we pay them; zero: the invoices offset exactly. */
export function nettingDirection(statement: Pick<Netting, 'netAmount'>): 'theyPay' | 'wePay' | 'even' {
  const direction = sign(statement.netAmount);
  return direction > 0 ? 'theyPay' : direction < 0 ? 'wePay' : 'even';
}

export interface NettingActions {
  agree: boolean;
  /** The preparer may not record the counterparty's agreement (four eyes). */
  ownStatement: boolean;
  settle: boolean;
  /** The net amount must be paid by a recorded payment before settling. */
  needsPayment: boolean;
  cancel: boolean;
  pdf: boolean;
}

export function nettingActions(
  statement: Pick<Netting, 'status' | 'createdBy' | 'netAmount' | 'documentId'>,
  userId: string,
): NettingActions {
  const own = statement.createdBy === userId;
  return {
    agree: statement.status === 'DRAFT' && !own,
    ownStatement: statement.status === 'DRAFT' && own,
    settle: statement.status === 'AGREED',
    needsPayment: statement.status === 'AGREED' && sign(statement.netAmount) !== 0,
    cancel: statement.status === 'DRAFT' || statement.status === 'AGREED',
    pdf: Boolean(statement.documentId),
  };
}

/** Unapplied payments that can pay a statement's net amount: same counterparty and currency, the right way. */
export function settlementPayments(
  payments: Payment[],
  statement: Pick<Netting, 'counterpartyId' | 'currency' | 'netAmount'>,
): Payment[] {
  const direction = sign(statement.netAmount) > 0 ? 'INCOMING' : 'OUTGOING';
  return payments.filter(
    (payment) =>
      payment.direction === direction &&
      payment.currency === statement.currency &&
      (payment.counterpartyId === null ||
        payment.counterpartyId === undefined ||
        payment.counterpartyId === statement.counterpartyId) &&
      sign(payment.unappliedAmount) > 0,
  );
}

/** Issued invoices a payment's unapplied cash can go to: same direction, currency and (if known) counterparty. */
export function allocatableInvoices(invoices: Invoice[], payment: Payment): Invoice[] {
  const direction = payment.direction === 'INCOMING' ? 'RECEIVABLE' : 'PAYABLE';
  return invoices.filter(
    (invoice) =>
      invoice.status === 'ISSUED' &&
      invoice.direction === direction &&
      invoice.currency === payment.currency &&
      (!payment.counterpartyId || invoice.counterpartyId === payment.counterpartyId) &&
      sign(invoice.outstanding) > 0,
  );
}

export const REPORTS = ['ageing', 'cash-forecast', 'vat', 'ec-sales-list'] as const;
export type Report = (typeof REPORTS)[number];

export function asReport(value: string | null): Report {
  return REPORTS.find((report) => report === value) ?? 'ageing';
}

/** Default parameters: ageing today, the cash forecast for the next 90 days, VAT and EC sales for this month. */
export function reportDefaults(today: string): { asOf: string; from: string; to: string; period: string } {
  const day = dayjs(today);
  return {
    asOf: day.format('YYYY-MM-DD'),
    from: day.format('YYYY-MM-DD'),
    to: day.add(90, 'day').format('YYYY-MM-DD'),
    period: day.format('YYYY-MM'),
  };
}

/** Bank statement lines for the CSV import: header row and blank lines are the server's to skip; this counts data. */
export function csvLineCount(text: string): number {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !/^bank_reference\b/i.test(line)).length;
}
