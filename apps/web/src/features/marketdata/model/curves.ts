import { decimal, type Body, type Schema } from '@/shared/api/types';
import { CODE_PATTERN, type QuoteType } from './marketData';

export type Curve = Schema<'CurveView'>;
export type CurveKind = Curve['kind'];

export const CURVE_KINDS: CurveKind[] = ['EUA_FORWARD', 'GOO_VINTAGE'];
/** The service's default quote types for a new curve. */
export const DEFAULT_CURVE_QUOTES: QuoteType[] = ['SETTLEMENT', 'CLOSE', 'MID'];

export interface CurveForm {
  code: string;
  kind: CurveKind;
  description: string;
  allowanceProductCode: string;
  bucketCode: string;
  currency: string;
  quoteTypes: QuoteType[];
  stalenessDays: string;
  proxyCurveCode: string;
  proxySpread: string;
}

export function emptyCurve(): CurveForm {
  return {
    code: '',
    kind: 'GOO_VINTAGE',
    description: '',
    allowanceProductCode: 'EUA',
    bucketCode: '',
    currency: 'EUR',
    quoteTypes: DEFAULT_CURVE_QUOTES,
    stalenessDays: '5',
    proxyCurveCode: '',
    proxySpread: '0',
  };
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

/** Field errors as i18n keys; mirrors the service (a forward names a product, a vintage curve a bucket). */
export function validateCurve(form: CurveForm): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!CODE_PATTERN.test(form.code.trim())) errors.code = 'marketData.codeHint';
  if (!form.description.trim()) errors.description = 'marketData.errors.required';
  if (form.kind === 'EUA_FORWARD' && !form.allowanceProductCode)
    errors.allowanceProductCode = 'marketData.errors.required';
  if (form.kind === 'GOO_VINTAGE' && !form.bucketCode) errors.bucketCode = 'marketData.errors.required';
  if (!/^[A-Za-z]{3}$/.test(form.currency.trim())) errors.currency = 'marketData.errors.currency';
  if (form.quoteTypes.length === 0) errors.quoteTypes = 'marketData.errors.required';
  if (!/^\d+$/.test(form.stalenessDays.trim())) errors.stalenessDays = 'marketData.errors.whole';
  if (!DECIMAL.test(form.proxySpread.trim())) errors.proxySpread = 'marketData.errors.decimal';
  return errors;
}

/** The request; the proxy spread stays a decimal string so no precision is lost (see `decimal()`). */
export function toCreateCurve(form: CurveForm): Body<'CreateCurve'> {
  return {
    code: form.code.trim().toUpperCase(),
    kind: form.kind,
    description: form.description.trim(),
    allowanceProductCode: form.kind === 'EUA_FORWARD' ? form.allowanceProductCode : null,
    bucketCode: form.kind === 'GOO_VINTAGE' ? form.bucketCode : null,
    currency: form.currency.trim().toUpperCase(),
    quoteTypes: form.quoteTypes,
    stalenessDays: Number(form.stalenessDays.trim()),
    proxyCurveCode: form.proxyCurveCode || null,
    proxySpread: decimal(form.proxySpread),
  };
}

/** A proxy must be another curve of the same kind. */
export function proxyCandidates(curves: readonly Curve[], kind: CurveKind, code: string): Curve[] {
  return curves.filter((curve) => curve.kind === kind && curve.code !== code.trim().toUpperCase());
}

export type LookupKind = 'forward' | 'vintage';

export interface PriceLookupForm {
  kind: LookupKind;
  product: string;
  delivery: string;
  bucket: string;
  year: string;
  asOf: string;
}

export type PriceLookup =
  | { kind: 'forward'; query: { product: string; delivery: string; asOf: string } }
  | { kind: 'vintage'; query: { bucket: string; year: number; asOf: string } };

/** The lookup to send, or null while the form is incomplete. */
export function priceLookup(form: PriceLookupForm): PriceLookup | null {
  if (!form.asOf) return null;
  if (form.kind === 'forward') {
    if (!form.product || !form.delivery) return null;
    return { kind: 'forward', query: { product: form.product, delivery: form.delivery, asOf: form.asOf } };
  }
  if (!form.bucket || !/^\d{4}$/.test(form.year.trim())) return null;
  return { kind: 'vintage', query: { bucket: form.bucket, year: Number(form.year.trim()), asOf: form.asOf } };
}

/** Products with a forward curve and buckets with a vintage curve: what a lookup can find. */
export function lookupOptions(curves: readonly Curve[]): { products: string[]; buckets: string[] } {
  const unique = (values: (string | null | undefined)[]) =>
    [...new Set(values.filter((value): value is string => Boolean(value)))].sort();
  return {
    products: unique(curves.filter((c) => c.kind === 'EUA_FORWARD').map((c) => c.allowanceProductCode)),
    buckets: unique(curves.filter((c) => c.kind === 'GOO_VINTAGE').map((c) => c.bucketCode)),
  };
}
