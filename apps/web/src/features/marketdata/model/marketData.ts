import type { Body, Schema } from '@/shared/api/types';

export type InstrumentKind = Schema<'InstrumentView'>['kind'];
export type QuoteType = Schema<'CreatePriceIndex'>['quoteType'];
export type FixingRule = Schema<'CreatePriceIndex'>['fixingRule'];
export type ImportFormat = Schema<'ImportFormatView'>;

export const INSTRUMENT_KINDS: InstrumentKind[] = [
  'EUA_SPOT',
  'EUA_FUTURE',
  'EUA_FORWARD',
  'GOO_VINTAGE',
  'INDEX',
  'RATE',
];
export const QUOTE_TYPES: QuoteType[] = ['SETTLEMENT', 'MID', 'BID', 'ASK', 'CLOSE', 'FIXING'];
export const FIXING_RULES: FixingRule[] = ['ON_DATE', 'PRIOR_DAY', 'MONTH_AVERAGE'];

/** Codes the services accept for instruments, curves and indices. */
export const CODE_PATTERN = /^[A-Za-z0-9_]+$/;

export interface InstrumentForm {
  code: string;
  kind: InstrumentKind;
  description: string;
  allowanceProductCode: string;
  futuresContractCode: string;
  bucketCode: string;
  productionYear: string;
  currency: string;
  unit: string;
}

export interface InstrumentFields {
  allowanceProduct: boolean;
  futuresContract: boolean;
  bucket: boolean;
  productionYear: boolean;
  unit: boolean;
  /** A future takes its currency from the contract. */
  currency: boolean;
}

/** Which fields a kind needs, mirroring the service: allowances name a product, futures a contract, GoO a vintage. */
export function instrumentFields(kind: InstrumentKind): InstrumentFields {
  return {
    allowanceProduct: kind === 'EUA_SPOT' || kind === 'EUA_FORWARD',
    futuresContract: kind === 'EUA_FUTURE',
    bucket: kind === 'GOO_VINTAGE',
    productionYear: kind === 'GOO_VINTAGE',
    unit: kind === 'INDEX' || kind === 'RATE',
    currency: kind !== 'EUA_FUTURE',
  };
}

export function emptyInstrument(): InstrumentForm {
  return {
    code: '',
    kind: 'GOO_VINTAGE',
    description: '',
    allowanceProductCode: '',
    futuresContractCode: '',
    bucketCode: '',
    productionYear: '',
    currency: 'EUR',
    unit: '',
  };
}

/** Field errors as i18n keys. */
export function validateInstrument(form: InstrumentForm): Record<string, string> {
  const errors: Record<string, string> = {};
  const fields = instrumentFields(form.kind);
  if (!CODE_PATTERN.test(form.code.trim())) errors.code = 'marketData.codeHint';
  if (!form.description.trim()) errors.description = 'marketData.errors.required';
  if (fields.allowanceProduct && !form.allowanceProductCode) errors.allowanceProductCode = 'marketData.errors.required';
  if (fields.futuresContract && !form.futuresContractCode) errors.futuresContractCode = 'marketData.errors.required';
  if (fields.bucket && !form.bucketCode) errors.bucketCode = 'marketData.errors.required';
  if (fields.productionYear && !/^\d{4}$/.test(form.productionYear.trim())) {
    errors.productionYear = 'marketData.errors.year';
  }
  if (fields.unit && !form.unit.trim()) errors.unit = 'marketData.errors.required';
  if (fields.currency && !/^[A-Za-z]{3}$/.test(form.currency.trim())) errors.currency = 'marketData.errors.currency';
  return errors;
}

/** The request: only the fields the kind uses, codes upper case. */
export function toCreateInstrument(form: InstrumentForm): Body<'CreateInstrument'> {
  const fields = instrumentFields(form.kind);
  return {
    code: form.code.trim().toUpperCase(),
    kind: form.kind,
    description: form.description.trim(),
    allowanceProductCode: fields.allowanceProduct ? form.allowanceProductCode : null,
    futuresContractCode: fields.futuresContract ? form.futuresContractCode : null,
    bucketCode: fields.bucket ? form.bucketCode : null,
    productionYear: fields.productionYear ? Number(form.productionYear.trim()) : null,
    currency: fields.currency ? form.currency.trim().toUpperCase() : 'EUR',
    unit: fields.unit ? form.unit.trim().toUpperCase() : null,
  };
}

/** What the import format editor shows: everything but the code (edited apart) and the version (the server's). */
export function importFormatJson(format?: ImportFormat): Record<string, unknown> {
  if (!format) {
    return {
      kind: 'QUOTES_LONG_CSV',
      description: '',
      source: '',
      delimiter: ',',
      skipLines: 0,
      dateFormat: 'yyyy-MM-dd',
      decimalSeparator: '.',
      columnMapping: { date: 'Date', instrument: 'Instrument', value: 'Value' },
      constants: { quoteType: 'CLOSE', currency: 'EUR' },
      active: true,
    };
  }
  const json: Record<string, unknown> = { ...format };
  delete json.code;
  delete json.version;
  return json;
}

/**
 * The upsert request from the edited JSON; the code from its own field wins over one typed in the JSON. The server
 * checks the content (kind, date format and the rest).
 */
export function toUpsertImportFormat(code: string, json: Record<string, unknown>): Body<'UpsertImportFormat'> {
  const body: Record<string, unknown> = { ...json, code: code.trim().toUpperCase() };
  delete body.version;
  return body as unknown as Body<'UpsertImportFormat'>;
}
