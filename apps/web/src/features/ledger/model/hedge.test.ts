import { describe, expect, it } from 'vitest';
import { ProblemError } from '@/shared/api/problem';
import {
  canDiscontinue,
  eligibleTrades,
  emptyHedge,
  flattenViolations,
  hedgeCounts,
  hedgeProduct,
  hedgeSteps,
  instrumentSide,
  latestMeasurement,
  measurementSeries,
  openTaskType,
  toDesignation,
  toDiscontinuation,
  validProportion,
  validateHedge,
  withDirection,
  withFamily,
  withInstruments,
  type Hedge,
  type HedgeMeasurement,
  type TradeSummary,
} from './hedge';

const t = (key: string) => key;

const trade = (patch: Partial<TradeSummary>): TradeSummary =>
  ({
    id: 't-1',
    tradeNumber: 'T-00001',
    side: 'BUY',
    productFamily: 'EMISSION_ALLOWANCE',
    status: 'CONFIRMED',
    ...patch,
  }) as TradeSummary;

const hedge = (patch: Partial<Hedge> = {}): Hedge =>
  ({
    id: 'h-1',
    number: 'H-00001',
    status: 'ACTIVE',
    requestedBy: 'acc1',
    requestedAt: '2026-10-01T08:00:00Z',
    decidedBy: 'acc2',
    decidedAt: '2026-10-02T08:00:00Z',
    item: { direction: 'PURCHASE', productFamily: 'EMISSION_ALLOWANCE', allowanceProductCode: 'EUA' },
    instruments: [],
    checks: [],
    discontinuation: null,
    ...patch,
  }) as Hedge;

const measurement = (valuationDate: string, instrumentChange: number): HedgeMeasurement =>
  ({ valuationDate, instrumentChange, hypotheticalValue: 5, effective: 4 }) as HedgeMeasurement;

const valid = () => ({
  ...emptyHedge(),
  quantity: '10000',
  expectedDate: '2027-04-30',
  objective: 'Fix the cost of the 2026 surrender',
  instruments: [{ tradeId: 't-1', proportion: '1' }],
});

