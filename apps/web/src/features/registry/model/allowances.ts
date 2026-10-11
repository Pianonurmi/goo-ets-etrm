import { decimal, type Body, type Schema } from '@/shared/api/types';

type Account = Schema<'RegistryAccountView'>;
type Holding = Schema<'AllowanceHoldingView'>;
type Lot = Schema<'LotView'>;
type Purpose = Schema<'CancellationDetails'>['purpose'];

export type TransferMovement = 'TRANSFER_OUT' | 'SALE';

export interface AllowanceTransferValues {
  fromAccountId: string | null;
  productCode: string | null;
  quantity: number | '';
  toAccountId: string | null;
  movementType: TransferMovement;
  unitPrice: string;
  priceCurrency: string;
}

/** Allowances not reserved for another instruction: what a new transfer may take. */
export function freeQuantity(holding: Pick<Holding, 'quantity' | 'reservedQuantity'>): number {
  return Math.max(0, holding.quantity - holding.reservedQuantity);
}

/** Accounts that may send allowances: ours, active and allowed to export. */
export function sourceAccounts(accounts: Account[]): Account[] {
  return accounts.filter((account) => account.active && account.canExport && account.holderType === 'LEGAL_ENTITY');
}

/** Receiving accounts: active, in the same registry as [from] (allowances move within a registry), not [from] itself. */
export function destinationAccounts(accounts: Account[], from: Account | undefined): Account[] {
  if (!from) return [];
  return accounts.filter(
    (account) => account.active && account.registryCode === from.registryCode && account.id !== from.id,
  );
}

/** The holdings of one account that still have allowances to move. */
export function transferableHoldings(holdings: Holding[], accountId: string | null): Holding[] {
  return holdings.filter((holding) => holding.registryAccountId === accountId && freeQuantity(holding) > 0);
}

/** Mirrors `RegistryInstructionService.submitAllowanceTransfer`: positive whole quantity, a price for a sale. */
export function allowanceTransferReady(values: AllowanceTransferValues, free: number | null): boolean {
  const quantity = values.quantity;
  if (
    !values.fromAccountId ||
    !values.productCode ||
    !values.toAccountId ||
    values.fromAccountId === values.toAccountId
  )
    return false;
  if (quantity === '' || !Number.isInteger(quantity) || quantity <= 0) return false;
  if (free !== null && quantity > free) return false;
  return values.movementType !== 'SALE' || (values.unitPrice.trim() !== '' && values.priceCurrency.trim() !== '');
}

export function toAllowanceTransfer(
  values: AllowanceTransferValues,
  idempotencyKey: string,
): Body<'SubmitAllowanceTransfer'> {
  const sale = values.movementType === 'SALE';
  return {
    fromAccountId: values.fromAccountId ?? '',
    productCode: values.productCode ?? '',
    quantity: Number(values.quantity),
    toAccountId: values.toAccountId ?? '',
    idempotencyKey,
    movementType: values.movementType,
    unitPrice: sale ? decimal(values.unitPrice) : null,
    priceCurrency: sale ? values.priceCurrency.trim().toUpperCase() : null,
  };
}

export interface CancellationValues {
  quantity: number | '';
  purpose: Purpose;
  beneficiaryName: string;
  beneficiaryId: string;
  country: string;
  from: string;
  to: string;
}

export function emptyCancellation(lot: Pick<Lot, 'deviceCountry'>): CancellationValues {
  return {
    quantity: '',
    purpose: 'DISCLOSURE',
    beneficiaryName: '',
    beneficiaryId: '',
    country: lot.deviceCountry,
    from: '',
    to: '',
  };
}

/** A consumption period, a country and, when a part of the lot is cancelled, a quantity smaller than the lot. */
export function cancellationReady(values: CancellationValues, lotQuantity: number): boolean {
  const quantity = values.quantity;
  if (quantity !== '' && (!Number.isInteger(quantity) || quantity < 1 || quantity > lotQuantity)) return false;
  return values.country.trim().length === 2 && Boolean(values.from) && Boolean(values.to) && values.from < values.to;
}

/** The registry cancellation instruction (it waits for a second person's approval like any manual instruction). */
export function toCancellation(
  lotId: string,
  values: CancellationValues,
  idempotencyKey: string,
): Body<'SubmitCancellation'> {
  return {
    lotId,
    idempotencyKey,
    quantityMwh: values.quantity === '' ? null : values.quantity,
    details: {
      purpose: values.purpose,
      consumptionCountry: values.country.trim().toUpperCase(),
      consumptionPeriod: { start: values.from, endExclusive: values.to },
      beneficiaryName: values.beneficiaryName.trim() || null,
      beneficiaryId: values.beneficiaryId.trim() || null,
    },
  };
}
