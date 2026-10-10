/** One changed value between two versions, by dotted path (e.g. `legs.0.price`). */
export interface Change {
  path: string;
  before: unknown;
  after: unknown;
}

const IGNORED = new Set(['version', 'approvalFindings', 'requiresRiskApproval']);

function flatten(value: unknown, prefix: string, out: Map<string, unknown>): Map<string, unknown> {
  if (Array.isArray(value)) {
    value.forEach((item, index) => flatten(item, prefix ? `${prefix}.${index}` : String(index), out));
    if (value.length === 0) out.set(prefix, []);
  } else if (value !== null && typeof value === 'object') {
    Object.entries(value as Record<string, unknown>).forEach(([key, item]) => {
      if (!prefix && IGNORED.has(key)) return;
      flatten(item, prefix ? `${prefix}.${key}` : key, out);
    });
  } else {
    out.set(prefix, value);
  }
  return out;
}

/** What changed from [before] to [after], ignoring bookkeeping fields; sorted by path. */
export function diffVersions(before: unknown, after: unknown): Change[] {
  const a = flatten(before, '', new Map());
  const b = flatten(after, '', new Map());
  const paths = new Set([...a.keys(), ...b.keys()]);
  return [...paths]
    .filter((path) => JSON.stringify(a.get(path)) !== JSON.stringify(b.get(path)))
    .sort()
    .map((path) => ({ path, before: a.get(path), after: b.get(path) }));
}
