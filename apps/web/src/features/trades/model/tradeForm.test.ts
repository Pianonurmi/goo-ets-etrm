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
  wholeLots,
  withFuture,
  withUnderlying,
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

  describe('options', () => {
    const contract = {
      code: 'EUA-DEC27',
      allowanceProductCode: 'EUA',
      currency: 'EUR',
      deliveryDate: '2027-12-20',
      lastTradingDay: '2027-12-17',
      contractSize: 1000,
      venueMic: 'XEEE',
    } as FuturesContract;
    const optionTrade = () => {
      const values = {
        ...emptyTrade('2026-10-10'),
        productFamily: 'EMISSION_ALLOWANCE' as const,
        instrumentType: 'OPTION' as const,
        bookId: 'b1',
        counterpartyId: 'c1',
      };
      values.legs[0] = {
        ...withUnderlying(values.legs[0]!, contract),
        side: 'BUY',
        optionType: 'PUT',
        strike: '70.50',
        expiryDate: '2027-12-10',
        quantity: '5000',
        price: '3.25',
      };
      return values;
    };

    it('sends the option terms, no deliveries and no futures contract', () => {
      const leg = toCaptureTrade(optionTrade(), 'e1').legs[0];
      expect(JSON.parse(JSON.stringify(leg))).toMatchObject({
        side: 'BUY',
        allowanceProductCode: 'EUA',
        priceType: 'FIXED',
        price: '3.25',
        futuresContractCode: null,
        option: { optionType: 'PUT', strike: '70.50', expiryDate: '2027-12-10', underlyingContractCode: 'EUA-DEC27' },
        deliveries: [],
      });
    });

    it('leaves the option out of other instruments', () => {
      const values = { ...optionTrade(), instrumentType: 'FORWARD' as const };
      const leg = toCaptureTrade(values, 'e1').legs[0];
      expect('option' in JSON.parse(JSON.stringify(leg))).toBe(false);
      expect(leg?.deliveries).toHaveLength(1);
    });

    it('takes product and currency from the underlying and keeps the premium fixed', () => {
      const leg = withUnderlying(
        { ...emptyTrade('2026-10-10').legs[0]!, priceType: 'INDEX', priceIndex: 'X', priceCurrency: 'SEK' },
        contract,
      );
      expect(leg).toMatchObject({
        underlyingContractCode: 'EUA-DEC27',
        allowanceProductCode: 'EUA',
        priceCurrency: 'EUR',
        priceType: 'FIXED',
        priceIndex: '',
        futuresContractCode: '',
      });
    });

    it('accepts a complete option and asks for no delivery schedule', () => {
      const values = optionTrade();
      values.legs[0]!.deliveries[0]!.quantity = '';
      expect(validateTrade(values, { amend: false, intercompany: false, contracts: [contract] })).toEqual({});
    });

    it('requires an underlying, a positive strike and an expiry', () => {
      const values = optionTrade();
      Object.assign(values.legs[0]!, { underlyingContractCode: '', strike: '0', expiryDate: '' });
      expect(validateTrade(values, { amend: false, intercompany: false })).toMatchObject({
        'legs.0.underlyingContractCode': 'Required',
        'legs.0.strike': 'A positive number',
        'legs.0.expiryDate': 'Required',
      });
      values.legs[0]!.strike = '';
      expect(validateTrade(values, { amend: false, intercompany: false })['legs.0.strike']).toBe('A positive number');
    });

    it('checks the expiry against the trade date and the last trading day, and the quantity against the lot', () => {
      const values = optionTrade();
      const check = () => validateTrade(values, { amend: false, intercompany: false, contracts: [contract] });
      values.legs[0]!.expiryDate = '2026-10-09';
      expect(check()['legs.0.expiryDate']).toMatch(/before the trade date/);
      values.legs[0]!.expiryDate = '2026-10-10';
      expect(check()['legs.0.expiryDate']).toBeUndefined();
      values.legs[0]!.expiryDate = '2027-12-18';
      expect(check()['legs.0.expiryDate']).toMatch(/2027-12-17/);
      values.legs[0]!.expiryDate = '2027-12-17';
      expect(check()['legs.0.expiryDate']).toBeUndefined();
      values.legs[0]!.quantity = '5500';
      expect(check()['legs.0.quantity']).toBe('Whole lots of 1000 t');
    });

    it('counts whole lots without floating point', () => {
      expect(wholeLots('5000', 1000)).toBe(true);
      expect(wholeLots('5000.000', 1000)).toBe(true);
      expect(wholeLots('5000.5', 1000)).toBe(false);
      expect(wholeLots('999999999999999999000', 1000)).toBe(true);
      expect(wholeLots('1500', 1000)).toBe(false);
    });

    it('offers only exchanges and clearing houses for an option', () => {
      const list = [party('CP-A', 'EXTERNAL'), party('CP-ICE', 'EXCHANGE'), party('CP-CCP', 'CLEARING_HOUSE')];
      expect(counterpartiesFor(list, 'OPTION').map((c) => c.code)).toEqual(['CP-ICE', 'CP-CCP']);
    });

    it('fills the form from an existing option trade and sends the terms back on amendment', () => {
      const trade = {
        id: 't1',
        bookId: 'b1',
        counterpartyId: 'c1',
        productFamily: 'EMISSION_ALLOWANCE',
        instrumentType: 'OPTION',
        tradeDate: '2026-10-01',
        trader: 'trader',
        version: 1,
        regulatory: null,
        legs: [
          {
            id: 'l1',
            legNo: 1,
            side: 'BUY',
            quantity: 5000,
            unit: 'TCO2E',
            price: 3.25,
            priceCurrency: 'EUR',
            priceType: 'FIXED',
            allowanceProductCode: 'EUA',
            option: { optionType: 'CALL', strike: 72, expiryDate: '2027-12-10', underlyingContractCode: 'EUA-DEC27' },
            deliveries: [],
          },
        ],
      } as unknown as TradeView;
      const values = fromTrade(trade);
      expect(values.legs[0]).toMatchObject({
        optionType: 'CALL',
        strike: '72',
        expiryDate: '2027-12-10',
        underlyingContractCode: 'EUA-DEC27',
        deliveries: [],
      });
      values.reason = 'strike correction';
      values.legs[0]!.strike = '72.5';
      expect(JSON.parse(JSON.stringify(toAmendTrade(values, 1)))).toMatchObject({
        legs: [{ option: { optionType: 'CALL', strike: '72.5', underlyingContractCode: 'EUA-DEC27' }, deliveries: [] }],
      });
    });
  });
});
