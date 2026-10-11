import dayjs from 'dayjs';
import { EXAMPLE, incomplete, type Criterion } from '@/features/criteria/model/tree';
import type { Body, Schema } from '@/shared/api/types';

export type Obligation = Schema<'ObligationView'>;
export type ObligationKind = Obligation['kind'];
export type OpenObligationBody = Body<'OpenObligation'>;
export type Purpose = Schema<'CancellationDetails'>['purpose'];
export type Constraint = Obligation['constraints'][number];

export const OBLIGATION_KINDS: ObligationKind[] = ['DISCLOSURE', 'OWN_CONSUMPTION', 'SALE', 'INTERCOMPANY'];
export const PURPOSES: Purpose[] = [
  'DISCLOSURE',
  'OWN_CONSUMPTION',
  'VOLUNTARY',
  'COMPLIANCE',
  'ON_BEHALF_OF_THIRD_PARTY',
];

/** The service refuses disclosure and own-consumption obligations without cancellation details. */
export function needsCancellation(kind: ObligationKind): boolean {
  return kind === 'DISCLOSURE' || kind === 'OWN_CONSUMPTION';
}

/** The service refuses sale and intercompany obligations without a target account. */
export function needsTarget(kind: ObligationKind): boolean {
  return kind === 'SALE' || kind === 'INTERCOMPANY';
}

export interface ObligationForm {
  kind: ObligationKind;
  quantityMwh: string;
  windowStart: string;
  windowEnd: string;
  deliveryDate: string;
  priorityClass: string;
  criterion: Criterion;
  targetRegistryCode: string;
  targetAccountRef: string;
  purpose: Purpose;
  beneficiaryName: string;
  consumptionCountry: string;
  consumptionStart: string;
  consumptionEnd: string;
}

/** An own-consumption need for the previous month, delivered within a month from [today]. */
export function emptyObligation(today: string): ObligationForm {
  const day = dayjs(today);
  const previous = day.subtract(1, 'month').startOf('month');
  return {
    kind: 'OWN_CONSUMPTION',
    quantityMwh: '',
    windowStart: day.format('YYYY-MM-DD'),
    windowEnd: day.add(45, 'day').format('YYYY-MM-DD'),
    deliveryDate: day.add(30, 'day').format('YYYY-MM-DD'),
    priorityClass: '',
    criterion: EXAMPLE,
    targetRegistryCode: '',
    targetAccountRef: '',
    purpose: 'OWN_CONSUMPTION',
    beneficiaryName: '',
    consumptionCountry: '',
    consumptionStart: previous.format('YYYY-MM-DD'),
    consumptionEnd: previous.add(1, 'month').format('YYYY-MM-DD'),
  };
}

const WHOLE = /^\d+$/;

/** Checks the browser can make before sending, as i18n keys by field; the server validates everything again. */
export function validateObligation(form: ObligationForm): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!WHOLE.test(form.quantityMwh.trim()) || Number(form.quantityMwh) <= 0) {
    errors.quantityMwh = 'allocation.errors.quantity';
  }
  if (!form.windowStart) errors.windowStart = 'allocation.errors.date';
  if (!form.windowEnd) errors.windowEnd = 'allocation.errors.date';
  else if (form.windowStart && form.windowEnd <= form.windowStart) errors.windowEnd = 'allocation.errors.window';
  if (!form.deliveryDate) errors.deliveryDate = 'allocation.errors.date';
  if (form.priorityClass.trim() && !WHOLE.test(form.priorityClass.trim())) {
    errors.priorityClass = 'allocation.errors.quantity';
  }
  if (incomplete(form.criterion).length > 0) errors.criterion = 'allocation.errors.criterion';
  if (needsTarget(form.kind) && !form.targetAccountRef.trim()) errors.targetAccountRef = 'allocation.errors.target';
  if (needsCancellation(form.kind)) {
    if (!/^[A-Za-z]{2}$/.test(form.consumptionCountry.trim())) errors.consumptionCountry = 'allocation.errors.country';
    if (!form.consumptionStart) errors.consumptionStart = 'allocation.errors.date';
    if (!form.consumptionEnd) errors.consumptionEnd = 'allocation.errors.date';
    else if (form.consumptionStart && form.consumptionEnd <= form.consumptionStart) {
      errors.consumptionEnd = 'allocation.errors.window';
    }
  }
  return errors;
}