describe('hedge designation form', () => {
  it('needs quantity, date, objective and at least one instrument', () => {
    expect(validateHedge(emptyHedge())).toEqual({
      quantity: 'hedges.errors.positive',
      expectedDate: 'hedges.errors.required',
      objective: 'hedges.errors.required',
      instruments: 'hedges.errors.noInstruments',
    });
    expect(validateHedge(valid())).toEqual({});
  });

  it('asks for a bucket instead of an allowance product for certificates, with an optional four-digit year', () => {
    const goo = withFamily(valid(), 'GUARANTEE_OF_ORIGIN');
    expect(goo.allowanceProductCode).toBe('');
    expect(goo.instruments).toEqual([]);
    const errors = validateHedge({ ...goo, productionYear: '26', instruments: [{ tradeId: 't', proportion: '1' }] });
    expect(errors).toEqual({ bucketCode: 'hedges.errors.required', productionYear: 'hedges.errors.year' });
    expect(
      validateHedge({ ...goo, bucketCode: 'NORDIC_HYDRO', productionYear: '2026', instruments: valid().instruments }),
    ).toEqual({});
  });

  it('accepts a proportion above zero up to one', () => {
    expect(['1', '0.5', '0.25', '1.0'].every(validProportion)).toBe(true);
    expect(['0', '1.01', '-1', 'x', ''].some(validProportion)).toBe(false);
    const errors = validateHedge({ ...valid(), instruments: [{ tradeId: 't-1', proportion: '1.5' }] });
    expect(errors['instruments.0.proportion']).toBe('hedges.errors.proportion');
  });

  it('keeps typed proportions when the chosen trades change and defaults new ones to 1', () => {
    const start = { ...valid(), instruments: [{ tradeId: 'a', proportion: '0.4' }] };
    expect(withInstruments(start, ['a', 'b']).instruments).toEqual([
      { tradeId: 'a', proportion: '0.4' },
      { tradeId: 'b', proportion: '1' },
    ]);
    expect(withInstruments(start, []).instruments).toEqual([]);
  });

  it('clears the instruments when the direction changes, since their side no longer fits', () => {
    const values = valid();
    expect(withDirection(values, 'SALE').instruments).toEqual([]);
    expect(withDirection(values, 'PURCHASE')).toBe(values);
  });

  it('sends decimals as strings and only the product of its family', () => {
    const body = toDesignation(valid(), 'e-1');
    expect(JSON.parse(JSON.stringify(body))).toEqual({
      legalEntityId: 'e-1',
      item: {
        direction: 'PURCHASE',
        productFamily: 'EMISSION_ALLOWANCE',
        allowanceProductCode: 'EUA',
        bucketCode: null,
        productionYear: null,
        quantity: '10000',
        expectedDate: '2027-04-30',
        currency: 'EUR',
      },
      instruments: [{ tradeId: 't-1', proportion: '1' }],
      objective: 'Fix the cost of the 2026 surrender',
    });
    const goo = {
      ...withDirection(withFamily(valid(), 'GUARANTEE_OF_ORIGIN'), 'SALE'),
      bucketCode: 'NORDIC_HYDRO',
      productionYear: '2026',
      quantity: '2500.5',
      instruments: [{ tradeId: 't-2', proportion: '0.5' }],
    };
    expect(toDesignation(goo, 'e-1').item).toMatchObject({
      direction: 'SALE',
      allowanceProductCode: null,
      bucketCode: 'NORDIC_HYDRO',
      productionYear: 2026,
      quantity: '2500.5',
    });
  });

  it('puts server violations of the item on the flat form fields', () => {
    const error = new ProblemError({
      type: 'urn:etrm:problem:validation-failed',
      title: 'Validation failed',
      status: 422,
      violations: [
        { field: 'item.quantity', message: 'must be positive' },
        { field: 'instruments[0].proportion', message: 'too much' },
      ],
    });
    expect(flattenViolations(error).problem.violations).toEqual([
      { field: 'quantity', message: 'must be positive' },
      { field: 'instruments[0].proportion', message: 'too much' },
    ]);
  });

  it('builds the discontinuation request with a trimmed reason', () => {
    expect(toDiscontinuation('  Forecast cancelled ', false)).toEqual({
      reason: 'Forecast cancelled',
      stillExpected: false,
    });
  });
});

describe('instrument choice', () => {
  const trades = [
    trade({ id: 'a', side: 'BUY' }),
    trade({ id: 'b', side: 'SELL' }),
    trade({ id: 'c', side: 'BUY', status: 'DRAFT' }),
    trade({ id: 'd', side: 'BUY', status: 'PARTIALLY_DELIVERED' }),
    trade({ id: 'e', side: 'BUY', productFamily: 'GUARANTEE_OF_ORIGIN' }),
    trade({ id: 'f', side: 'BUY', status: 'SETTLED' }),
  ];

  it('buys to hedge a purchase and sells to hedge a sale', () => {
    expect(instrumentSide('PURCHASE')).toBe('BUY');
    expect(instrumentSide('SALE')).toBe('SELL');
  });

  it('offers open trades of the side and family', () => {
    expect(eligibleTrades(trades, 'PURCHASE', 'EMISSION_ALLOWANCE').map((x) => x.id)).toEqual(['a', 'd']);
    expect(eligibleTrades(trades, 'SALE', 'EMISSION_ALLOWANCE').map((x) => x.id)).toEqual(['b']);
    expect(eligibleTrades(trades, 'PURCHASE', 'GUARANTEE_OF_ORIGIN').map((x) => x.id)).toEqual(['e']);
  });
});

