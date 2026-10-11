export interface FieldChange {
  field: string;
  before: string | null;
  after: string | null;
}

/** Bookkeeping columns every versioned row carries; they change on every write and say nothing. */
const IGNORED = new Set(['version', 'sys_period', 'updated_at', 'created_at', 'updated_by', 'created_by']);

function text(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return value.toString();
  return JSON.stringify(value);
}

/**
 * The fields that differ between two versions of a record, in key order. The first version lists every field
 * that has a value.
 */
export function changedFields(
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown>,
): FieldChange[] {
  const keys = [...new Set([...Object.keys(before ?? {}), ...Object.keys(after)])].sort();
  return keys
    .filter((key) => !IGNORED.has(key))
    .map((key) => ({ field: key, before: before ? text(before[key]) : null, after: text(after[key]) }))
    .filter((change) => change.before !== change.after);
}
