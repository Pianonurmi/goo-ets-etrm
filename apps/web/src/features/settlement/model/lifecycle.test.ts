import { describe, expect, it } from 'vitest';
import { invoiceSteps, nettingSteps } from './lifecycle';

const t = (key: string) => key.replace('settlement.lifecycle.', '');
const invoice = {
  createdBy: 'accountant',
  submittedBy: null,
  approvedBy: null,
  issuedAt: null,
  paymentStatus: undefined,
} as const;

const states = (steps: { label: string; state: string }[]) => steps.map((step) => `${step.label}:${step.state}`);

describe('invoiceSteps', () => {
  it('sits on the draft until submitted', () => {
    expect(states(invoiceSteps({ ...invoice, status: 'DRAFT' }, t))).toEqual([
      'drafted:current',
      'submitted:pending',
      'issued:pending',
      'paid:pending',
    ]);
  });

  it('waits for payment once issued, and names who approved it', () => {
    const steps = invoiceSteps(
      {
        ...invoice,
        status: 'ISSUED',
        submittedBy: 'accountant',
        approvedBy: 'controller',
        issuedAt: '2026-10-01T10:00:00Z',
      },
      t,
    );
    expect(states(steps)).toEqual(['drafted:done', 'submitted:done', 'issued:current', 'paid:pending']);
    expect(steps[2]).toMatchObject({ by: 'controller', at: '2026-10-01T10:00:00Z' });
  });

  it('sits on the payment step while partly paid and completes when paid', () => {
    expect(states(invoiceSteps({ ...invoice, status: 'ISSUED', paymentStatus: 'PARTIALLY_PAID' }, t)).at(-1)).toBe(
      'partlyPaid:current',
    );
    expect(states(invoiceSteps({ ...invoice, status: 'ISSUED', paymentStatus: 'PAID' }, t))).toEqual([
      'drafted:done',
      'submitted:done',
      'issued:done',
      'paid:done',
    ]);
  });

  it('ends a discarded draft in a failed step', () => {
    expect(states(invoiceSteps({ ...invoice, status: 'CANCELLED' }, t))).toEqual(['drafted:done', 'cancelled:failed']);
  });
});

describe('nettingSteps', () => {
  const statement = { createdBy: 'accountant', agreedBy: null, counterpartyRef: null };

  it('moves from prepared through agreed to settled', () => {
    expect(states(nettingSteps({ ...statement, status: 'DRAFT' }, t))).toEqual([
      'prepared:current',
      'agreed:pending',
      'settled:pending',
    ]);
    const agreed = nettingSteps({ ...statement, status: 'AGREED', agreedBy: 'controller', counterpartyRef: 'NV-1' }, t);
    expect(states(agreed)).toEqual(['prepared:done', 'agreed:current', 'settled:pending']);
    expect(agreed[1]).toMatchObject({ by: 'controller', detail: 'NV-1' });
    expect(states(nettingSteps({ ...statement, status: 'SETTLED' }, t))).toEqual([
      'prepared:done',
      'agreed:done',
      'settled:done',
    ]);
  });

  it('ends a cancelled statement in a failed step', () => {
    expect(states(nettingSteps({ ...statement, status: 'CANCELLED' }, t))).toEqual([
      'prepared:done',
      'cancelled:failed',
    ]);
  });
});
