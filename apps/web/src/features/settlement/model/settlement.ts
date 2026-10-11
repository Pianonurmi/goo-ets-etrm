import type { Schema } from '@/shared/api/types';

export type Item = Schema<'SettlementItemView'>;
export type ItemStatus = Item['status'];
export type Invoice = Schema<'InvoiceView'>;
export type InvoiceStatus = Invoice['status'];
export type InvoiceKind = Invoice['kind'];
export type PaymentStatus = NonNullable<Invoice['paymentStatus']>;

/** The item views offered on the items tab, each a set of statuses for the API. */
export const ITEM_VIEWS = ['attention', 'open', 'invoiced', 'all'] as const;
export type ItemView = (typeof ITEM_VIEWS)[number];

export function itemStatuses(view: ItemView): ItemStatus[] | undefined {
  switch (view) {
    case 'attention':
      return ['NEEDS_ATTENTION'];
    case 'open':
      return ['OPEN', 'NEEDS_ATTENTION'];
    case 'invoiced':
      return ['INVOICED'];
    case 'all':
      return undefined;
  }
}

export function asItemView(value: string | null): ItemView {
  return ITEM_VIEWS.find((view) => view === value) ?? 'attention';
}

export const INVOICE_STATUSES: InvoiceStatus[] = ['DRAFT', 'PENDING_APPROVAL', 'ISSUED', 'CANCELLED'];
export const INVOICE_KINDS: InvoiceKind[] = ['SALES', 'SELF_BILLED', 'PURCHASE', 'CREDIT_NOTE', 'PURCHASE_CREDIT_NOTE'];

export const ITEM_STATUS_COLORS: Record<ItemStatus, string> = {
  OPEN: 'blue',
  NEEDS_ATTENTION: 'orange',
  INVOICED: 'teal',
  CANCELLED: 'gray',
};

export const INVOICE_STATUS_COLORS: Record<InvoiceStatus, string> = {
  DRAFT: 'gray',
  PENDING_APPROVAL: 'yellow',
  ISSUED: 'teal',
  CANCELLED: 'red',
};

export const PAYMENT_STATUS_COLORS: Record<PaymentStatus, string> = {
  UNPAID: 'orange',
  PARTIALLY_PAID: 'yellow',
  PAID: 'teal',
};

/** Unpaid until the server says otherwise. */
export function paymentStatus(invoice: Pick<Invoice, 'paymentStatus'>): PaymentStatus {
  return invoice.paymentStatus ?? 'UNPAID';
}

/** An item whose price is not fixed yet can be fixed by hand; one with a price can only be evaluated again. */
export function needsPrice(item: Pick<Item, 'status' | 'unitPrice'>): boolean {
  return item.status === 'NEEDS_ATTENTION' && (item.unitPrice === null || item.unitPrice === undefined);
}

export function canReevaluate(item: Pick<Item, 'status'>): boolean {
  return item.status === 'NEEDS_ATTENTION';
}

/** The VAT rule that determined an item's treatment, with its version (`FI_STANDARD v2`); null until determined. */
export function vatRuleLabel(item: Pick<Item, 'vatRuleCode' | 'vatRuleVersion'>): string | null {
  if (!item.vatRuleCode) return null;
  return item.vatRuleVersion == null ? item.vatRuleCode : `${item.vatRuleCode} v${item.vatRuleVersion}`;
}

/** What may be done to an invoice in its current state; the API checks roles and four eyes again. */
export interface InvoiceActions {
  submit: boolean;
  discard: boolean;
  creditNote: boolean;
  awaitingApproval: boolean;
  pdf: boolean;
}

const CREDITABLE: InvoiceKind[] = ['SALES', 'SELF_BILLED', 'PURCHASE'];

export function invoiceActions(
  invoice: Pick<Invoice, 'status' | 'kind' | 'credited' | 'originalInvoiceId' | 'documentId'>,
): InvoiceActions {
  return {
    submit: invoice.status === 'DRAFT',
    discard: invoice.status === 'DRAFT',
    creditNote:
      invoice.status === 'ISSUED' &&
      !invoice.credited &&
      !invoice.originalInvoiceId &&
      CREDITABLE.includes(invoice.kind),
    awaitingApproval: invoice.status === 'PENDING_APPROVAL',
    pdf: Boolean(invoice.documentId),
  };
}

/** Payables are what we owe: purchase invoices and their credit notes, and self-billed invoices. */
export function isPayable(kind: InvoiceKind): boolean {
  return kind === 'PURCHASE' || kind === 'PURCHASE_CREDIT_NOTE' || kind === 'SELF_BILLED';
}

/** Open payable items of one supplier in one currency: what a supplier's invoice can be matched against. */
export function purchaseCandidates(items: Item[], counterpartyId: string | null, currency: string): Item[] {
  return items.filter(
    (item) =>
      item.direction === 'PAYABLE' &&
      item.status === 'OPEN' &&
      item.counterpartyId === counterpartyId &&
      item.currency === currency.toUpperCase(),
  );
}
