import type { Body, Schema } from '@/shared/api/types';

type Format = Schema<'StatementFormatView'>;
type Upsert = Body<'UpsertStatementFormat'>;

const FIELDS = [
  'registryCode',
  'profileCode',
  'kind',
  'delimiter',
  'skipLines',
  'dateFormat',
  'columnMapping',
  'constants',
  'active',
] as const satisfies readonly (keyof Upsert)[];

/** What the editor shows of a format: the fields of the upsert request, without id, version and audit fields. */
export function formatToUpsert(format: Format): Upsert {
  return {
    registryCode: format.registryCode,
    profileCode: format.profileCode,
    kind: format.kind,
    delimiter: format.delimiter,
    skipLines: format.skipLines,
    dateFormat: format.dateFormat,
    columnMapping: format.columnMapping,
    constants: format.constants,
    active: format.active,
  };
}

/**
 * A starting point for a new format: a GoO holdings export with the columns the parser needs. The profile code is
 * typed in its own field; `*` as the registry makes the profile usable for every registry.
 */
export const NEW_FORMAT: Omit<Upsert, 'profileCode'> = {
  registryCode: '*',
  kind: 'GOO_HOLDINGS',
  delimiter: ',',
  skipLines: 1,
  dateFormat: 'yyyy-MM-dd',
  columnMapping: {
    issuanceRef: 'issuance_ref',
    certStart: 'cert_no_start',
    certEnd: 'cert_no_end',
    deviceGsrn: 'device_gsrn',
    productionStart: 'production_start',
    productionEnd: 'production_end',
  },
  constants: {},
  active: true,
};

/**
 * The request from the edited JSON: only the request's own fields are passed on (the server checks their values),
 * and a profile code typed separately (new formats) wins over one in the JSON.
 */
export function upsertBody(json: Record<string, unknown>, profileCode?: string): Upsert {
  const body: Record<string, unknown> = {};
  FIELDS.forEach((field) => {
    if (field in json) body[field] = json[field];
  });
  if (profileCode !== undefined) body.profileCode = profileCode.trim();
  return body as unknown as Upsert;
}

/** A stable key of a format for test ids and React keys: registry, profile and kind. */
export function formatKey(format: Pick<Format, 'registryCode' | 'profileCode' | 'kind'>): string {
  return `${format.registryCode}-${format.profileCode}-${format.kind}`;
}
