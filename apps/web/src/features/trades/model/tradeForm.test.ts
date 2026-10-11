import { diffVersions } from './diff';
import {
  brokersOf,
  counterpartiesFor,
  emptyTrade,
  fromTrade,
  isIntercompany,
  toAmendTrade,
  toCaptureTrade,
  validateTrade,
  withFuture,
  type FuturesContract,
  type TradeView,
} from './tradeForm';

type Counterparty = Parameters<typeof brokersOf>[0][number];
const party = (code: string, counterpartyType: Counterparty['counterpartyType'], linked?: string) =>
  ({ id: code, code, counterpartyType, linkedLegalEntityId: linked ?? null }) as unknown as Counterparty;

describe('trade form', () => {
  it('builds a capture request with decimals as strings and family-specific legs', () => {
    const values = emptyTrade('2026-10-10');
    values.bookId = 'b1';
    values.counterpartyId = 'c1';
    values.legs[0] = {
      ...values.legs[0]!,
      quantity: '600',
      price: '1.950000000000000001',
      deliveries: [{ ...values.legs[0]!.deliveries[0]!, quantity: '600' }],
    };
    const request = toCaptureTrade(values, 'e1');
    expect(JSON.parse(JSON.stringify(request))).toMatchObject({
      legalEntityId: 'e1',
      productFamily: 'GUARANTEE_OF_ORIGIN',
      regulatory: null,
      legs: [
        {
          side: 'SELL',
          unit: 'MWH',
          quantity: '600',
          price: '1.950000000000000001',
          allowanceProductCode: null,
          priceType: 'FIXED',
        },
      ],
    });
    expect(request.legs[0]?.criterion).toMatchObject({ type: 'all' });
    expect(request.legs[0]?.deliveries[0]?.deliveryWindow).toEqual({ start: '2026-10-10', endExclusive: '2027-01-01' });
  });

  it('switches EUA legs to allowance products and tonnes', () => {
    const values = { ...emptyTrade('2026-10-10'), productFamily: 'EMISSION_ALLOWANCE' as const };
    values.legs[0] = { ...values.legs[0]!, allowanceProductCode: 'EUA' };
    const leg = toCaptureTrade(values, 'e1').legs[0];
    expect(leg).toMatchObject({ unit: 'TCO2E', allowanceProductCode: 'EUA' });
    expect(leg?.criterion).toBeUndefined();
  });

  it('validates required fields, numbers and windows', () => {
    const errors = validateTrade(emptyTrade('2026-10-10'), { amend: true, intercompany: false });
    expect(errors).toMatchObject({
      bookId: 'Required',
      counterpartyId: 'Required',
      reason: 'Required',
      'legs.0.quantity': 'A positive number',
    });
    const values = emptyTrade('2026-10-10');
    values.legs[0]!.deliveries[0]!.endExclusive = '2026-01-01';
    expect(
      validateTrade(values, { amend: false, intercompany: false })['legs.0.deliveries.0.endExclusive'],
    ).toBeDefined();
  });

  it('sends an index-priced leg with its index and the spread as price', () => {
    const values = emptyTrade('2026-10-10');
    values.legs[0] = { ...values.legs[0]!, priceType: 'INDEX', priceIndex: '', quantity: '100', price: '0.15' };
    expect(validateTrade(values, { amend: false, intercompany: false })['legs.0.priceIndex']).toBe('Required');
    values.legs[0].priceIndex = 'GOO_NORDIC_HYDRO_IDX';
    expect(toCaptureTrade(values, 'e1').legs[0]).toMatchObject({
      priceType: 'INDEX',
      priceIndex: 'GOO_NORDIC_HYDRO_IDX',
      futuresContractCode: null,
    });
  });

  it('takes the product, currency and single delivery of a future from its contract', () => {
    const contract = {
      code: 'EUA-DEC27',
      allowanceProductCode: 'EUA',
      currency: 'EUR',
      deliveryDate: '2027-12-20',
      contractSize: 1000,
      venueMic: 'XEEE',
    } as FuturesContract;
    const values = {
      ...emptyTrade('2026-10-10'),
      productFamily: 'EMISSION_ALLOWANCE' as const,
      instrumentType: 'FUTURE' as const,
    };
    expect(validateTrade(values, { amend: false, intercompany: false })['legs.0.futuresContractCode']).toBe('Required');
    values.legs[0] = withFuture({ ...values.legs[0]!, quantity: '5000', priceType: 'INDEX' }, contract);
    const leg = toCaptureTrade(values, 'e1').legs[0];
    expect(JSON.parse(JSON.stringify(leg))).toMatchObject({
      futuresContractCode: 'EUA-DEC27',
      allowanceProductCode: 'EUA',
      priceType: 'FIXED',
      deliveries: [
        {
          deliveryDate: '2027-12-20',
          quantity: '5000',
          deliveryWindow: { start: '2027-12-20', endExclusive: '2027-12-21' },
        },
      ],
    });
  });

  it('offers counterparties by instrument and needs a mirror book for an intercompany trade', () => {
    const list = [
      party('CP-A', 'EXTERNAL'),
      party('CP-ICE', 'EXCHANGE'),
      party('CP-BRK', 'BROKER'),
      party('DEMO-SE', 'INTERCOMPANY', 'se'),
    ];
    expect(counterpartiesFor(list, 'FUTURE').map((c) => c.code)).toEqual(['CP-ICE']);
    expect(counterpartiesFor(list, 'FORWARD').map((c) => c.code)).toEqual(['CP-A', 'CP-ICE', 'DEMO-SE']);
    expect(brokersOf(list).map((c) => c.code)).toEqual(['CP-BRK']);
    expect(isIntercompany(list[3])).toBe(true);
    expect(isIntercompany(list[0])).toBe(false);
    const values = emptyTrade('2026-10-10');
    expect(validateTrade(values, { amend: false, intercompany: true }).mirrorBookId).toBe('Required');
    expect(validateTrade(values, { amend: true, intercompany: true }).mirrorBookId).toBeUndefined();
    values.mirrorBookId = 'se-book';
    values.brokerId = 'CP-BRK';
    expect(toCaptureTrade(values, 'e1')).toMatchObject({
      mirrorBookId: 'se-book',
      brokerId: 'CP-BRK',
      masterAgreementRef: null,
    });
  });

  it('round-trips an existing trade into an amendment', () => {
    const trade = {
      id: 't1',
      bookId: 'b1',
      counterpartyId: 'c1',
      productFamily: 'GUARANTEE_OF_ORIGIN',
      instrumentType: 'FORWARD',
      tradeDate: '2026-10-01',
      trader: 'trader',
      externalRef: 'EXT-1',
      version: 3,
      regulatory: null,
      legs: [
        {
          id: 'l1',
          legNo: 1,
          side: 'SELL',
          quantity: 600,
          unit: 'MWH',
          price: 1.95,
          priceCurrency: 'EUR',
          priceType: 'FIXED',
          criterion: { type: 'matchAll' },
          deliveries: [
            {
              id: 'd1',
              scheduleNo: 1,
              status: 'PENDING',
              quantity: 600,
              deliveredQuantity: 0,
              deliveryDate: '2026-12-15',
              deliveryWindow: { start: '2026-10-11', endExclusive: '2027-01-01' },
            },
          ],
        },
      ],
    } as unknown as TradeView;
    const values = fromTrade(trade);
    values.reason = 'price correction';
    values.legs[0]!.price = '2.00';
    const amend = toAmendTrade(values, trade.version);
    expect(JSON.parse(JSON.stringify(amend))).toMatchObject({
      expectedVersion: 3,
      reason: 'price correction',
      externalRef: 'EXT-1',
      legs: [{ price: '2.00', quantity: '600' }],
    });
  });

  it('diffs versions by path, ignoring bookkeeping', () => {
    const before = { version: 1, status: 'PENDING_APPROVAL', legs: [{ price: 1.95, quantity: 600 }] };
    const after = { version: 2, status: 'PENDING_APPROVAL', legs: [{ price: 2.0, quantity: 600 }], externalRef: 'X' };
    expect(diffVersions(before, after)).toEqual([
      { path: 'externalRef', before: undefined, after: 'X' },
      { path: 'legs.0.price', before: 1.95, after: 2.0 },
    ]);
  });
});
