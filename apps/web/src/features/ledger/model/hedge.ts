import type { LifecycleStep, StepState } from '@/shared/components/LifecycleTimeline';
import { ProblemError } from '@/shared/api/problem';
import { decimal, type Body, type Schema } from '@/shared/api/types';

export type Hedge = Schema<'HedgeRelationshipView'>;
export type HedgeMeasurement = Schema<'HedgeMeasurementView'>;
export type HedgeStatus = Hedge['status'];
export type HedgeDirection = Hedge['item']['direction'];
export type HedgeFamily = Hedge['item']['productFamily'];
export type TradeSummary = Schema<'TradeSummaryView'>;

type Translate = (key: string) => string;

export const HEDGE_STATUSES: HedgeStatus[] = ['PENDING', 'ACTIVE', 'REJECTED', 'DISCONTINUED'];
export const HEDGE_CURRENCIES = ['EUR', 'SEK', 'NOK', 'DKK', 'PLN', 'CHF', 'GBP'];

/** Trades that are still open for delivery: the ones a forward can hedge with. */
export const INSTRUMENT_STATUSES: TradeSummary['status'][] = ['APPROVED', 'CONFIRMED', 'PARTIALLY_DELIVERED'];

/** A forecast purchase is hedged by bought forwards, a forecast sale by sold ones. */
export function instrumentSide(direction: HedgeDirection): TradeSummary['side'] {
  return direction === 'PURCHASE' ? 'BUY' : 'SELL';
}

/**
 * The open trades of the right side and product family that can be designated as instruments: for allowances only
 * those of the hedged allowance product, and never futures, whose result the daily margin realises (ADR 0046).
 */
export function eligibleTrades(
  trades: readonly TradeSummary[],
  direction: HedgeDirection,
  family: HedgeFamily,
  allowanceProductCode?: string,
): TradeSummary[] {
  const side = instrumentSide(direction);
  return trades.filter(
    (trade) =>
      trade.side === side &&
      trade.productFamily === family &&
      trade.instrumentType !== 'FUTURE' &&
      INSTRUMENT_STATUSES.includes(trade.status) &&
      (family !== 'EMISSION_ALLOWANCE' || !allowanceProductCode || trade.product === allowanceProductCode),
  );
}

/** The hedged product as one label: the allowance product or the bucket, with the production year when given. */
export function hedgeProduct(
  item: Pick<Hedge['item'], 'allowanceProductCode' | 'bucketCode' | 'productionYear'>,
): string {
  const code = item.allowanceProductCode ?? item.bucketCode ?? '';
  return item.productionYear ? `${code} ${item.productionYear}`.trim() : code;
}

export interface InstrumentDraft {
  tradeId: string;
  /** A fraction above zero up to one, typed. */
  proportion: string;
}

export interface HedgeFormValues {
  direction: HedgeDirection;
  productFamily: HedgeFamily;
  allowanceProductCode: string;
  bucketCode: string;
  productionYear: string;
  quantity: string;
  expectedDate: string;
  currency: string;
  objective: string;
  instruments: InstrumentDraft[];
}

export function emptyHedge(): HedgeFormValues {
  return {
    direction: 'PURCHASE',
    productFamily: 'EMISSION_ALLOWANCE',
    allowanceProductCode: 'EUA',
    bucketCode: '',
    productionYear: '',
    quantity: '',
    expectedDate: '',
    currency: 'EUR',
    objective: '',
    instruments: [],
  };
}

/** Switching the product family clears the product of the other family and the instruments chosen for it. */
export function withFamily(values: HedgeFormValues, productFamily: HedgeFamily): HedgeFormValues {
  if (values.productFamily === productFamily) return values;
  return {
    ...values,
    productFamily,
    allowanceProductCode: productFamily === 'EMISSION_ALLOWANCE' ? 'EUA' : '',
    bucketCode: '',
    productionYear: '',
    instruments: [],
  };
}

/** Switching direction changes the side of the instruments, so the ones chosen no longer fit. */
export function withDirection(values: HedgeFormValues, direction: HedgeDirection): HedgeFormValues {
  return values.direction === direction ? values : { ...values, direction, instruments: [] };
}

