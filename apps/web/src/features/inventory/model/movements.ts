import dayjs from 'dayjs';
import { decimal, type Body, type Schema } from '@/shared/api/types';

type Account = Schema<'RegistryAccountView'>;
type Registry = Schema<'RegistryView'>;
export type MovementType = Body<'RecordAllowanceMovement'>['type'];

/** Movement types a person records by hand; transfers out and surrenders go through registry instructions. */
export const MOVEMENT_TYPES: MovementType[] = [
  'PURCHASE',
  'FREE_ALLOCATION',
  'TRANSFER_IN',
  'SALE',
  'TRANSFER_OUT',
  'SURRENDER',
  'DELETION',
  'OPENING_BALANCE',
  'CORRECTION',
];

/** Types that add to the holding (the rest take away; a correction carries its own sign). */
const INBOUND: ReadonlySet<MovementType> = new Set(['PURCHASE', 'FREE_ALLOCATION', 'TRANSFER_IN', 'OPENING_BALANCE']);

export interface MovementValues {
  registryAccountId: string | null;
  productCode: string | null;
  type: MovementType;
  quantity: number | '';
  effectiveDate: string;
  reference: string;
  complianceYear: number | '';
  costPerUnit: string;
  costCurrency: string;
}

export function emptyMovement(
  registryAccountId: string | null = null,
  productCode: string | null = null,
  today = dayjs(),
): MovementValues {
  return {
    registryAccountId,
    productCode,
    type: 'PURCHASE',
    quantity: '',
    effectiveDate: today.format('YYYY-MM-DD'),
    reference: '',
    complianceYear: '',
    costPerUnit: '',
    costCurrency: 'EUR',
  };
}

/** The entity's active accounts in an emission allowance registry. */
export function allowanceAccounts(accounts: Account[], registries: Registry[]): Account[] {
  const ets = new Set(
    registries.filter((registry) => registry.registryType === 'EMISSION_ALLOWANCE').map((r) => r.code),
  );
  return accounts.filter((account) => account.active && ets.has(account.registryCode));
}

/** +1 when the movement adds to the holding, -1 when it takes away, 0 for a correction (signed by the user). */
export function direction(type: MovementType): 1 | -1 | 0 {
  if (type === 'CORRECTION') return 0;
  return INBOUND.has(type) ? 1 : -1;
}

/** Whole allowances: positive, or for a correction any non-zero number (negative takes away). */
export function validQuantity(type: MovementType, quantity: number | ''): boolean {
  if (quantity === '' || !Number.isInteger(quantity)) return false;
  return type === 'CORRECTION' ? quantity !== 0 : quantity > 0;
}

export function movementReady(values: MovementValues): boolean {
  return Boolean(
    values.registryAccountId &&
    values.productCode &&
    values.effectiveDate &&
    validQuantity(values.type, values.quantity),
  );
}

export function toMovement(values: MovementValues, holderEntityId: string): Body<'RecordAllowanceMovement'> {
  const cost = values.costPerUnit.trim();
  return {
    holderEntityId,
    registryAccountId: values.registryAccountId ?? '',
    productCode: values.productCode ?? '',
    type: values.type,
    quantity: Number(values.quantity),
    effectiveDate: values.effectiveDate,
    reference: values.reference.trim() || null,
    complianceYear: values.complianceYear === '' ? null : values.complianceYear,
    costPerUnit: cost ? decimal(cost) : null,
    costCurrency: cost ? values.costCurrency.trim().toUpperCase() || null : null,
  };
}
