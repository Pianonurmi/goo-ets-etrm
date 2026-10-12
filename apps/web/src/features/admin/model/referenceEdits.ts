import { decimal, type Body, type Schema } from '@/shared/api/types';

export type LegalEntity = Schema<'LegalEntityView'>;
export type Counterparty = Schema<'CounterpartyView'>;
export type Device = Schema<'ProductionDeviceView'>;
export type RegistryAccount = Schema<'RegistryAccountView'>;
export type EmirClassification = NonNullable<LegalEntity['emirClassification']>;
export type OwnerType = Device['ownerType'];

export const EMIR_CLASSIFICATIONS: EmirClassification[] = [
  'FC',
  'NFC_PLUS',
  'NFC_MINUS',
  'THIRD_COUNTRY_ENTITY',
  'OTHER',
];
export const OWNER_TYPES: OwnerType[] = ['LEGAL_ENTITY', 'COUNTERPARTY', 'THIRD_PARTY'];

const DECIMAL = /^\d+(\.\d+)?$/;
const WHOLE_NUMBER = /^\d{1,4}$/;

/** Days between the end of a production month and the issuance of its GoOs, until a device says otherwise. */
export const DEFAULT_ISSUANCE_LAG_DAYS = 30;

/**
 * The typed value when it differs from the stored one. The API patches with "absent means unchanged", so a field
 * cannot be cleared: a blank input keeps the stored value.
 */
function changed(current: string | null | undefined, next: string): string | undefined {
  const value = next.trim();
  return value && value !== (current ?? '') ? value : undefined;
}

function changedFlag(current: boolean, next: boolean): boolean | undefined {
  return current === next ? undefined : next;
}

function lagChanged(current: number | undefined, next: string): number | undefined {
  const value = next.trim();
  return WHOLE_NUMBER.test(value) && Number(value) !== (current ?? DEFAULT_ISSUANCE_LAG_DAYS)
    ? Number(value)
    : undefined;
}

function sameSet(a: readonly string[], b: readonly string[]): boolean {
  return [...a].sort().join('\u0000') === [...b].sort().join('\u0000');
}

/** A JSON object of strings (external codes, rule conditions); null when the text is not one. Blank is `{}`. */
export function parseStringMap(text: string): Record<string, string> | null {
  if (!text.trim()) return {};
  try {
    const value: unknown = JSON.parse(text);
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.some(([, v]) => typeof v !== 'string')) return null;
    return Object.fromEntries(entries) as Record<string, string>;
  } catch {
    return null;
  }
}

export function formatStringMap(map: Record<string, string>): string {
  return Object.keys(map).length === 0 ? '' : JSON.stringify(map, null, 2);
}

