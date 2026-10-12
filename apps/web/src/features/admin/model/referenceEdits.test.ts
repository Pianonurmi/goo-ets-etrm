import { describe, expect, it } from 'vitest';
import {
  counterpartyDraft,
  counterpartyPatch,
  defaultBookAssignable,
  deviceDraft,
  deviceEditProblems,
  devicePatch,
  deviceProblems,
  emptyDevice,
  entityDraft,
  entityPatch,
  gsrnValid,
  hasChanges,
  knownAtInstant,
  newDeviceBody,
  parseStringMap,
  snapshotRows,
  type Counterparty,
  type Device,
  type LegalEntity,
} from './referenceEdits';

const entity = {
  id: 'e-1',
  code: 'DEMO-FI',
  name: 'Demo Oy',
  country: 'FI',
  lei: null,
  vatNumber: 'FI12345678',
  baseCurrency: 'EUR',
  timeZone: 'Europe/Helsinki',
  validPeriod: { validFrom: '2026-01-01', validTo: null },
  obligationsOpenAt: 'CONFIRMED',
  emirClassification: 'NFC_MINUS',
  active: true,
  version: 3,
} as LegalEntity;

const counterparty = {
  id: 'c-1',
  code: 'CP-AURORA',
  name: 'Aurora AB',
  country: 'SE',
  counterpartyType: 'EXTERNAL',
  masterAgreements: [],
  paymentTermsDays: 14,
  settlementCurrency: 'EUR',
  invoiceEmail: null,
  nettingAgreed: false,
  selfBilling: false,
  active: true,
  version: 2,
} as unknown as Counterparty;

const device = {
  id: 'd-1',
  gsrn: '643002406000000017',
  name: 'Koski hydro',
  country: 'FI',
  capacityKw: 5000,
  commissioningDate: '2010-05-01',
  decommissioningDate: null,
  energySourceCode: 'HYDRO',
  technologyCode: 'HYDRO_RUN_OF_RIVER',
  supportSchemeCodes: [],
  labelCodes: ['EKOENERGY'],
  ownerType: 'LEGAL_ENTITY',
  ownerId: 'e-1',
  ownDevice: true,
  externalCodes: { AIB: 'X1' },
  active: true,
  version: 4,
} as unknown as Device;

/** A GSRN with a valid check digit, from 17 digits. */
function withCheckDigit(body: string): string {
  const sum = body
    .split('')
    .reverse()
    .reduce((total, digit, index) => total + Number(digit) * (index % 2 === 0 ? 3 : 1), 0);
  return body + String((10 - (sum % 10)) % 10);
}

