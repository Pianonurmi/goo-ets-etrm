import type { Schema } from '@/shared/api/types';
import type { LifecycleStep } from '@/shared/components/LifecycleTimeline';

export type Limit = Schema<'RiskLimitView'>;
export type LimitType = Limit['type'];
export type LimitScope = Schema<'LimitScope'>;
export type UtilisationStatus = Schema<'LimitUtilisationView'>['status'];

export const LIMIT_TYPES: LimitType[] = [
  'CREDIT_EXPOSURE',
  'POSITION_NET',
  'TENOR_MAX_MONTHS',
  'STOP_LOSS_MTD',
  'COMPLIANCE_SHORT',
  'GOO_CONCENTRATION',
  'GOO_EXPIRY',
  'GOO_BASIS',
  'MARGIN_LIQUIDITY',
];

export interface LimitFields {
  counterparty: 'required' | 'hidden';
  productFamily: 'required' | 'optional' | 'hidden';
  /** The product bucket a GoO limit is scoped to. */
  bucket: 'hidden' | 'required' | 'optional';
  /** Days ahead a GoO expiry limit looks. */
  horizon: 'hidden' | 'required';
  /** Whether a GoO limit can be narrowed to one vintage (production year). */
  vintage: boolean;
  /** Whether the limit can be set on one book; GoO limits apply to the whole entity. */
  book: boolean;
  /** `required`: always a money amount; `optional`: MWh, or EUR when chosen; `hidden`: no currency. */
  currency: 'required' | 'optional' | 'hidden';
}

export const HORIZON_DAYS = { min: 1, max: 730, initial: 90 };

/** The years a GoO limit's vintage may name (mirrors the service's validation). */
export const VINTAGE_YEARS = { min: 2000, max: 2100 };

/** Which scope fields and amount unit a limit type uses (mirrors the service's validation). */
export const LIMIT_FIELDS: Record<LimitType, LimitFields> = {
  CREDIT_EXPOSURE: {
    counterparty: 'required',
    productFamily: 'hidden',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: false,
    book: false,
    currency: 'required',
  },
  POSITION_NET: {
    counterparty: 'hidden',
    productFamily: 'required',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: false,
    book: true,
    currency: 'hidden',
  },
  TENOR_MAX_MONTHS: {
    counterparty: 'hidden',
    productFamily: 'optional',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: false,
    book: true,
    currency: 'hidden',
  },
  STOP_LOSS_MTD: {
    counterparty: 'hidden',
    productFamily: 'optional',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: false,
    book: true,
    currency: 'required',
  },
  COMPLIANCE_SHORT: {
    counterparty: 'hidden',
    productFamily: 'hidden',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: false,
    book: false,
    currency: 'hidden',
  },
  GOO_CONCENTRATION: {
    counterparty: 'hidden',
    productFamily: 'hidden',
    bucket: 'required',
    horizon: 'hidden',
    vintage: true,
    book: false,
    currency: 'optional',
  },
  GOO_EXPIRY: {
    counterparty: 'hidden',
    productFamily: 'hidden',
    bucket: 'optional',
    horizon: 'required',
    vintage: true,
    book: false,
    currency: 'hidden',
  },
  GOO_BASIS: {
    counterparty: 'hidden',
    productFamily: 'hidden',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: true,
    book: false,
    currency: 'optional',
  },
  MARGIN_LIQUIDITY: {
    counterparty: 'hidden',
    productFamily: 'hidden',
    bucket: 'hidden',
    horizon: 'hidden',
    vintage: false,
    book: false,
    currency: 'required',
  },
};

export interface ScopeNames {
  counterparty: (id: string) => string | undefined;
  book: (id: string) => string | undefined;
  /** The wording for an expiry horizon, e.g. "expiring within 90 days". */
  horizon: (days: number) => string;
  /** The wording for a vintage, e.g. "vintage 2025". */
  vintage: (year: number) => string;
}

/** A short human description of what a limit applies to, e.g. "Nordvind · book DEMO-FI-TRD · EUA 2026" or "NORDIC_HYDRO · vintage 2025 · expiring within 90 days". */
export function scopeLabel(scope: LimitScope, names: ScopeNames): string {
  const parts: string[] = [];
  if (scope.counterpartyId) parts.push(names.counterparty(scope.counterpartyId) ?? scope.counterpartyId);
  if (scope.bookId) parts.push(names.book(scope.bookId) ?? scope.bookId);
  if (scope.bucketCode) parts.push(scope.bucketCode);
  if (scope.productionYear != null) parts.push(names.vintage(scope.productionYear));
  if (scope.horizonDays != null) parts.push(names.horizon(scope.horizonDays));
  if (scope.productFamily) parts.push(scope.productFamily === 'GUARANTEE_OF_ORIGIN' ? 'GoO' : 'EUA');
  const allowance = [scope.allowanceProductCode, scope.complianceYear].filter((part) => part != null).join(' ');
  if (allowance) parts.push(allowance);
  return parts.join(' · ');
}

/** The unit a limit's amount is expressed in. */
export function limitUnit(limit: Pick<Limit, 'type' | 'currency' | 'scope'>): string {
  switch (limit.type) {
    case 'CREDIT_EXPOSURE':
    case 'STOP_LOSS_MTD':
    case 'MARGIN_LIQUIDITY':
      return limit.currency ?? '';
    case 'GOO_CONCENTRATION':
    case 'GOO_BASIS':
      return limit.currency || 'MWh';
    case 'GOO_EXPIRY':
      return 'MWh';
    case 'TENOR_MAX_MONTHS':
      return 'months';
    case 'COMPLIANCE_SHORT':
      return 't';
    case 'POSITION_NET':
      return limit.scope.productFamily === 'GUARANTEE_OF_ORIGIN' ? 'MWh' : 't';
  }
}

export function utilisationColor(status: UtilisationStatus | undefined): string {
  switch (status) {
    case 'BREACH':
      return 'red';
    case 'WARNING':
      return 'orange';
    case 'OK':
      return 'teal';
    default:
      return 'gray';
  }
}

/** A limit's lifecycle: requested → approved by a second analyst and active → retired when replaced or ended. */
export function limitSteps(
  limit: Pick<Limit, 'status' | 'requestedBy' | 'requestedAt' | 'approvedBy' | 'approvedAt' | 'validTo'>,
  t: (key: string) => string,
): LifecycleStep[] {
  const requested: LifecycleStep = {
    label: t('risk.lifecycle.requested'),
    state: 'done',
    by: limit.requestedBy,
    at: limit.requestedAt,
  };
  const decided = { by: limit.approvedBy, at: limit.approvedAt };
  switch (limit.status) {
    case 'PENDING_APPROVAL':
      return [requested, { label: t('risk.lifecycle.approval'), state: 'current' }];
    case 'REJECTED':
      return [requested, { label: t('risk.lifecycle.rejected'), state: 'failed', ...decided }];
    case 'ACTIVE':
      return [requested, { label: t('risk.lifecycle.active'), state: 'current', ...decided }];
    case 'RETIRED':
      return [
        requested,
        { label: t('risk.lifecycle.active'), state: 'done', ...decided },
        { label: t('risk.lifecycle.retired'), state: 'done', detail: limit.validTo },
      ];
  }
}

/** Utilisation is a percentage from the server; the bar is capped at 100 so a breach does not overflow. */
export function utilisationBar(utilisation: number | undefined): number {
  if (utilisation === undefined || Number.isNaN(utilisation)) return 0;
  return Math.max(0, Math.min(100, utilisation));
}