/** Adds or removes the chosen trades, keeping the proportion already typed for those that stay (default 1). */
export function withInstruments(values: HedgeFormValues, tradeIds: readonly string[]): HedgeFormValues {
  const typed = new Map(values.instruments.map((instrument) => [instrument.tradeId, instrument.proportion]));
  return {
    ...values,
    instruments: tradeIds.map((tradeId) => ({ tradeId, proportion: typed.get(tradeId) ?? '1' })),
  };
}

const POSITIVE = /^\d+(\.\d+)?$/;
const YEAR = /^\d{4}$/;

/** Whether a typed proportion is above zero and at most one. */
export function validProportion(text: string): boolean {
  const value = text.trim();
  return POSITIVE.test(value) && Number(value) > 0 && Number(value) <= 1;
}

/** Translation keys of what still stops the designation, by form field (instrument fields as `instruments.0.proportion`). */
export function validateHedge(values: HedgeFormValues): Record<string, string> {
  const errors: Record<string, string> = {};
  const required = 'hedges.errors.required';
  if (values.productFamily === 'EMISSION_ALLOWANCE' && !values.allowanceProductCode) {
    errors.allowanceProductCode = required;
  }
  if (values.productFamily === 'GUARANTEE_OF_ORIGIN') {
    if (!values.bucketCode) errors.bucketCode = required;
    if (values.productionYear.trim() && !YEAR.test(values.productionYear.trim())) {
      errors.productionYear = 'hedges.errors.year';
    }
  }
  if (!POSITIVE.test(values.quantity.trim()) || Number(values.quantity) <= 0)
    errors.quantity = 'hedges.errors.positive';
  if (!values.expectedDate) errors.expectedDate = required;
  if (!values.currency) errors.currency = required;
  if (!values.objective.trim()) errors.objective = required;
  if (values.instruments.length === 0) errors.instruments = 'hedges.errors.noInstruments';
  values.instruments.forEach((instrument, index) => {
    if (!validProportion(instrument.proportion)) errors[`instruments.${index}.proportion`] = 'hedges.errors.proportion';
  });
  return errors;
}

/** Field names the designation form shows, for placing server violations (see applyProblemToForm). */
export const HEDGE_FORM_FIELDS = new Set([
  'direction',
  'productFamily',
  'allowanceProductCode',
  'bucketCode',
  'productionYear',
  'quantity',
  'expectedDate',
  'currency',
  'objective',
  'instruments',
]);

/** Server violations name fields under `item.`; the form's own fields are flat, so the prefix is dropped. */
export function flattenViolations(error: ProblemError): ProblemError {
  return new ProblemError({
    ...error.problem,
    violations: (error.problem.violations ?? []).map((violation) => ({
      ...violation,
      field: violation.field.replace(/^item\./, ''),
    })),
  });
}

/** The designation as the API takes it; decimals travel as strings. */
export function toDesignation(values: HedgeFormValues, legalEntityId: string): Body<'RequestHedgeDesignation'> {
  const goo = values.productFamily === 'GUARANTEE_OF_ORIGIN';
  const year = values.productionYear.trim();
  return {
    legalEntityId,
    item: {
      direction: values.direction,
      productFamily: values.productFamily,
      allowanceProductCode: goo ? null : values.allowanceProductCode,
      bucketCode: goo ? values.bucketCode : null,
      productionYear: goo && year ? Number.parseInt(year, 10) : null,
      quantity: decimal(values.quantity),
      expectedDate: values.expectedDate,
      currency: values.currency,
    },
    instruments: values.instruments.map((instrument) => ({
      tradeId: instrument.tradeId,
      proportion: decimal(instrument.proportion),
    })),
    objective: values.objective.trim(),
  };
}

/** The discontinuation as the API takes it. */
export function toDiscontinuation(reason: string, stillExpected: boolean): Body<'RequestHedgeDiscontinuation'> {
  return { reason: reason.trim(), stillExpected };
}

