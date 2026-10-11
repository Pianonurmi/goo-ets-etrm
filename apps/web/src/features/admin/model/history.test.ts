import { describe, expect, it } from 'vitest';
import { changedFields } from './history';

describe('reference history', () => {
  it('lists every set field of the first version', () => {
    expect(changedFields(undefined, { name: 'A', lei: null, version: 1, active: true })).toEqual([
      { field: 'active', before: null, after: 'true' },
      { field: 'name', before: null, after: 'A' },
    ]);
  });

  it('lists only what changed, ignoring bookkeeping columns', () => {
    expect(
      changedFields(
        { name: 'A', codes: { x: 1 }, version: 1, updated_at: 't1' },
        { name: 'B', codes: { x: 1 }, version: 2, updated_at: 't2', vat: 'FI1' },
      ),
    ).toEqual([
      { field: 'name', before: 'A', after: 'B' },
      { field: 'vat', before: null, after: 'FI1' },
    ]);
  });
});