/** GS1 GSRN: 18 digits, the last a mod-10 check digit (weights 3 and 1 from the right). */
export function gsrnValid(value: string): boolean {
  const digits = value.trim();
  if (!/^\d{18}$/.test(digits)) return false;
  const body = digits.slice(0, 17).split('').reverse();
  const sum = body.reduce((total, digit, index) => total + Number(digit) * (index % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === Number(digits[17]);
}

// ---------------------------------------------------------------------------------------------------------------
// Legal entity
// ---------------------------------------------------------------------------------------------------------------

/** What middle office edits on a legal entity; the accounting policy changes through month end (four eyes). */
export interface EntityDraft {
  name: string;
  lei: string;
  vatNumber: string;
  emirClassification: EmirClassification | null;
  obligationsOpenAt: LegalEntity['obligationsOpenAt'];
  active: boolean;
  validTo: string;
}

export function entityDraft(entity: LegalEntity): EntityDraft {
  return {
    name: entity.name,
    lei: entity.lei ?? '',
    vatNumber: entity.vatNumber ?? '',
    emirClassification: entity.emirClassification ?? null,
    obligationsOpenAt: entity.obligationsOpenAt,
    active: entity.active,
    validTo: entity.validPeriod.validTo ?? '',
  };
}

export function entityPatch(entity: LegalEntity, draft: EntityDraft): Body<'UpdateLegalEntity'> {
  return {
    expectedVersion: entity.version,
    name: changed(entity.name, draft.name),
    lei: changed(entity.lei, draft.lei.toUpperCase()),
    vatNumber: changed(entity.vatNumber, draft.vatNumber),
    emirClassification:
      draft.emirClassification && draft.emirClassification !== entity.emirClassification
        ? draft.emirClassification
        : undefined,
    obligationsOpenAt: draft.obligationsOpenAt !== entity.obligationsOpenAt ? draft.obligationsOpenAt : undefined,
    active: changedFlag(entity.active, draft.active),
    validTo: changed(entity.validPeriod.validTo, draft.validTo),
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Counterparty
// ---------------------------------------------------------------------------------------------------------------

/**
 * What middle office edits on a counterparty. Registry and bank accounts change through change requests, and the
 * credit limit is a risk limit, so neither is here.
 */
export interface CounterpartyDraft {
  name: string;
  lei: string;
  vatNumber: string;
  paymentTermsDays: number;
  invoiceEmail: string;
  emirClassification: EmirClassification | null;
  parentCounterpartyId: string | null;
  nettingAgreed: boolean;
  selfBilling: boolean;
  active: boolean;
}

export function counterpartyDraft(counterparty: Counterparty): CounterpartyDraft {
  return {
    name: counterparty.name,
    lei: counterparty.lei ?? '',
    vatNumber: counterparty.vatNumber ?? '',
    paymentTermsDays: counterparty.paymentTermsDays,
    invoiceEmail: counterparty.invoiceEmail ?? '',
    emirClassification: counterparty.emirClassification ?? null,
    parentCounterpartyId: counterparty.parentCounterpartyId ?? null,
    nettingAgreed: counterparty.nettingAgreed ?? false,
    selfBilling: counterparty.selfBilling ?? false,
    active: counterparty.active,
  };
}

export function counterpartyPatch(counterparty: Counterparty, draft: CounterpartyDraft): Body<'UpdateCounterparty'> {
  return {
    expectedVersion: counterparty.version,
    name: changed(counterparty.name, draft.name),
    lei: changed(counterparty.lei, draft.lei.toUpperCase()),
    vatNumber: changed(counterparty.vatNumber, draft.vatNumber),
    paymentTermsDays:
      draft.paymentTermsDays !== counterparty.paymentTermsDays ? Math.trunc(draft.paymentTermsDays) : undefined,
    invoiceEmail: changed(counterparty.invoiceEmail, draft.invoiceEmail),
    emirClassification:
      draft.emirClassification && draft.emirClassification !== counterparty.emirClassification
        ? draft.emirClassification
        : undefined,
    parentCounterpartyId:
      draft.parentCounterpartyId && draft.parentCounterpartyId !== counterparty.parentCounterpartyId
        ? draft.parentCounterpartyId
        : undefined,
    nettingAgreed: changedFlag(counterparty.nettingAgreed ?? false, draft.nettingAgreed),
    selfBilling: changedFlag(counterparty.selfBilling ?? false, draft.selfBilling),
    active: changedFlag(counterparty.active, draft.active),
  };
}

/** Whether a patch changes anything beyond the version check. */
export function hasChanges(patch: Record<string, unknown>): boolean {
  return Object.entries(patch).some(([key, value]) => key !== 'expectedVersion' && value !== undefined);
}

// ---------------------------------------------------------------------------------------------------------------
// Production device
// ---------------------------------------------------------------------------------------------------------------

/** A device as typed; the capacity stays a decimal string. */
export interface DeviceDraft {
  gsrn: string;
  name: string;
  country: string;
  capacityKw: string;
  issuanceLagDays: string;
  commissioningDate: string;
  decommissioningDate: string;
  energySourceCode: string | null;
  technologyCode: string | null;
  supportSchemeCodes: string[];
  labelCodes: string[];
  registryCode: string | null;
  ownerType: OwnerType;
  ownerId: string | null;
  ownDevice: boolean;
  externalCodes: string;
  active: boolean;
}

export function emptyDevice(today: string): DeviceDraft {
  return {
    gsrn: '',
    name: '',
    country: '',
    capacityKw: '',
    issuanceLagDays: String(DEFAULT_ISSUANCE_LAG_DAYS),
    commissioningDate: today,
    decommissioningDate: '',
    energySourceCode: null,
    technologyCode: null,
    supportSchemeCodes: [],
    labelCodes: [],
    registryCode: null,
    ownerType: 'LEGAL_ENTITY',
    ownerId: null,
    ownDevice: true,
    externalCodes: '',
    active: true,
  };
}

export function deviceDraft(device: Device): DeviceDraft {
  return {
    gsrn: device.gsrn,
    name: device.name,
    country: device.country,
    capacityKw: String(device.capacityKw),
    issuanceLagDays: String(device.issuanceLagDays ?? DEFAULT_ISSUANCE_LAG_DAYS),
    commissioningDate: device.commissioningDate,
    decommissioningDate: device.decommissioningDate ?? '',
    energySourceCode: device.energySourceCode,
    technologyCode: device.technologyCode,
    supportSchemeCodes: [...device.supportSchemeCodes],
    labelCodes: [...device.labelCodes],
    registryCode: null,
    ownerType: device.ownerType,
    ownerId: device.ownerId ?? null,
    ownDevice: device.ownDevice,
    externalCodes: formatStringMap(device.externalCodes),
    active: device.active,
  };
}

/** The fields a new device still lacks (or has malformed), by field name. */
export function deviceProblems(draft: DeviceDraft): string[] {
  const problems: string[] = [];
  if (!gsrnValid(draft.gsrn)) problems.push('gsrn');
  if (!draft.name.trim()) problems.push('name');
  if (!/^[A-Z]{2}$/.test(draft.country.trim().toUpperCase())) problems.push('country');
  if (!DECIMAL.test(draft.capacityKw.trim()) || !/[1-9]/.test(draft.capacityKw)) problems.push('capacityKw');
  if (!WHOLE_NUMBER.test(draft.issuanceLagDays.trim())) problems.push('issuanceLagDays');
  if (!draft.commissioningDate) problems.push('commissioningDate');
  if (draft.decommissioningDate && draft.decommissioningDate <= draft.commissioningDate) {
    problems.push('decommissioningDate');
  }
  if (!draft.energySourceCode) problems.push('energySourceCode');
  if (!draft.technologyCode) problems.push('technologyCode');
  if (draft.ownerType !== 'THIRD_PARTY' && !draft.ownerId) problems.push('ownerId');
  if (parseStringMap(draft.externalCodes) === null) problems.push('externalCodes');
  return problems;
}

export function newDeviceBody(draft: DeviceDraft): Body<'CreateProductionDevice'> {
  return {
    gsrn: draft.gsrn.trim(),
    name: draft.name.trim(),
    country: draft.country.trim().toUpperCase(),
    capacityKw: decimal(draft.capacityKw),
    issuanceLagDays: Number(draft.issuanceLagDays.trim()),
    commissioningDate: draft.commissioningDate,
    decommissioningDate: draft.decommissioningDate || undefined,
    energySourceCode: draft.energySourceCode ?? '',
    technologyCode: draft.technologyCode ?? '',
    supportSchemeCodes: draft.supportSchemeCodes,
    labelCodes: draft.labelCodes,
    registryCode: draft.registryCode ?? undefined,
    ownerType: draft.ownerType,
    ownerId: draft.ownerType === 'THIRD_PARTY' ? undefined : (draft.ownerId ?? undefined),
    ownDevice: draft.ownDevice,
    externalCodes: parseStringMap(draft.externalCodes) ?? {},
  };
}

/** The editable fields that changed; identity, technology and ownership are fixed once a device exists. */
export function devicePatch(device: Device, draft: DeviceDraft): Body<'UpdateProductionDevice'> {
  const capacity = draft.capacityKw.trim();
  const codes = parseStringMap(draft.externalCodes);
  return {
    expectedVersion: device.version,
    name: changed(device.name, draft.name),
    capacityKw: capacity && capacity !== String(device.capacityKw) ? decimal(capacity) : undefined,
    issuanceLagDays: lagChanged(device.issuanceLagDays, draft.issuanceLagDays),
    decommissioningDate: changed(device.decommissioningDate, draft.decommissioningDate),
    supportSchemeCodes: sameSet(device.supportSchemeCodes, draft.supportSchemeCodes)
      ? undefined
      : draft.supportSchemeCodes,
    labelCodes: sameSet(device.labelCodes, draft.labelCodes) ? undefined : draft.labelCodes,
    externalCodes: codes && JSON.stringify(codes) !== JSON.stringify(device.externalCodes) ? codes : undefined,
    active: changedFlag(device.active, draft.active),
  };
}

/** Problems of an edit: a capacity must stay a positive decimal and the codes a JSON object of strings. */
export function deviceEditProblems(draft: DeviceDraft): string[] {
  const problems: string[] = [];
  if (!draft.name.trim()) problems.push('name');
  if (!DECIMAL.test(draft.capacityKw.trim()) || !/[1-9]/.test(draft.capacityKw)) problems.push('capacityKw');
  if (!WHOLE_NUMBER.test(draft.issuanceLagDays.trim())) problems.push('issuanceLagDays');
  if (draft.decommissioningDate && draft.decommissioningDate <= draft.commissioningDate) {
    problems.push('decommissioningDate');
  }
  if (parseStringMap(draft.externalCodes) === null) problems.push('externalCodes');
  return problems;
}

// ---------------------------------------------------------------------------------------------------------------
// Registry accounts and history
// ---------------------------------------------------------------------------------------------------------------

/** Only an entity's own accounts belong to one of its books (the service refuses counterparty accounts). */
export function defaultBookAssignable(account: Pick<RegistryAccount, 'holderType'>): boolean {
  return account.holderType === 'LEGAL_ENTITY';
}

/** A `datetime-local` value (the browser's time zone) as the instant the API takes; null when empty or invalid. */
export function knownAtInstant(local: string): string | null {
  if (!local) return null;
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** A record snapshot as label and text rows, in key order. */
export function snapshotRows(snapshot: Record<string, unknown>): { field: string; value: string }[] {
  return Object.keys(snapshot)
    .sort()
    .map((field) => {
      const value = snapshot[field];
      let text: string;
      if (value === null || value === undefined) text = '';
      else if (typeof value === 'string') text = value;
      else if (typeof value === 'number' || typeof value === 'boolean') text = value.toString();
      else text = JSON.stringify(value);
      return { field, value: text };
    });
}