describe('hedge lifecycle', () => {
  it('waits for the second accountant while pending', () => {
    const steps = hedgeSteps(hedge({ status: 'PENDING', decidedBy: null, decidedAt: null }), t);
    expect(steps.map((s) => [s.label, s.state])).toEqual([
      ['hedges.lifecycle.requested', 'done'],
      ['hedges.lifecycle.awaitingApproval', 'current'],
      ['hedges.lifecycle.discontinued', 'pending'],
    ]);
    expect(openTaskType(hedge({ status: 'PENDING' }))).toBe('HEDGE_DESIGNATION');
  });

  it('ends a rejected hedge in a failed step', () => {
    const steps = hedgeSteps(hedge({ status: 'REJECTED' }), t);
    expect(steps.map((s) => s.state)).toEqual(['done', 'failed']);
    expect(steps[1]).toMatchObject({ by: 'acc2', label: 'hedges.lifecycle.rejected' });
  });

  it('shows an active hedge as approved with the discontinuation still to come', () => {
    const steps = hedgeSteps(hedge(), t);
    expect(steps.map((s) => s.state)).toEqual(['done', 'current', 'pending']);
    expect(canDiscontinue(hedge())).toBe(true);
    expect(openTaskType(hedge())).toBeNull();
  });

  it('makes a requested discontinuation the current step and blocks another request', () => {
    const waiting = hedge({
      discontinuation: {
        reason: 'Forecast cancelled',
        stillExpected: false,
        requestedBy: 'acc1',
        requestedAt: '2026-11-01T09:00:00Z',
      },
    });
    const steps = hedgeSteps(waiting, t);
    expect(steps.map((s) => [s.label, s.state])).toEqual([
      ['hedges.lifecycle.requested', 'done'],
      ['hedges.lifecycle.approved', 'done'],
      ['hedges.lifecycle.discontinuing', 'current'],
    ]);
    expect(steps[2]?.detail).toBe('Forecast cancelled');
    expect(canDiscontinue(waiting)).toBe(false);
    expect(openTaskType(waiting)).toBe('HEDGE_DISCONTINUATION');
  });

  it('finishes with the discontinuation decided by the second accountant', () => {
    const done = hedge({
      status: 'DISCONTINUED',
      discontinuation: {
        reason: 'Forecast cancelled',
        stillExpected: true,
        requestedBy: 'acc1',
        requestedAt: '2026-11-01T09:00:00Z',
        decidedBy: 'acc2',
        decidedAt: '2026-11-02T09:00:00Z',
        frozenReserve: 120,
      },
    });
    const steps = hedgeSteps(done, t);
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'done']);
    expect(steps[2]).toMatchObject({ label: 'hedges.lifecycle.discontinued', by: 'acc2' });
    expect(canDiscontinue(done)).toBe(false);
    expect(openTaskType(done)).toBeNull();
  });
});

describe('hedge list and measurements', () => {
  it('counts active, pending and discontinuing hedges', () => {
    const waiting = hedge({
      discontinuation: { reason: 'r', stillExpected: true, requestedBy: 'a', requestedAt: '2026-11-01T09:00:00Z' },
    });
    expect(hedgeCounts([hedge(), waiting, hedge({ status: 'PENDING' }), hedge({ status: 'REJECTED' })])).toEqual({
      active: 2,
      pending: 1,
      discontinuing: 1,
    });
  });

  it('labels the product with its production year', () => {
    expect(hedgeProduct({ allowanceProductCode: 'EUA' })).toBe('EUA');
    expect(hedgeProduct({ bucketCode: 'NORDIC_HYDRO', productionYear: 2026 })).toBe('NORDIC_HYDRO 2026');
  });

  it('orders the chart by valuation date and finds the latest measurement', () => {
    const ms = [measurement('2026-10-31', 3), measurement('2026-09-30', 2)];
    expect(measurementSeries(ms).map((p) => p.date)).toEqual(['2026-09-30', '2026-10-31']);
    expect(measurementSeries(ms)[1]).toEqual({
      date: '2026-10-31',
      instrumentChange: 3,
      hypotheticalValue: 5,
      effective: 4,
    });
    expect(latestMeasurement(ms)?.valuationDate).toBe('2026-10-31');
    expect(latestMeasurement([])).toBeUndefined();
  });
});
