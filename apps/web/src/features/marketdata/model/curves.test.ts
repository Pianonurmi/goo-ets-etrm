import {
  defaultDayCount,
  emptyCurve,
  lookupOptions,
  priceLookup,
  proxyCandidates,
  toCreateCurve,
  validateCurve,
  type Curve,
} from './curves';

function curve(code: string, kind: Curve['kind'], extra: Partial<Curve> = {}): Curve {
  return {
    code,
    kind,
    description: code,
    currency: 'EUR',
    quoteTypes: ['CLOSE'],
    stalenessDays: 5,
    proxySpread: 0,
    active: true,
    ...extra,
  };
}

describe('new curves', () => {
  it('wants a bucket for a vintage curve and a product for a forward', () => {
    expect(validateCurve({ ...emptyCurve(), code: 'GOO_X', description: 'X' })).toEqual({
      bucketCode: 'marketData.errors.required',
    });
    expect(
      validateCurve({
        ...emptyCurve(),
        kind: 'EUA_FORWARD',
        allowanceProductCode: '',
        code: 'EUA X',
        description: 'X',
      }),
    ).toEqual({ code: 'marketData.codeHint', allowanceProductCode: 'marketData.errors.required' });
  });

  it('checks the numbers and the quote types', () => {
    const errors = validateCurve({
      ...emptyCurve(),
      code: 'GOO_X',
      description: 'X',
      bucketCode: 'EU_WIND',
      quoteTypes: [],
      stalenessDays: '1.5',
      proxySpread: 'abc',
    });
    expect(errors).toEqual({
      quoteTypes: 'marketData.errors.required',
      stalenessDays: 'marketData.errors.whole',
      proxySpread: 'marketData.errors.decimal',
    });
  });

  it('sends the field of its kind and the spread as a decimal string', () => {
    const body = toCreateCurve({
      ...emptyCurve(),
      code: 'goo_x',
      description: ' Wind ',
      bucketCode: 'EU_WIND',
      allowanceProductCode: 'EUA',
      proxyCurveCode: 'GOO_EU_WIND',
      proxySpread: ' -0.015 ',
    });
    expect(body).toEqual({
      code: 'GOO_X',
      kind: 'GOO_VINTAGE',
      description: 'Wind',
      allowanceProductCode: null,
      bucketCode: 'EU_WIND',
      currency: 'EUR',
      dayCount: null,
      quoteTypes: ['SETTLEMENT', 'CLOSE', 'MID'],
      stalenessDays: 5,
      proxyCurveCode: 'GOO_EU_WIND',
      proxySpread: '-0.015',
    });
  });

  it('needs neither product nor bucket for a discount curve, only a currency', () => {
    const form = { ...emptyCurve(), kind: 'DISCOUNT' as const, code: 'EUR_OIS', description: 'EUR OIS' };
    expect(validateCurve(form)).toEqual({});
    expect(validateCurve({ ...form, currency: 'EU' })).toEqual({ currency: 'marketData.errors.currency' });
  });

  it('sends a discount curve with its currency, its day count and no product or bucket', () => {
    const form = {
      ...emptyCurve(),
      kind: 'DISCOUNT' as const,
      code: 'eur_ois',
      description: 'EUR OIS',
      allowanceProductCode: 'EUA',
      bucketCode: 'EU_WIND',
      currency: 'eur',
    };
    expect(toCreateCurve(form)).toMatchObject({
      code: 'EUR_OIS',
      kind: 'DISCOUNT',
      allowanceProductCode: null,
      bucketCode: null,
      currency: 'EUR',
      dayCount: null,
    });
    expect(toCreateCurve({ ...form, currency: 'SEK', dayCount: 'ACT_365F' }).dayCount).toBe('ACT_365F');
  });

  it('leaves the day count out for other kinds', () => {
    expect(toCreateCurve({ ...emptyCurve(), bucketCode: 'EU_WIND', dayCount: 'ACT_360' }).dayCount).toBeNull();
  });

  it('names the default day count of a currency', () => {
    expect(defaultDayCount('eur')).toBe('ACT_360');
    expect(defaultDayCount('SEK')).toBe('ACT_365F');
  });

  it('offers curves of the same kind as proxies', () => {
    const curves = [curve('EUA_FWD', 'EUA_FORWARD'), curve('GOO_A', 'GOO_VINTAGE'), curve('GOO_B', 'GOO_VINTAGE')];
    expect(proxyCandidates(curves, 'GOO_VINTAGE', 'goo_a').map((c) => c.code)).toEqual(['GOO_B']);
  });
});

describe('price lookup', () => {
  const base = {
    kind: 'forward' as const,
    product: 'EUA',
    delivery: '2027-12-15',
    bucket: '',
    year: '',
    currency: 'EUR',
    payDate: '2027-03-31',
    asOf: '2026-10-09',
  };

  it('builds a forward lookup', () => {
    expect(priceLookup(base)).toEqual({
      kind: 'forward',
      query: { product: 'EUA', delivery: '2027-12-15', asOf: '2026-10-09' },
    });
  });

  it('builds a vintage lookup with a four-digit year', () => {
    expect(priceLookup({ ...base, kind: 'vintage', bucket: 'NORDIC_HYDRO', year: '2026' })).toEqual({
      kind: 'vintage',
      query: { bucket: 'NORDIC_HYDRO', year: 2026, asOf: '2026-10-09' },
    });
    expect(priceLookup({ ...base, kind: 'vintage', bucket: 'NORDIC_HYDRO', year: '26' })).toBeNull();
  });

  it('builds a discount factor lookup with an upper-case currency and the payment date', () => {
    expect(priceLookup({ ...base, kind: 'discount', currency: ' sek ' })).toEqual({
      kind: 'discount',
      query: { currency: 'SEK', payDate: '2027-03-31', asOf: '2026-10-09' },
    });
    expect(priceLookup({ ...base, kind: 'discount', payDate: '' })).toBeNull();
    expect(priceLookup({ ...base, kind: 'discount', currency: 'EU' })).toBeNull();
  });

  it('builds a volatility lookup for an expiry', () => {
    expect(priceLookup({ ...base, kind: 'volatility', delivery: '2026-12-14' })).toEqual({
      kind: 'volatility',
      query: { product: 'EUA', expiry: '2026-12-14', asOf: '2026-10-09' },
    });
    expect(priceLookup({ ...base, kind: 'volatility', delivery: '' })).toBeNull();
  });

  it('waits for the date', () => {
    expect(priceLookup({ ...base, asOf: '' })).toBeNull();
  });

  it('lists the products, buckets and currencies that have curves', () => {
    const curves = [
      curve('EUA_FWD', 'EUA_FORWARD', { allowanceProductCode: 'EUA' }),
      curve('GOO_B', 'GOO_VINTAGE', { bucketCode: 'NORDIC_WIND' }),
      curve('GOO_A', 'GOO_VINTAGE', { bucketCode: 'EU_SOLAR' }),
      curve('GOO_C', 'GOO_VINTAGE', { bucketCode: 'EU_SOLAR' }),
      curve('SEK_OIS', 'DISCOUNT', { currency: 'SEK' }),
      curve('EUR_OIS', 'DISCOUNT'),
      curve('EUA_VOL', 'EUA_VOLATILITY', { allowanceProductCode: 'EUA' }),
    ];
    expect(lookupOptions(curves)).toEqual({
      products: ['EUA'],
      buckets: ['EU_SOLAR', 'NORDIC_WIND'],
      currencies: ['EUR', 'SEK'],
      volatilityProducts: ['EUA'],
    });
  });
});
