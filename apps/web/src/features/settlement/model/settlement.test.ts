import { describe, expect, it } from 'vitest';
import {
  asItemView,
  canReevaluate,
  invoiceActions,
  isPayable,
  itemStatuses,
  needsPrice,
  purchaseCandidates,
  type Item,
} from './settlement';

const draft = { status: 'DRAFT', kind: 'SALES', credited: false, originalInvoiceId: null, documentId: null } as const;

describe('settlement', () => {
  it('maps item views to statuses and defaults to what needs attention', () => {
    expect(itemStatuses('attention')).toEqual(['NEEDS_ATTENTION']);
    expect(itemStatuses('open')).toEqual(['OPEN', 'NEEDS_ATTENTION']);
    expect(itemStatuses('all')).toBeUndefined();
    expect(asItemView('invoiced')).toBe('invoiced');
    expect(asItemView('nonsense')).toBe('attention');
    expect(asItemView(null)).toBe('attention');
  });

  it('offers a price fixing only for items waiting for a price', () => {
    expect(needsPrice({ status: 'NEEDS_ATTENTION', unitPrice: null })).toBe(true);
    expect(needsPrice({ status: 'NEEDS_ATTENTION', unitPrice: 1.95 })).toBe(false);
    expect(needsPrice({ status: 'OPEN', unitPrice: null })).toBe(false);
    expect(canReevaluate({ status: 'NEEDS_ATTENTION' })).toBe(true);
    expect(canReevaluate({ status: 'INVOICED' })).toBe(false);
  });

  it('offers the actions of each invoice state', () => {
    expect(invoiceActions(draft)).toEqual({
      submit: true,
      discard: true,
      creditNote: false,
      awaitingApproval: false,
      pdf: false,
    });
    expect(invoiceActions({ ...draft, status: 'PENDING_APPROVAL' }).awaitingApproval).toBe(true);
    const issued = { ...draft, status: 'ISSUED', documentId: 'doc-1' } as const;
    expect(invoiceActions(issued)).toMatchObject({ submit: false, discard: false, creditNote: true, pdf: true });
    expect(invoiceActions({ ...issued, credited: true }).creditNote).toBe(false);
    expect(invoiceActions({ ...issued, kind: 'CREDIT_NOTE', originalInvoiceId: 'inv-1' }).creditNote).toBe(false);
  });

  it('tells payables from receivables', () => {
    expect(isPayable('PURCHASE')).toBe(true);
    expect(isPayable('SELF_BILLED')).toBe(true);
    expect(isPayable('SALES')).toBe(false);
    expect(isPayable('CREDIT_NOTE')).toBe(false);
  });

  it('finds the open purchases of one supplier in one currency', () => {
    const item = (id: string, patch: Partial<Item>) =>
      ({ id, direction: 'PAYABLE', status: 'OPEN', counterpartyId: 'cp-1', currency: 'EUR', ...patch }) as Item;
    const items = [
      item('a', {}),
      item('b', { direction: 'RECEIVABLE' }),
      item('c', { status: 'INVOICED' }),
      item('d', { counterpartyId: 'cp-2' }),
      item('e', { currency: 'SEK' }),
    ];
    expect(purchaseCandidates(items, 'cp-1', 'eur').map((i) => i.id)).toEqual(['a']);
    expect(purchaseCandidates(items, null, 'EUR')).toEqual([]);
  });
});
