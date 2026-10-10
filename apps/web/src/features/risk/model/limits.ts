import type { Schema } from '@/shared/api/types';

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
];

/** Which scope fields and amount unit a limit type uses (mirrors the service's validation). */
export const LIMIT_FIELDS: Record<
  LimitType,
  { counterparty: 'required' | 'hidden'; productFamily: 'required' | 'optional' | 'hidden'; currency: boolean }
> = {
  CREDIT_EXPOSURE: { counterparty: 'required', productFamily: 'hidden', currency: true },
  POSITION_NET: { counterparty: 'hidden', productFamily: 'required', currency: false },
  TENOR_MAX_MONTHS: { counterparty: 'hidden', productFamily: 'optional', currency: false },
  STOP_LOSS_MTD: { counterparty: 'hidden', productFamily: 'optional', currency: true },
  COMPLIANCE_SHORT: { counterparty: 'hidden', productFamily: 'hidden', currency: false },
};

export interface ScopeNames {
  counterparty: (id: string) => string | undefined;
  book: (id: string) => string | undefined;
}

/** A short human description of what a limit applies to, e.g. "Nordvind · book DEMO-FI-TRD · EUA 2026". */
export function scopeLabel(scope: LimitScope, names: ScopeNames): string {
  const parts: string[] = [];
  if (scope.counterpartyId) parts.push(names.counterparty(scope.counterpartyId) ?? scope.counterpartyId);
  if (scope.bookId) parts.push(names.book(scope.bookId) ?? scope.bookId);
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
      return limit.currency ?? '';
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

/** Utilisation is a percentage from the server; the bar is capped at 100 so a breach does not overflow. */
export function utilisationBar(utilisation: number | undefined): number {
  if (utilisation === undefined || Number.isNaN(utilisation)) return 0;
  return Math.max(0, Math.min(100, utilisation));
}
