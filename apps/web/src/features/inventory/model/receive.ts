import dayjs from 'dayjs';
import { decimal, type Body, type Schema } from '@/shared/api/types';

type Account = Schema<'RegistryAccountView'>;
type Registry = Schema<'RegistryView'>;
type Device = Schema<'ProductionDeviceView'>;
export type AcquisitionSource = Body<'ReceiveLots'>['acquisitionSource'];
export const ACQUISITION_SOURCES: AcquisitionSource[] = ['ISSUANCE', 'TRANSFER_IN', 'OPENING_BALANCE'];

/**
 * The receive form. Field names follow the request (`range.start`, `productionPeriod.start`), so the server's
 * violations land on the right inputs; `device` holds the attributes of a device the registry of devices lacks.
 */
export interface ReceiveValues {
  registryAccountId: string | null;
  issuanceRef: string;
  range: { start: number | ''; end: number | '' };
  deviceGsrn: string;
  device: { country: string; energySourceCode: string | null; name: string };
  productionPeriod: { start: string; endExclusive: string };
  issueDate: string;
  issuingBody: string;
  issuingCountry: string;
  acquisitionSource: AcquisitionSource;
  acquisitionRef: string;
  expiryDate: string;
  costBasisPerMwh: string;
  costCurrency: string;
  supportProduction: boolean;
  supportInvestment: boolean;
}

/** Request fields the form has an input for; the server's violations of others are listed in the alert. */
export const RECEIVE_FIELDS: ReadonlySet<string> = new Set([
  'registryAccountId',
  'issuanceRef',
  'range',
  'deviceGsrn',
  'productionPeriod',
  'issueDate',
  'issuingBody',
  'issuingCountry',
  'acquisitionSource',
  'acquisitionRef',
  'expiryDate',
  'costBasisPerMwh',
  'costCurrency',
]);

/** Last month's production, issued today: the usual shape of a monthly issuance. */
export function emptyReceive(today = dayjs()): ReceiveValues {
  const month = today.startOf('month');
  return {
    registryAccountId: null,
    issuanceRef: '',
    range: { start: '', end: '' },
    deviceGsrn: '',
    device: { country: '', energySourceCode: null, name: '' },
    productionPeriod: {
      start: month.subtract(1, 'month').format('YYYY-MM-DD'),
      endExclusive: month.format('YYYY-MM-DD'),
    },
    issueDate: today.format('YYYY-MM-DD'),
    issuingBody: '',
    issuingCountry: '',
    acquisitionSource: 'ISSUANCE',
    acquisitionRef: '',
    expiryDate: '',
    costBasisPerMwh: '',
    costCurrency: 'EUR',
    supportProduction: false,
    supportInvestment: false,
  };
}

/** The entity's active accounts in a guarantee-of-origin registry (lots can only be received there). */
export function gooAccounts(accounts: Account[], registries: Registry[]): Account[] {
  const goo = new Set(registries.filter((registry) => registry.registryType === 'GOO').map((r) => r.code));
  return accounts.filter((account) => account.active && goo.has(account.registryCode));
}

/** Certificates in an inclusive number range, or null while the range is incomplete or reversed. */
export function rangeQuantity(range: ReceiveValues['range']): number | null {
  if (range.start === '' || range.end === '' || range.end < range.start) return null;
  return range.end - range.start + 1;
}

/** What still keeps the form from being sent, as field paths (empty when it is ready). */
export function missingFields(values: ReceiveValues, knownDevice: boolean): string[] {
  const missing: string[] = [];
  if (!values.registryAccountId) missing.push('registryAccountId');
  if (!values.issuanceRef.trim()) missing.push('issuanceRef');
  if (rangeQuantity(values.range) === null) missing.push('range');
  if (!values.deviceGsrn.trim()) missing.push('deviceGsrn');
  if (values.deviceGsrn.trim() && !knownDevice) {
    if (values.device.country.trim().length !== 2) missing.push('device.country');
    if (!values.device.energySourceCode) missing.push('device.energySourceCode');
  }
  if (!values.productionPeriod.start || !values.productionPeriod.endExclusive) missing.push('productionPeriod');
  if (!values.issueDate) missing.push('issueDate');
  if (!values.issuingBody.trim()) missing.push('issuingBody');
  if (values.issuingCountry.trim().length !== 2) missing.push('issuingCountry');
  return missing;
}

/** The device registered under a GSRN, if any. */
export function deviceFor(devices: Device[], gsrn: string): Device | undefined {
  const key = gsrn.trim();
  return key ? devices.find((device) => device.gsrn === key) : undefined;
}

const orNull = (value: string) => (value.trim() ? value.trim() : null);

/** The request; device attributes go along only when the GSRN is not a registered device. */
export function toReceiveLots(
  values: ReceiveValues,
  holderEntityId: string,
  knownDevice: boolean,
): Body<'ReceiveLots'> {
  const cost = values.costBasisPerMwh.trim();
  return {
    holderEntityId,
    registryAccountId: values.registryAccountId ?? '',
    issuanceRef: values.issuanceRef.trim(),
    range: { start: Number(values.range.start), end: Number(values.range.end) },
    deviceGsrn: values.deviceGsrn.trim(),
    deviceAttributes: knownDevice
      ? null
      : {
          country: values.device.country.trim().toUpperCase(),
          energySourceCode: values.device.energySourceCode ?? '',
          name: orNull(values.device.name),
        },
    productionPeriod: { start: values.productionPeriod.start, endExclusive: values.productionPeriod.endExclusive },
    issueDate: values.issueDate,
    issuingBody: values.issuingBody.trim(),
    issuingCountry: values.issuingCountry.trim().toUpperCase(),
    acquisitionSource: values.acquisitionSource,
    acquisitionRef: orNull(values.acquisitionRef),
    expiryDate: values.expiryDate || null,
    costBasisPerMwh: cost ? decimal(cost) : null,
    costCurrency: cost ? values.costCurrency.trim().toUpperCase() || null : null,
    supportProduction: values.supportProduction,
    supportInvestment: values.supportInvestment,
  };
}
