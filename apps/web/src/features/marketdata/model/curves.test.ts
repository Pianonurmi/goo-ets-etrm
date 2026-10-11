import {
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
      quoteTypes: ['SETTLEMENT', 'CLOSE', 'MID'],
      stalenessDays: 5,
      proxyCurveCode: 'GOO_EU_WIND',
      proxySpread: '-0.015',
    });
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

  it('waits for the date', () => {
    expect(priceLookup({ ...base, asOf: '' })).toBeNull();
  });

  it('lists the products and buckets that have curves', () => {
    const curves = [
      curve('EUA_FWD', 'EUA_FORWARD', { allowanceProductCode: 'EUA' }),
      curve('GOO_B', 'GOO_VINTAGE', { bucketCode: 'NORDIC_WIND' }),
      curve('GOO_A', 'GOO_VINTAGE', { bucketCode: 'EU_SOLAR' }),
      curve('GOO_C', 'GOO_VINTAGE', { bucketCode: 'EU_SOLAR' }),
    ];
    expect(lookupOptions(curves)).toEqual({ products: ['EUA'], buckets: ['EU_SOLAR', 'NORDIC_WIND'] });
  });
});
