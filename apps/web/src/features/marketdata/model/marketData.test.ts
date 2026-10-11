import {
  emptyInstrument,
  importFormatJson,
  instrumentFields,
  toCreateInstrument,
  toUpsertImportFormat,
  validateInstrument,
  type ImportFormat,
} from './marketData';

describe('instruments', () => {
  it('asks each kind for what the service needs', () => {
    expect(instrumentFields('GOO_VINTAGE')).toMatchObject({ bucket: true, productionYear: true, unit: false });
    expect(instrumentFields('EUA_FUTURE')).toMatchObject({ futuresContract: true, currency: false });
    expect(instrumentFields('EUA_SPOT')).toMatchObject({ allowanceProduct: true, bucket: false });
    expect(instrumentFields('INDEX')).toMatchObject({ unit: true, allowanceProduct: false });
  });

  it('validates a GoO vintage', () => {
    expect(validateInstrument({ ...emptyInstrument(), code: 'goo-x', productionYear: '26' })).toEqual({
      code: 'marketData.codeHint',
      description: 'marketData.errors.required',
      bucketCode: 'marketData.errors.required',
      productionYear: 'marketData.errors.year',
    });
    expect(
      validateInstrument({
        ...emptyInstrument(),
        code: 'GOO_X_2026',
        description: 'X',
        bucketCode: 'NORDIC_HYDRO',
        productionYear: '2026',
      }),
    ).toEqual({});
  });

  it('sends only the fields of the kind, codes upper case', () => {
    const body = toCreateInstrument({
      ...emptyInstrument(),
      code: ' goo_x_2026 ',
      description: ' Nordic hydro 2026 ',
      bucketCode: 'NORDIC_HYDRO',
      productionYear: '2026',
      allowanceProductCode: 'EUA',
      unit: 'mwh',
    });
    expect(body).toEqual({
      code: 'GOO_X_2026',
      kind: 'GOO_VINTAGE',
      description: 'Nordic hydro 2026',
      allowanceProductCode: null,
      futuresContractCode: null,
      bucketCode: 'NORDIC_HYDRO',
      productionYear: 2026,
      currency: 'EUR',
      unit: null,
    });
  });

  it('lets a future take its currency from the contract', () => {
    const body = toCreateInstrument({
      ...emptyInstrument(),
      kind: 'EUA_FUTURE',
      futuresContractCode: 'EUAZ27',
      currency: 'usd',
    });
    expect(body.futuresContractCode).toBe('EUAZ27');
    expect(body.currency).toBe('EUR');
  });
});

describe('import formats', () => {
  const format: ImportFormat = {
    code: 'BROKER_CSV',
    kind: 'QUOTES_LONG_CSV',
    description: 'Broker',
    source: 'BROKER',
    delimiter: ',',
    skipLines: 0,
    dateFormat: 'yyyy-MM-dd',
    decimalSeparator: '.',
    columnMapping: { date: 'Date' },
    constants: {},
    active: true,
    version: 3,
  };

  it('edits everything but the code and the version', () => {
    const json = importFormatJson(format);
    expect(json).not.toHaveProperty('code');
    expect(json).not.toHaveProperty('version');
    expect(json).toMatchObject({ kind: 'QUOTES_LONG_CSV', columnMapping: { date: 'Date' } });
  });

  it('offers a template for a new format', () => {
    expect(importFormatJson()).toMatchObject({ kind: 'QUOTES_LONG_CSV', active: true });
  });

  it('takes the code from its own field', () => {
    const body = toUpsertImportFormat(' new_csv ', { code: 'OTHER', version: 9, kind: 'FX_WIDE_CSV' });
    expect(body).toEqual({ code: 'NEW_CSV', kind: 'FX_WIDE_CSV' });
  });
});
