import { decimal, type Body, type Schema } from '@/shared/api/types';
import { CODE_PATTERN, type QuoteType } from './marketData';

export type Curve = Schema<'CurveView'>;
export type CurveKind = Curve['kind'];

export const CURVE_KINDS: CurveKind[] = ['EUA_FORWARD', 'GOO_VINTAGE', 'DISCOUNT', 'EUA_VOLATILITY'];

/** Curves of one allowance product: forward prices and option volatility. */
export const forProduct = (kind: CurveKind) => kind === 'EUA_FORWARD' || kind === 'EUA_VOLATILITY';
export type DayCount = NonNullable<Schema<'CurveView'>['dayCount']>;
export const DAY_COUNTS: DayCount[] = ['ACT_360', 'ACT_365F'];
/** The service's default quote types for a new curve. */
export const DEFAULT_CURVE_QUOTES: QuoteType[] = ['SETTLEMENT', 'CLOSE', 'MID'];

export interface CurveForm {
  code: string;
  kind: CurveKind;
  description: string;
  allowanceProductCode: string;
  bucketCode: string;
  currency: string;
  /** Discount curves only; empty lets the service choose (ACT/360 for EUR, ACT/365F otherwise). */
  dayCount: DayCount | '';
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
    dayCount: '',
    quoteTypes: DEFAULT_CURVE_QUOTES,
    stalenessDays: '5',
    proxyCurveCode: '',
    proxySpread: '0',
  };
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

/** Field errors as i18n keys; mirrors the service (a forward names a product, a vintage curve a bucket, a discount curve a currency only). */
export function validateCurve(form: CurveForm): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!CODE_PATTERN.test(form.code.trim())) errors.code = 'marketData.codeHint';
  if (!form.description.trim()) errors.description = 'marketData.errors.required';
  if (forProduct(form.kind) && !form.allowanceProductCode) errors.allowanceProductCode = 'marketData.errors.required';
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
    allowanceProductCode: forProduct(form.kind) ? form.allowanceProductCode : null,
    bucketCode: form.kind === 'GOO_VINTAGE' ? form.bucketCode : null,
    currency: form.currency.trim().toUpperCase(),
    dayCount: form.kind === 'DISCOUNT' && form.dayCount ? form.dayCount : null,
    // A volatility curve reads volatility quotes only.
    quoteTypes: form.kind === 'EUA_VOLATILITY' ? ['VOLATILITY'] : form.quoteTypes,
    stalenessDays: Number(form.stalenessDays.trim()),
    proxyCurveCode: form.proxyCurveCode || null,
    proxySpread: decimal(form.proxySpread),
  };
}

/** A proxy must be another curve of the same kind. */
export function proxyCandidates(curves: readonly Curve[], kind: CurveKind, code: string): Curve[] {
  return curves.filter((curve) => curve.kind === kind && curve.code !== code.trim().toUpperCase());
}

/** The day count a discount curve gets when none is chosen: the service's default. */
export function defaultDayCount(currency: string): DayCount {
  return currency.trim().toUpperCase() === 'EUR' ? 'ACT_360' : 'ACT_365F';
}

export type LookupKind = 'forward' | 'vintage' | 'discount' | 'volatility';

export interface PriceLookupForm {
  kind: LookupKind;
  product: string;
  delivery: string;
  bucket: string;
  year: string;
  currency: string;
  payDate: string;
  asOf: string;
}

export type PriceLookup =
  | { kind: 'forward'; query: { product: string; delivery: string; asOf: string } }
  | { kind: 'vintage'; query: { bucket: string; year: number; asOf: string } }
  | { kind: 'discount'; query: { currency: string; payDate: string; asOf: string } }
  | { kind: 'volatility'; query: { product: string; expiry: string; asOf: string } };

/** The lookup to send, or null while the form is incomplete. */
export function priceLookup(form: PriceLookupForm): PriceLookup | null {
  if (!form.asOf) return null;
  if (form.kind === 'discount') {
    const currency = form.currency.trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(currency) || !form.payDate) return null;
    return { kind: 'discount', query: { currency, payDate: form.payDate, asOf: form.asOf } };
  }
  if (form.kind === 'forward') {
    if (!form.product || !form.delivery) return null;
    return { kind: 'forward', query: { product: form.product, delivery: form.delivery, asOf: form.asOf } };
  }
  // An option's volatility: the delivery field holds the expiry.
  if (form.kind === 'volatility') {
    if (!form.product || !form.delivery) return null;
    return { kind: 'volatility', query: { product: form.product, expiry: form.delivery, asOf: form.asOf } };
  }
  if (!form.bucket || !/^\d{4}$/.test(form.year.trim())) return null;
  return { kind: 'vintage', query: { bucket: form.bucket, year: Number(form.year.trim()), asOf: form.asOf } };
}

/**
 * Products with a forward curve, buckets with a vintage curve, currencies with a discount curve and products with a
 * volatility curve: what a lookup can find.
 */
export function lookupOptions(curves: readonly Curve[]): {
  products: string[];
  buckets: string[];
  currencies: string[];
  volatilityProducts: string[];
} {
  const unique = (values: (string | null | undefined)[]) =>
    [...new Set(values.filter((value): value is string => Boolean(value)))].sort();
  return {
    products: unique(curves.filter((c) => c.kind === 'EUA_FORWARD').map((c) => c.allowanceProductCode)),
    buckets: unique(curves.filter((c) => c.kind === 'GOO_VINTAGE').map((c) => c.bucketCode)),
    currencies: unique(curves.filter((c) => c.kind === 'DISCOUNT').map((c) => c.currency)),
    volatilityProducts: unique(curves.filter((c) => c.kind === 'EUA_VOLATILITY').map((c) => c.allowanceProductCode)),
  };
}