function blank(value: string): string | null {
  return value.trim() ? value.trim() : null;
}

/**
 * The request for [form]: cancellation details only for disclosure and own consumption, a target only for sales.
 * Quantities are whole MWh, so they travel as numbers.
 */
export function toOpenObligation(form: ObligationForm, holderEntityId: string): OpenObligationBody {
  const body: OpenObligationBody = {
    kind: form.kind,
    holderEntityId,
    criterion: form.criterion,
    quantityMwh: Number(form.quantityMwh.trim()),
    deliveryWindow: { start: form.windowStart, endExclusive: form.windowEnd },
    deliveryDate: form.deliveryDate,
    priorityClass: form.priorityClass.trim() ? Number(form.priorityClass.trim()) : null,
  };
  if (needsTarget(form.kind)) {
    body.targetRegistryCode = blank(form.targetRegistryCode)?.toUpperCase() ?? null;
    body.targetAccountRef = blank(form.targetAccountRef);
  }
  if (needsCancellation(form.kind)) {
    body.cancellation = {
      purpose: form.purpose,
      beneficiaryName: blank(form.beneficiaryName),
      consumptionCountry: form.consumptionCountry.trim().toUpperCase(),
      consumptionPeriod: { start: form.consumptionStart, endExclusive: form.consumptionEnd },
    };
  }
  return body;
}

/** What the UI offers on an obligation: constraints while it is open, closing until it is closed. */
export function obligationActions(obligation: Pick<Obligation, 'status'>): { close: boolean; constraints: boolean } {
  return { close: obligation.status !== 'CLOSED', constraints: obligation.status === 'OPEN' };
}

export interface ConstraintsForm {
  /** Empty for no limit. */
  maxSharePerDevice: string;
  minShares: { criterion: Criterion; percent: string }[];
}

export function toConstraintsForm(constraints: readonly Constraint[]): ConstraintsForm {
  const max = constraints.find((c) => c.type === 'maxSharePerDevice');
  return {
    maxSharePerDevice: max ? String(max.percent) : '',
    minShares: constraints.flatMap((c) =>
      c.type === 'minShare' ? [{ criterion: c.criterion, percent: String(c.percent) }] : [],
    ),
  };
}

function isPercent(value: string): boolean {
  return WHOLE.test(value.trim()) && Number(value) >= 1 && Number(value) <= 100;
}

/** Field errors as i18n keys: `maxSharePerDevice`, `minShares.<i>.percent`, `minShares.<i>.criterion`. */
export function validateConstraints(form: ConstraintsForm): Record<string, string> {
  const errors: Record<string, string> = {};
  if (form.maxSharePerDevice.trim() && !isPercent(form.maxSharePerDevice)) {
    errors.maxSharePerDevice = 'allocation.errors.percent';
  }
  form.minShares.forEach((share, index) => {
    if (!isPercent(share.percent)) errors[`minShares.${index}.percent`] = 'allocation.errors.percent';
    if (incomplete(share.criterion).length > 0) errors[`minShares.${index}.criterion`] = 'allocation.errors.criterion';
  });
  return errors;
}

/** The constraint list the API replaces the obligation's constraints with. */
export function fromConstraintsForm(form: ConstraintsForm): Constraint[] {
  const constraints: Constraint[] = [];
  if (form.maxSharePerDevice.trim()) {
    constraints.push({ type: 'maxSharePerDevice', percent: Number(form.maxSharePerDevice.trim()) });
  }
  for (const share of form.minShares) {
    constraints.push({ type: 'minShare', criterion: share.criterion, percent: Number(share.percent.trim()) });
  }
  return constraints;
}
