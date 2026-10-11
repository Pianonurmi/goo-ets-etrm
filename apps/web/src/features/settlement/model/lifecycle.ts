import type { LifecycleStep, StepState } from '@/shared/components/LifecycleTimeline';
import type { Netting } from './payments';
import { paymentStatus, type Invoice } from './settlement';

type Translate = (key: string) => string;

/** Steps up to `reached` are done, the one at `reached` is where the record sits now, the rest are to come. */
function stateAt(index: number, reached: number, finished: boolean): StepState {
  if (index < reached || (index === reached && finished)) return 'done';
  return index === reached ? 'current' : 'pending';
}

/**
 * An invoice's lifecycle: drafted → submitted for approval → issued by a second person → paid. A discarded draft
 * ends in a failed step; a partly paid invoice sits on the payment step.
 */
export function invoiceSteps(
  invoice: Pick<Invoice, 'status' | 'paymentStatus' | 'createdBy' | 'submittedBy' | 'approvedBy' | 'issuedAt'>,
  t: Translate,
): LifecycleStep[] {
  const payment = paymentStatus(invoice);
  if (invoice.status === 'CANCELLED') {
    return [
      { label: t('settlement.lifecycle.drafted'), state: 'done', by: invoice.createdBy },
      { label: t('settlement.lifecycle.cancelled'), state: 'failed' },
    ];
  }
  const reached = { DRAFT: 0, PENDING_APPROVAL: 1, ISSUED: payment === 'UNPAID' ? 2 : 3 }[invoice.status];
  const paid = invoice.status === 'ISSUED' && payment === 'PAID';
  const steps: Omit<LifecycleStep, 'state'>[] = [
    { label: t('settlement.lifecycle.drafted'), by: invoice.createdBy },
    { label: t('settlement.lifecycle.submitted'), by: invoice.submittedBy },
    { label: t('settlement.lifecycle.issued'), by: invoice.approvedBy, at: invoice.issuedAt },
    {
      label: t(payment === 'PARTIALLY_PAID' ? 'settlement.lifecycle.partlyPaid' : 'settlement.lifecycle.paid'),
    },
  ];
  return steps.map((step, index) => ({ ...step, state: stateAt(index, reached, paid) }));
}

/** A netting statement's lifecycle: prepared → agreed with the counterparty (four eyes) → settled. */
export function nettingSteps(
  statement: Pick<Netting, 'status' | 'createdBy' | 'agreedBy' | 'counterpartyRef'>,
  t: Translate,
): LifecycleStep[] {
  if (statement.status === 'CANCELLED') {
    return [
      { label: t('settlement.lifecycle.prepared'), state: 'done', by: statement.createdBy },
      { label: t('settlement.lifecycle.cancelled'), state: 'failed' },
    ];
  }
  const reached = { DRAFT: 0, AGREED: 1, SETTLED: 2 }[statement.status];
  const steps: Omit<LifecycleStep, 'state'>[] = [
    { label: t('settlement.lifecycle.prepared'), by: statement.createdBy },
    { label: t('settlement.lifecycle.agreed'), by: statement.agreedBy, detail: statement.counterpartyRef },
    { label: t('settlement.lifecycle.settled') },
  ];
  return steps.map((step, index) => ({ ...step, state: stateAt(index, reached, statement.status === 'SETTLED') }));
}
