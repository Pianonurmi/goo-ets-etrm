import {
  emptyObligation,
  fromConstraintsForm,
  obligationActions,
  toConstraintsForm,
  toOpenObligation,
  validateConstraints,
  validateObligation,
  type ObligationForm,
} from './obligation';

const ENTITY = '0190a000-0000-7000-8000-000000000001';

function form(overrides: Partial<ObligationForm> = {}): ObligationForm {
  return { ...emptyObligation('2026-10-11'), quantityMwh: '120', consumptionCountry: 'fi', ...overrides };
}

describe('emptyObligation', () => {
  it('starts with own consumption in the previous month, delivered within a month', () => {
    const empty = emptyObligation('2026-10-11');
    expect(empty.kind).toBe('OWN_CONSUMPTION');
    expect(empty.windowStart).toBe('2026-10-11');
    expect(empty.deliveryDate).toBe('2026-11-10');
    expect(empty.consumptionStart).toBe('2026-09-01');
    expect(empty.consumptionEnd).toBe('2026-10-01');
  });
});

describe('validateObligation', () => {
  it('accepts a complete own-consumption obligation', () => {
    expect(validateObligation(form())).toEqual({});
  });

  it('wants whole positive MWh, an ordered window and a country for cancellations', () => {
    const errors = validateObligation(
      form({ quantityMwh: '1.5', windowEnd: '2026-10-01', consumptionCountry: 'Finland' }),
    );
    expect(errors).toEqual({
      quantityMwh: 'allocation.errors.quantity',
      windowEnd: 'allocation.errors.window',
      consumptionCountry: 'allocation.errors.country',
    });
  });

  it('wants a target account for sales but no cancellation details', () => {
    expect(validateObligation(form({ kind: 'SALE', consumptionCountry: '' }))).toEqual({
      targetAccountRef: 'allocation.errors.target',
    });
  });

  it('refuses an incomplete criterion', () => {
    const errors = validateObligation(form({ criterion: { type: 'equals', attribute: 'ENERGY_SOURCE', value: '' } }));
    expect(errors.criterion).toBe('allocation.errors.criterion');
  });
});

describe('toOpenObligation', () => {
  it('sends cancellation details for own consumption and no target', () => {
    const body = toOpenObligation(form({ beneficiaryName: ' Offices ' }), ENTITY);
    expect(body).toMatchObject({
      kind: 'OWN_CONSUMPTION',
      holderEntityId: ENTITY,
      quantityMwh: 120,
      deliveryWindow: { start: '2026-10-11', endExclusive: '2026-11-25' },
      deliveryDate: '2026-11-10',
      priorityClass: null,
      cancellation: {
        purpose: 'OWN_CONSUMPTION',
        beneficiaryName: 'Offices',
        consumptionCountry: 'FI',
        consumptionPeriod: { start: '2026-09-01', endExclusive: '2026-10-01' },
      },
    });
    expect(body.targetAccountRef).toBeUndefined();
  });

  it('sends the target of a sale and no cancellation', () => {
    const body = toOpenObligation(
      form({ kind: 'SALE', targetRegistryCode: 'simulator', targetAccountRef: 'SIM-1', priorityClass: '2' }),
      ENTITY,
    );
    expect(body.targetRegistryCode).toBe('SIMULATOR');
    expect(body.targetAccountRef).toBe('SIM-1');
    expect(body.priorityClass).toBe(2);
    expect(body.cancellation).toBeUndefined();
  });
});

describe('obligationActions', () => {
  it('offers constraints while open and closing until closed', () => {
    expect(obligationActions({ status: 'OPEN' })).toEqual({ close: true, constraints: true });
    expect(obligationActions({ status: 'FULFILLED' })).toEqual({ close: true, constraints: false });
    expect(obligationActions({ status: 'CLOSED' })).toEqual({ close: false, constraints: false });
  });
});

describe('constraints', () => {
  const hydro = { type: 'descendantOf', attribute: 'ENERGY_SOURCE', code: 'HYDRO' } as const;

  it('round-trips through the form', () => {
    const constraints = [
      { type: 'maxSharePerDevice', percent: 80 },
      { type: 'minShare', criterion: hydro, percent: 30 },
    ] as const;
    const edited = toConstraintsForm([...constraints]);
    expect(edited).toEqual({ maxSharePerDevice: '80', minShares: [{ criterion: hydro, percent: '30' }] });
    expect(fromConstraintsForm(edited)).toEqual(constraints);
  });

  it('leaves out an empty device limit', () => {
    expect(fromConstraintsForm({ maxSharePerDevice: ' ', minShares: [] })).toEqual([]);
  });

  it('wants whole percentages from 1 to 100 and complete criteria', () => {
    expect(
      validateConstraints({
        maxSharePerDevice: '120',
        minShares: [
          { criterion: hydro, percent: '0' },
          { criterion: { type: 'in', attribute: 'DEVICE_COUNTRY', values: [] }, percent: '50' },
        ],
      }),
    ).toEqual({
      maxSharePerDevice: 'allocation.errors.percent',
      'minShares.0.percent': 'allocation.errors.percent',
      'minShares.1.criterion': 'allocation.errors.criterion',
    });
  });
});
