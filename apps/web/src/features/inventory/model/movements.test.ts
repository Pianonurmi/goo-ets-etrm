import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';
import type { Schema } from '@/shared/api/types';
import { allowanceAccounts, direction, emptyMovement, movementReady, toMovement, validQuantity } from './movements';

describe('allowance movements', () => {
  it('signs by type; a correction carries its own sign', () => {
    expect(direction('PURCHASE')).toBe(1);
    expect(direction('FREE_ALLOCATION')).toBe(1);
    expect(direction('SALE')).toBe(-1);
    expect(direction('SURRENDER')).toBe(-1);
    expect(direction('CORRECTION')).toBe(0);
    expect(validQuantity('PURCHASE', 10)).toBe(true);
    expect(validQuantity('PURCHASE', -10)).toBe(false);
    expect(validQuantity('CORRECTION', -10)).toBe(true);
    expect(validQuantity('CORRECTION', 0)).toBe(false);
    expect(validQuantity('PURCHASE', 1.5)).toBe(false);
    expect(validQuantity('PURCHASE', '')).toBe(false);
  });

  it('builds the request', () => {
    const values = { ...emptyMovement('a1', 'EUA', dayjs('2026-10-11')), quantity: 500 as number | '' };
    expect(movementReady(values)).toBe(true);
    expect(movementReady({ ...values, productCode: null })).toBe(false);
    expect(toMovement({ ...values, costPerUnit: '70.10', reference: ' BUY-1 ' }, 'e1')).toEqual({
      holderEntityId: 'e1',
      registryAccountId: 'a1',
      productCode: 'EUA',
      type: 'PURCHASE',
      quantity: 500,
      effectiveDate: '2026-10-11',
      reference: 'BUY-1',
      complianceYear: null,
      costPerUnit: '70.10',
      costCurrency: 'EUR',
    });
  });

  it('offers only active accounts in an allowance registry', () => {
    const registries = [
      { code: 'SIMULATOR', registryType: 'GOO' },
      { code: 'UNION_REGISTRY', registryType: 'EMISSION_ALLOWANCE' },
    ] as Schema<'RegistryView'>[];
    const accounts = [
      { id: 'a1', registryCode: 'SIMULATOR', active: true },
      { id: 'a2', registryCode: 'UNION_REGISTRY', active: true },
      { id: 'a3', registryCode: 'UNION_REGISTRY', active: false },
    ] as Schema<'RegistryAccountView'>[];
    expect(allowanceAccounts(accounts, registries).map((a) => a.id)).toEqual(['a2']);
  });
});
