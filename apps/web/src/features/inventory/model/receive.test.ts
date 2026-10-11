import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';
import type { Schema } from '@/shared/api/types';
import { deviceFor, emptyReceive, gooAccounts, missingFields, rangeQuantity, toReceiveLots } from './receive';

type Account = Schema<'RegistryAccountView'>;
type Registry = Schema<'RegistryView'>;
type Device = Schema<'ProductionDeviceView'>;

const filled = () => ({
  ...emptyReceive(dayjs('2026-10-11')),
  registryAccountId: 'a1',
  issuanceRef: ' ISS-1 ',
  range: { start: 1001, end: 1100 } as { start: number | ''; end: number | '' },
  deviceGsrn: '643000000000000001',
  issuingBody: 'Fingrid',
  issuingCountry: 'fi',
});

describe('receive certificates', () => {
  it('starts with last month issued today', () => {
    const values = emptyReceive(dayjs('2026-10-11'));
    expect(values.productionPeriod).toEqual({ start: '2026-09-01', endExclusive: '2026-10-01' });
    expect(values.issueDate).toBe('2026-10-11');
  });

  it('counts the certificates of an inclusive range', () => {
    expect(rangeQuantity({ start: 1001, end: 1100 })).toBe(100);
    expect(rangeQuantity({ start: 5, end: 5 })).toBe(1);
    expect(rangeQuantity({ start: 10, end: 9 })).toBeNull();
    expect(rangeQuantity({ start: '', end: 9 })).toBeNull();
  });

  it('lists what is missing; device attributes only for an unknown device', () => {
    expect(missingFields(emptyReceive(), false)).toEqual(
      expect.arrayContaining(['registryAccountId', 'issuanceRef', 'range', 'deviceGsrn', 'issuingBody']),
    );
    expect(missingFields(filled(), true)).toEqual([]);
    expect(missingFields(filled(), false)).toEqual(['device.country', 'device.energySourceCode']);
  });

  it('builds the request', () => {
    const body = toReceiveLots({ ...filled(), costBasisPerMwh: '0.95' }, 'e1', true);
    expect(body).toMatchObject({
      holderEntityId: 'e1',
      registryAccountId: 'a1',
      issuanceRef: 'ISS-1',
      range: { start: 1001, end: 1100 },
      issuingCountry: 'FI',
      deviceAttributes: null,
      expiryDate: null,
      costBasisPerMwh: '0.95',
      costCurrency: 'EUR',
      acquisitionSource: 'ISSUANCE',
    });
    const unknown = toReceiveLots(
      { ...filled(), device: { country: 'se', energySourceCode: 'WIND', name: '' } },
      'e1',
      false,
    );
    expect(unknown.deviceAttributes).toEqual({ country: 'SE', energySourceCode: 'WIND', name: null });
    expect(unknown.costBasisPerMwh).toBeNull();
    expect(unknown.costCurrency).toBeNull();
  });

  it('offers only active accounts in a GoO registry, and finds devices by GSRN', () => {
    const registries = [
      { code: 'SIMULATOR', registryType: 'GOO' },
      { code: 'UNION_REGISTRY', registryType: 'EMISSION_ALLOWANCE' },
    ] as Registry[];
    const accounts = [
      { id: 'a1', registryCode: 'SIMULATOR', active: true },
      { id: 'a2', registryCode: 'SIMULATOR', active: false },
      { id: 'a3', registryCode: 'UNION_REGISTRY', active: true },
    ] as Account[];
    expect(gooAccounts(accounts, registries).map((a) => a.id)).toEqual(['a1']);
    const devices = [{ gsrn: '643000000000000001', name: 'Hydro' }] as Device[];
    expect(deviceFor(devices, ' 643000000000000001 ')?.name).toBe('Hydro');
    expect(deviceFor(devices, '')).toBeUndefined();
  });
});