/** Whether a discontinuation was requested and no one has decided it yet. */
export function discontinuationPending(hedge: Pick<Hedge, 'status' | 'discontinuation'>): boolean {
  return hedge.status === 'ACTIVE' && hedge.discontinuation != null && !hedge.discontinuation.decidedAt;
}

/** Only an active hedge can be discontinued, and once at a time. */
export function canDiscontinue(hedge: Pick<Hedge, 'status' | 'discontinuation'>): boolean {
  return hedge.status === 'ACTIVE' && hedge.discontinuation == null;
}

/** The task a second accountant decides in the inbox, if one is waiting. */
export function openTaskType(hedge: Pick<Hedge, 'status' | 'discontinuation'>): string | null {
  if (hedge.status === 'PENDING') return 'HEDGE_DESIGNATION';
  return discontinuationPending(hedge) ? 'HEDGE_DISCONTINUATION' : null;
}

export interface HedgeCounts {
  active: number;
  pending: number;
  discontinuing: number;
}

/** Headline counts of a list of hedges; nothing is summed. */
export function hedgeCounts(hedges: readonly Hedge[]): HedgeCounts {
  return {
    active: hedges.filter((hedge) => hedge.status === 'ACTIVE').length,
    pending: hedges.filter((hedge) => hedge.status === 'PENDING').length,
    discontinuing: hedges.filter(discontinuationPending).length,
  };
}

/**
 * A hedge's lifecycle: requested → approved by a second accountant (or rejected) → discontinued. A discontinuation
 * waiting for its approval is the current step of an active hedge.
 */
export function hedgeSteps(hedge: Hedge, t: Translate): LifecycleStep[] {
  const requested: LifecycleStep = {
    label: t('hedges.lifecycle.requested'),
    state: 'done',
    at: hedge.requestedAt,
    by: hedge.requestedBy,
  };
  if (hedge.status === 'REJECTED') {
    return [
      requested,
      { label: t('hedges.lifecycle.rejected'), state: 'failed', at: hedge.decidedAt, by: hedge.decidedBy },
    ];
  }
  if (hedge.status === 'PENDING') {
    return [
      requested,
      { label: t('hedges.lifecycle.awaitingApproval'), state: 'current' },
      { label: t('hedges.lifecycle.discontinued'), state: 'pending' },
    ];
  }
  const approved: LifecycleStep = {
    label: t('hedges.lifecycle.approved'),
    state: 'done',
    at: hedge.decidedAt,
    by: hedge.decidedBy,
  };
  const d = hedge.discontinuation;
  if (!d) {
    return [
      requested,
      { ...approved, state: 'current' },
      { label: t('hedges.lifecycle.discontinued'), state: 'pending' },
    ];
  }
  const finished = hedge.status === 'DISCONTINUED';
  const state: StepState = finished ? 'done' : 'current';
  return [
    requested,
    approved,
    {
      label: t(finished ? 'hedges.lifecycle.discontinued' : 'hedges.lifecycle.discontinuing'),
      state,
      at: finished ? (d.decidedAt ?? d.requestedAt) : d.requestedAt,
      by: finished ? (d.decidedBy ?? d.requestedBy) : d.requestedBy,
      detail: d.reason,
    },
  ];
}

export interface MeasurementPoint {
  date: string;
  instrumentChange: number;
  hypotheticalValue: number;
  effective: number;
}

/** The measurements as chart rows, oldest valuation first; the values are passed on as they are. */
export function measurementSeries(measurements: readonly HedgeMeasurement[]): MeasurementPoint[] {
  return [...measurements]
    .sort((a, b) => a.valuationDate.localeCompare(b.valuationDate))
    .map((m) => ({
      date: m.valuationDate,
      instrumentChange: m.instrumentChange,
      hypotheticalValue: m.hypotheticalValue,
      effective: m.effective,
    }));
}

/** The most recent measurement, by valuation date. */
export function latestMeasurement(measurements: readonly HedgeMeasurement[]): HedgeMeasurement | undefined {
  return [...measurements].sort((a, b) => b.valuationDate.localeCompare(a.valuationDate))[0];
}
