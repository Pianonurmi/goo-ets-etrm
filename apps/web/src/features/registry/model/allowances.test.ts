import { describe, expect, it } from 'vitest';
import type { Schema } from '@/shared/api/types';
import {
  allowanceTransferReady,
  cancellationReady,
  destinationAccounts,
  emptyCancellation,
  freeQuantity,
  sourceAccounts,
  toAllowanceTransfer,
  toCancellation,
  transferableHoldings,
  type AllowanceTransferValues,
} from './allowances';

type Account = Schema<'RegistryAccountView'>;
type Holding = Schema<'AllowanceHoldingView'>;

const account = (id: string, registryCode: string, extra: Partial<Account> = {}) =>
  ({ id, registryCode, active: true, canExport: true, holderType: 'LEGAL_ENTITY', ...extra }) as Account;

const values: AllowanceTransferValues = {
  fromAccountId: 'a1',
  productCode: 'EUA',
  quantity: 100,
  toAccountId: 'a2',
  movementType: 'TRANSFER_OUT',
  unitPrice: '',
  priceCurrency: 'EUR',
};

describe('allowance transfer', () => {
  it('moves only free allowances, within one registry', () => {
    expect(freeQuantity({ quantity: 3500, reservedQuantity: 800 })).toBe(2700);
    expect(freeQuantity({ quantity: 10, reservedQuantity: 20 })).toBe(0);
    const accounts = [
      account('a1', 'UNION_REGISTRY'),
      account('a2', 'UNION_REGISTRY'),
      account('a3', 'SIMULATOR'),
      account('a4', 'UNION_REGISTRY', { active: false }),
      account('a5', 'UNION_REGISTRY', { canExport: false }),
    ];
    expect(sourceAccounts(accounts).map((a) => a.id)).toEqual(['a1', 'a2', 'a3']);
    expect(destinationAccounts(accounts, accounts[0]).map((a) => a.id)).toEqual(['a2', 'a5']);
    expect(destinationAccounts(accounts, undefined)).toEqual([]);
    const holdings = [
      { registryAccountId: 'a1', productCode: 'EUA', quantity: 100, reservedQuantity: 0 },
      { registryAccountId: 'a1', productCode: 'EUAA', quantity: 50, reservedQuantity: 50 },
      { registryAccountId: 'a2', productCode: 'EUA', quantity: 10, reservedQuantity: 0 },
    ] as Holding[];
    expect(transferableHoldings(holdings, 'a1').map((h) => h.productCode)).toEqual(['EUA']);
  });

  it('is ready with a positive quantity within the free balance, and a price for a sale', () => {
    expect(allowanceTransferReady(values, 2700)).toBe(true);
    expect(allowanceTransferReady({ ...values, quantity: 2701 }, 2700)).toBe(false);
    expect(allowanceTransferReady({ ...values, quantity: 0 }, null)).toBe(false);
    expect(allowanceTransferReady({ ...values, toAccountId: 'a1' }, null)).toBe(false);
    expect(allowanceTransferReady({ ...values, movementType: 'SALE' }, null)).toBe(false);
    expect(allowanceTransferReady({ ...values, movementType: 'SALE', unitPrice: '71.25' }, null)).toBe(true);
  });

  it('builds the request; a price only for a sale', () => {
    expect(toAllowanceTransfer({ ...values, unitPrice: '70' }, 'ui-1')).toEqual({
      fromAccountId: 'a1',
      productCode: 'EUA',
      quantity: 100,
      toAccountId: 'a2',
      idempotencyKey: 'ui-1',
      movementType: 'TRANSFER_OUT',
      unitPrice: null,
      priceCurrency: null,
    });
    expect(
      toAllowanceTransfer({ ...values, movementType: 'SALE', unitPrice: '71.25', priceCurrency: 'eur' }, 'ui-2'),
    ).toMatchObject({ movementType: 'SALE', unitPrice: '71.25', priceCurrency: 'EUR' });
  });
});

describe('registry cancellation', () => {
  it('needs a country and a consumption period, and a quantity within the lot', () => {
    const empty = emptyCancellation({ deviceCountry: 'FI' });
    expect(empty.country).toBe('FI');
    expect(cancellationReady(empty, 100)).toBe(false);
    const filled = { ...empty, from: '2026-01-01', to: '2027-01-01' };
    expect(cancellationReady(filled, 100)).toBe(true);
    expect(cancellationReady({ ...filled, to: '2025-12-31' }, 100)).toBe(false);
    expect(cancellationReady({ ...filled, quantity: 101 }, 100)).toBe(false);
    expect(cancellationReady({ ...filled, quantity: 40 }, 100)).toBe(true);
  });

  it('builds the instruction for a beneficiary', () => {
    const filled = {
      ...emptyCancellation({ deviceCountry: 'fi' }),
      quantity: 40 as number | '',
      beneficiaryName: ' Oy Asiakas Ab ',
      from: '2026-01-01',
      to: '2027-01-01',
    };
    expect(toCancellation('l1', filled, 'ui-c1')).toEqual({
      lotId: 'l1',
      idempotencyKey: 'ui-c1',
      quantityMwh: 40,
      details: {
        purpose: 'DISCLOSURE',
        consumptionCountry: 'FI',
        consumptionPeriod: { start: '2026-01-01', endExclusive: '2027-01-01' },
        beneficiaryName: 'Oy Asiakas Ab',
        beneficiaryId: null,
      },
    });
  });
});