describe('reference data edits', () => {
  it('sends only what changed on a legal entity, with the version it was read at', () => {
    const draft = entityDraft(entity);
    expect(hasChanges(entityPatch(entity, draft))).toBe(false);
    const patch = entityPatch(entity, { ...draft, name: ' Demo Finland Oy ', active: false, emirClassification: 'FC' });
    expect(patch).toEqual({
      expectedVersion: 3,
      name: 'Demo Finland Oy',
      lei: undefined,
      vatNumber: undefined,
      emirClassification: 'FC',
      obligationsOpenAt: undefined,
      active: false,
      validTo: undefined,
    });
  });

  it('keeps a stored value when its input is cleared (the API cannot clear a field)', () => {
    const patch = entityPatch(entity, { ...entityDraft(entity), vatNumber: '  ' });
    expect(patch.vatNumber).toBeUndefined();
    expect(hasChanges(patch)).toBe(false);
  });

  it('builds a counterparty patch without credit limit or accounts', () => {
    const draft = counterpartyDraft(counterparty);
    const patch = counterpartyPatch(counterparty, {
      ...draft,
      paymentTermsDays: 30,
      invoiceEmail: 'ap@aurora.example',
      nettingAgreed: true,
      parentCounterpartyId: 'c-9',
    });
    expect(patch).toMatchObject({
      expectedVersion: 2,
      paymentTermsDays: 30,
      invoiceEmail: 'ap@aurora.example',
      nettingAgreed: true,
      parentCounterpartyId: 'c-9',
    });
    expect(patch).not.toHaveProperty('creditLimit');
    expect(patch.selfBilling).toBeUndefined();
  });

  it('checks the GS1 check digit of a GSRN', () => {
    const gsrn = withCheckDigit('64300240600000001');
    expect(gsrnValid(gsrn)).toBe(true);
    const wrong = gsrn.slice(0, 17) + String((Number(gsrn[17]) + 1) % 10);
    expect(gsrnValid(wrong)).toBe(false);
    expect(gsrnValid('12345')).toBe(false);
  });

  it('parses external codes as a JSON object of strings', () => {
    expect(parseStringMap('')).toEqual({});
    expect(parseStringMap('{"AIB":"X1"}')).toEqual({ AIB: 'X1' });
    expect(parseStringMap('{"AIB":1}')).toBeNull();
    expect(parseStringMap('[1]')).toBeNull();
    expect(parseStringMap('{')).toBeNull();
  });

  it('lists what a new device lacks and builds its body', () => {
    const empty = emptyDevice('2026-10-11');
    expect(deviceProblems(empty)).toEqual(
      expect.arrayContaining(['gsrn', 'name', 'country', 'capacityKw', 'energySourceCode', 'ownerId']),
    );
    const draft = {
      ...empty,
      gsrn: withCheckDigit('64300240612345678'),
      name: 'Tuuli wind',
      country: 'fi',
      capacityKw: '2500.5',
      energySourceCode: 'WIND',
      technologyCode: 'WIND_ONSHORE',
      ownerId: 'e-1',
      externalCodes: '{"AIB":"W1"}',
    };
    expect(deviceProblems(draft)).toEqual([]);
    expect(newDeviceBody(draft)).toMatchObject({
      country: 'FI',
      capacityKw: '2500.5',
      ownerType: 'LEGAL_ENTITY',
      ownerId: 'e-1',
      externalCodes: { AIB: 'W1' },
    });
    expect(newDeviceBody({ ...draft, ownerType: 'THIRD_PARTY' }).ownerId).toBeUndefined();
    expect(newDeviceBody(draft).issuanceLagDays).toBe(30);
    expect(deviceProblems({ ...draft, issuanceLagDays: '-1' })).toEqual(['issuanceLagDays']);
  });

  it('patches the editable fields of a device', () => {
    const draft = deviceDraft(device);
    expect(hasChanges(devicePatch(device, draft))).toBe(false);
    expect(deviceEditProblems(draft)).toEqual([]);
    const patch = devicePatch(device, { ...draft, capacityKw: '5200', labelCodes: [], externalCodes: '' });
    expect(patch).toMatchObject({ expectedVersion: 4, capacityKw: '5200', labelCodes: [], externalCodes: {} });
    expect(devicePatch(device, { ...draft, issuanceLagDays: '45' }).issuanceLagDays).toBe(45);
    expect(deviceEditProblems({ ...draft, capacityKw: '0', externalCodes: '{' })).toEqual([
      'capacityKw',
      'externalCodes',
    ]);
  });

  it('assigns default books to own accounts only', () => {
    expect(defaultBookAssignable({ holderType: 'LEGAL_ENTITY' })).toBe(true);
    expect(defaultBookAssignable({ holderType: 'COUNTERPARTY' })).toBe(false);
  });

  it('turns a local date-time into an instant and a snapshot into rows', () => {
    expect(knownAtInstant('')).toBeNull();
    expect(knownAtInstant('not a date')).toBeNull();
    expect(knownAtInstant('2026-10-01T12:00')).toBe(new Date('2026-10-01T12:00').toISOString());
    expect(snapshotRows({ name: 'A', active: true, codes: { x: '1' }, lei: null })).toEqual([
      { field: 'active', value: 'true' },
      { field: 'codes', value: '{"x":"1"}' },
      { field: 'lei', value: '' },
      { field: 'name', value: 'A' },
    ]);
  });
});
