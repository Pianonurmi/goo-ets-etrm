import { describe, expect, it } from 'vitest';
import type { Schema } from '@/shared/api/types';
import { NEW_FORMAT, formatKey, formatToUpsert, upsertBody } from './formats';

const format = {
  id: 'f1',
  registryCode: '*',
  profileCode: 'GENERIC',
  kind: 'GOO_HOLDINGS',
  delimiter: ',',
  skipLines: 1,
  dateFormat: 'yyyy-MM-dd',
  columnMapping: { issuanceRef: 'issuance_ref' },
  constants: {},
  active: true,
  version: 3,
  updatedBy: 'middle',
  updatedAt: '2026-10-01T08:00:00Z',
} as Schema<'StatementFormatView'>;

describe('statement formats', () => {
  it('edits the request fields only', () => {
    const upsert = formatToUpsert(format);
    expect(upsert).not.toHaveProperty('id');
    expect(upsert).not.toHaveProperty('version');
    expect(upsert).toMatchObject({ registryCode: '*', profileCode: 'GENERIC', kind: 'GOO_HOLDINGS' });
  });

  it('passes on known fields and the typed profile code', () => {
    expect(upsertBody({ ...formatToUpsert(format), id: 'f1', junk: 1 })).toEqual(formatToUpsert(format));
    expect(upsertBody({ ...NEW_FORMAT }, ' E2E ')).toMatchObject({ profileCode: 'E2E', registryCode: '*' });
    expect(upsertBody({ ...NEW_FORMAT, profileCode: 'OTHER' }, 'MINE').profileCode).toBe('MINE');
  });

  it('keys a format by registry, profile and kind', () => {
    expect(formatKey(format)).toBe('*-GENERIC-GOO_HOLDINGS');
  });
});
