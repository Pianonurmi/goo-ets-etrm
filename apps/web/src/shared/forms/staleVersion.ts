/**
 * Recovery from an optimistic-concurrency conflict (W5). A form is opened on a record (`loaded`), the user edits it
 * (`mine`) and the save is refused because the record has moved on (`current`). These pure functions say what
 * changed on the server and merge the user's edits onto the server's version, so the next save can go through.
 */

export type Draft = object;

/** One thing that changed on the server while the form was open. */
export interface FieldChange {
  /** Dotted path into the draft: `name`, `legs.0.quantity`. */
  path: string;
  /** What the form was opened with. */
  loaded: unknown;
  /** What the server has now. */
  current: unknown;
  /** What is in the form now. */
  mine: unknown;
  /** The user changed this field too; their edit is the one kept. */
  edited: boolean;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function sameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((item, index) => sameValue(item, b[index]));
  }
  if (isPlainObject(a) && isPlainObject(b)) {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    return [...keys].every((key) => sameValue(a[key], b[key]));
  }
  return false;
}

/** Objects, and arrays of the same length in all three versions, are compared field by field. */
function sameShape(loaded: unknown, mine: unknown, current: unknown): boolean {
  if (isPlainObject(loaded) && isPlainObject(mine) && isPlainObject(current)) return true;
  return (
    Array.isArray(loaded) &&
    Array.isArray(mine) &&
    Array.isArray(current) &&
    loaded.length === mine.length &&
    loaded.length === current.length
  );
}

function childKeys(value: unknown): string[] {
  if (Array.isArray(value)) return value.map((_, index) => String(index));
  return isPlainObject(value) ? Object.keys(value) : [];
}

function child(value: unknown, key: string): unknown {
  if (Array.isArray(value)) return value[Number(key)];
  return isPlainObject(value) ? value[key] : undefined;
}

function collect(loaded: unknown, mine: unknown, current: unknown, path: string, into: FieldChange[]): void {
  if (sameValue(loaded, current)) return;
  if (sameShape(loaded, mine, current)) {
    const keys = new Set([...childKeys(loaded), ...childKeys(mine), ...childKeys(current)]);
    keys.forEach((key) =>
      collect(child(loaded, key), child(mine, key), child(current, key), path ? `${path}.${key}` : key, into),
    );
    return;
  }
  into.push({ path, loaded, current, mine, edited: !sameValue(loaded, mine) });
}

/** The fields the server changed since `loaded`, with the user's value beside them, in the draft's order. */
export function staleChanges(loaded: Draft, mine: Draft, current: Draft): FieldChange[] {
  const changes: FieldChange[] = [];
  collect(loaded, mine, current, '', changes);
  return changes;
}

function merge(loaded: unknown, mine: unknown, current: unknown): unknown {
  if (sameValue(loaded, mine)) return current;
  if (sameValue(loaded, current)) return mine;
  if (sameShape(loaded, mine, current)) {
    if (Array.isArray(loaded)) {
      return loaded.map((_, index) => merge(loaded[index], (mine as unknown[])[index], (current as unknown[])[index]));
    }
    const keys = new Set([...childKeys(loaded), ...childKeys(mine), ...childKeys(current)]);
    return Object.fromEntries(
      [...keys].map((key) => [key, merge(child(loaded, key), child(mine, key), child(current, key))]),
    );
  }
  // Both sides changed it and it cannot be split further: the user's edit wins.
  return mine;
}

/**
 * The server's version with the user's edits on top: a field the user did not touch takes the server's value, a
 * field they changed keeps their value (also where the server changed it, which `staleChanges` reports).
 */
export function rebaseEdits<T extends Draft>(loaded: T, mine: T, current: T): T {
  return merge(loaded, mine, current) as T;
}

/** `legs.0.quantity` → `Legs · #1 · Quantity`; `validTo` → `Valid to`. */
export function fieldPathLabel(path: string): string {
  return path
    .split('.')
    .map((part) => {
      if (/^\d+$/.test(part)) return `#${Number(part) + 1}`;
      const words = part.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
      return words.charAt(0).toUpperCase() + words.slice(1);
    })
    .join(' · ');
}
