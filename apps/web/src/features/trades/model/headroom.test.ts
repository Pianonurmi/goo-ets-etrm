import {
  bindingSupplies,
  demandRef,
  headroomByRef,
  headroomRequest,
  jointShortfall,
  ownScheduleIds,
  type HeadroomResult,
} from './headroom';
import { emptyLeg, emptyTrade, type TradeFormValues, type TradeView } from './tradeForm';

const ENTITY = '00000000-0000-7000-8000-000000000001';
const TODAY = '2026-10-12';

function sale(quantities: string[], overrides: Partial<TradeFormValues> = {}): TradeFormValues {
  const trade = emptyTrade(TODAY);
  const leg = trade.legs[0]!;
  leg.deliveries = quantities.map((quantity, i) => ({
    start: TODAY,
    endExclusive: '2027-01-01',
    deliveryDate: `2026-1${i}-15`,
    quantity,
  }));
  return { ...trade, ...overrides };
}

describe('headroomRequest', () => {
  it('asks one demand per usable delivery of a sale leg, referenced by leg and delivery', () => {
    const request = headroomRequest(sale(['100', '250.5']), ENTITY);
    expect(request?.holderEntityId).toBe(ENTITY);
    expect(request?.demands).toHaveLength(2);
    expect(request?.demands[0]).toMatchObject({ quantityMwh: 100, deliveryDate: '2026-10-15', ref: '0-0' });
    expect(request?.demands[1]).toMatchObject({ quantityMwh: 251, ref: demandRef(0, 1) });
    expect(request?.demands[0]?.criterion).toEqual(emptyLeg('GUARANTEE_OF_ORIGIN', TODAY).criterion);
    expect(request?.excludeSourceRefs).toBeUndefined();
  });

  it('skips deliveries that are not filled in yet', () => {
    const values = sale(['', '40', 'abc', '0']);
    values.legs[0]!.deliveries[1]!.deliveryDate = '';
    expect(headroomRequest(values, ENTITY)).toBeNull();
    expect(headroomRequest(sale(['', '40', 'abc']), ENTITY)?.demands.map((d) => d.ref)).toEqual(['0-1']);
  });

  it('asks nothing for purchases, allowances, futures or without an entity', () => {
    const buy = sale(['100']);
    buy.legs[0]!.side = 'BUY';
    expect(headroomRequest(buy, ENTITY)).toBeNull();
    expect(headroomRequest(sale(['100'], { productFamily: 'EMISSION_ALLOWANCE' }), ENTITY)).toBeNull();
    expect(headroomRequest(sale(['100'], { instrumentType: 'FUTURE' }), ENTITY)).toBeNull();
    expect(headroomRequest(sale(['100']), undefined)).toBeNull();
  });

  it('numbers deliveries by their leg in the form, counting buy legs', () => {
    const values = sale(['100']);
    const second = emptyLeg('GUARANTEE_OF_ORIGIN', TODAY);
    second.deliveries[0]!.quantity = '30';
    values.legs[0]!.side = 'BUY';
    values.legs.push(second);
    expect(headroomRequest(values, ENTITY)?.demands.map((d) => d.ref)).toEqual(['1-0']);
  });

  it('leaves out the own schedules of the trade being amended', () => {
    const request = headroomRequest(sale(['100']), ENTITY, ['a', 'b']);
    expect(request?.excludeSourceRefs).toEqual(['a', 'b']);
  });
});

describe('ownScheduleIds', () => {
  const trade = (family: TradeView['productFamily']) =>
    ({
      productFamily: family,
      legs: [
        { side: 'SELL', deliveries: [{ id: 's1' }, { id: 's2' }] },
        { side: 'BUY', deliveries: [{ id: 'b1' }] },
      ],
    }) as unknown as TradeView;

  it('lists the delivery schedules of the sale legs of a GoO trade', () => {
    expect(ownScheduleIds(trade('GUARANTEE_OF_ORIGIN'))).toEqual(['s1', 's2']);
  });

  it('lists none for a new trade or an allowance trade', () => {
    expect(ownScheduleIds(undefined)).toEqual([]);
    expect(ownScheduleIds(trade('EMISSION_ALLOWANCE'))).toEqual([]);
  });
});

const result = (overrides: Partial<HeadroomResult> = {}): HeadroomResult => ({
  holderEntityId: ENTITY,
  stamp: 's',
  computedAt: '2026-10-12T08:00:00Z',
  demands: [
    { ref: '0-0', requestedMwh: 100, headroomMwh: 150, freeMwh: 150, fits: true, binding: [] },
    { ref: '0-1', requestedMwh: 100, headroomMwh: 120, freeMwh: 120, fits: true, binding: [] },
  ],
  requestedMwh: 200,
  jointMwh: 200,
  fits: true,
  ...overrides,
});

describe('headroomByRef', () => {
  it('finds the answer for a delivery row by its reference', () => {
    expect(headroomByRef(result()).get('0-1')?.headroomMwh).toBe(120);
    expect(headroomByRef(result()).get('1-0')).toBeUndefined();
    expect(headroomByRef(undefined).size).toBe(0);
  });
});

describe('jointShortfall', () => {
  it('is set when each delivery fits but together they exceed the joint figure', () => {
    expect(jointShortfall(result({ jointMwh: 170 }))).toEqual({ requestedMwh: 200, jointMwh: 170 });
  });

  it('is not set when everything fits together, when one delivery already fails or with a single delivery', () => {
    expect(jointShortfall(result())).toBeNull();
    expect(jointShortfall(result({ jointMwh: null }))).toBeNull();
    const failing = result({ jointMwh: 100 });
    failing.demands[0] = { ...failing.demands[0]!, fits: false };
    expect(jointShortfall(failing)).toBeNull();
    expect(jointShortfall(result({ demands: [result().demands[0]!], jointMwh: 50 }))).toBeNull();
    expect(jointShortfall(undefined)).toBeNull();
  });
});

describe('bindingSupplies', () => {
  it('lists the scarcest supply first and at most three', () => {
    const supplies = [10, 40, 5, 25].map((eligibleMwh, i) => ({
      bucketCode: `B${i}`,
      productionYear: 2025,
      eligibleMwh,
    }));
    const demand = { ref: '0-0', requestedMwh: 1, headroomMwh: 0, freeMwh: 0, fits: false, binding: supplies };
    expect(bindingSupplies(demand).map((s) => s.eligibleMwh)).toEqual([5, 10, 25]);
  });
});
