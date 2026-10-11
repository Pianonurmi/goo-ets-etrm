import { describe, expect, it } from 'vitest';
import type { Invoice } from './settlement';
import {
  allocatableInvoices,
  asReport,
  csvLineCount,
  nettingActions,
  nettingDirection,
  reportDefaults,
  settlementPayments,
  sign,
  type Payment,
} from './payments';

const payment = (patch: Partial<Payment>): Payment =>
  ({
    id: 'p',
    direction: 'INCOMING',
    currency: 'EUR',
    counterpartyId: 'cp-1',
    unappliedAmount: 100,
    ...patch,
  }) as Payment;

describe('payments and netting', () => {
  it('reads the sign of server amounts', () => {
    expect(sign('245.00')).toBe(1);
    expect(sign(-3)).toBe(-1);
    expect(sign('0.00')).toBe(0);
    expect(sign(null)).toBe(0);
    expect(nettingDirection({ netAmount: 245 })).toBe('theyPay');
    expect(nettingDirection({ netAmount: -10 })).toBe('wePay');
    expect(nettingDirection({ netAmount: 0 })).toBe('even');
  });

  it('keeps the preparer from recording the agreement and asks for a payment only when something is due', () => {
    const draft = { status: 'DRAFT', createdBy: 'accountant', netAmount: 245, documentId: 'd1' } as const;
    expect(nettingActions(draft, 'accountant')).toMatchObject({ agree: false, ownStatement: true, cancel: true });
    expect(nettingActions(draft, 'controller')).toMatchObject({ agree: true, ownStatement: false, pdf: true });
    const agreed = { ...draft, status: 'AGREED' } as const;
    expect(nettingActions(agreed, 'x')).toMatchObject({ settle: true, needsPayment: true, agree: false });
    expect(nettingActions({ ...agreed, netAmount: 0 }, 'x').needsPayment).toBe(false);
    expect(nettingActions({ ...draft, status: 'SETTLED' }, 'x')).toMatchObject({ settle: false, cancel: false });
  });

  it('offers payments that can pay the net amount', () => {
    const statement = { counterpartyId: 'cp-1', currency: 'EUR', netAmount: 245 };
    const payments = [
      payment({ id: 'a' }),
      payment({ id: 'b', direction: 'OUTGOING' }),
      payment({ id: 'c', currency: 'SEK' }),
      payment({ id: 'd', counterpartyId: 'cp-2' }),
      payment({ id: 'e', counterpartyId: null }),
      payment({ id: 'f', unappliedAmount: 0 }),
    ];
    expect(settlementPayments(payments, statement).map((p) => p.id)).toEqual(['a', 'e']);
    expect(settlementPayments(payments, { ...statement, netAmount: -5 }).map((p) => p.id)).toEqual(['b']);
  });

  it('offers issued invoices with something outstanding for unapplied cash', () => {
    const invoice = (id: string, patch: Partial<Invoice>) =>
      ({
        id,
        status: 'ISSUED',
        direction: 'RECEIVABLE',
        currency: 'EUR',
        counterpartyId: 'cp-1',
        outstanding: 50,
        ...patch,
      }) as Invoice;
    const invoices = [
      invoice('a', {}),
      invoice('b', { status: 'DRAFT' }),
      invoice('c', { direction: 'PAYABLE' }),
      invoice('d', { outstanding: 0 }),
      invoice('e', { counterpartyId: 'cp-2' }),
    ];
    expect(allocatableInvoices(invoices, payment({})).map((i) => i.id)).toEqual(['a']);
    expect(allocatableInvoices(invoices, payment({ counterpartyId: null })).map((i) => i.id)).toEqual(['a', 'e']);
  });

  it('defaults report parameters and counts statement lines', () => {
    expect(asReport('vat')).toBe('vat');
    expect(asReport('x')).toBe('ageing');
    expect(reportDefaults('2026-10-11')).toEqual({
      asOf: '2026-10-11',
      from: '2026-10-11',
      to: '2027-01-09',
      period: '2026-10',
    });
    expect(
      csvLineCount('bank_reference,value_date,amount,currency\nB1,2026-10-01,10,EUR\n\nB2,2026-10-02,-5,EUR\n'),
    ).toBe(2);
  });
});
