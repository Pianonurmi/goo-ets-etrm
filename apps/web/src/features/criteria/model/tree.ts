import type { Schema } from '@/shared/api/types';

export type Criterion = Schema<'Criterion'>;
export type CriterionType = Criterion['type'];
export type Group = Extract<Criterion, { type: 'all' | 'any' }>;
export type Catalogue = Schema<'CriteriaCatalogue'>;
export type Field = Schema<'CriteriaField'>;
export type Operator = Field['operators'][number];
export type Attribute = Field['attribute'];

/** A position in the tree: child indexes from the root; `not` has its operand at index 0. */
export type Path = readonly number[];

export function isGroup(node: Criterion): node is Group {
  return node.type === 'all' || node.type === 'any';
}

export function children(node: Criterion): Criterion[] {
  if (isGroup(node)) return node.of;
  if (node.type === 'not') return [node.of];
  return [];
}

function withChildren(node: Criterion, next: Criterion[]): Criterion {
  if (isGroup(node)) return { ...node, of: next };
  if (node.type === 'not' && next[0]) return { ...node, of: next[0] };
  return node;
}

export function nodeAt(root: Criterion, path: Path): Criterion | undefined {
  let node: Criterion | undefined = root;
  for (const index of path) node = node ? children(node)[index] : undefined;
  return node;
}

/** Replaces the node at [path]; the root is replaced for an empty path. */
export function replaceAt(root: Criterion, path: Path, replacement: Criterion): Criterion {
  const [head, ...rest] = path;
  if (head === undefined) return replacement;
  const kids = children(root);
  const child = kids[head];
  if (!child) return root;
  const next = [...kids];
  next[head] = replaceAt(child, rest, replacement);
  return withChildren(root, next);
}

/** Adds [child] to the group at [path]. */
export function addChild(root: Criterion, path: Path, child: Criterion): Criterion {
  const group = nodeAt(root, path);
  if (!group || !isGroup(group)) return root;
  return replaceAt(root, path, { ...group, of: [...group.of, child] });
}

/**
 * Removes the node at [path]. A group left with one operand keeps it (an `all` of one is that one); removing the
 * operand of a `not` removes the `not`; removing the root leaves "match all".
 */
export function removeAt(root: Criterion, path: Path): Criterion {
  if (path.length === 0) return { type: 'matchAll' };
  const parentPath = path.slice(0, -1);
  const index = path[path.length - 1] ?? 0;
  const parent = nodeAt(root, parentPath);
  if (!parent) return root;
  if (parent.type === 'not') return removeAt(root, parentPath);
  const remaining = children(parent).filter((_, i) => i !== index);
  if (remaining.length === 0) return removeAt(root, parentPath);
  return replaceAt(root, parentPath, withChildren(parent, remaining));
}

/** Wraps the node at [path] in a group or a negation. */
export function wrapAt(root: Criterion, path: Path, wrapper: 'all' | 'any' | 'not'): Criterion {
  const node = nodeAt(root, path);
  if (!node) return root;
  const wrapped: Criterion = wrapper === 'not' ? { type: 'not', of: node } : { type: wrapper, of: [node] };
  return replaceAt(root, path, wrapped);
}

/** Switches a group between "all of" and "any of". */
export function toggleGroup(root: Criterion, path: Path): Criterion {
  const node = nodeAt(root, path);
  if (!node || !isGroup(node)) return root;
  return replaceAt(root, path, { ...node, type: node.type === 'all' ? 'any' : 'all' });
}

/** A fresh condition for a field and operator, with empty values the user fills in. */
export function newCondition(field: Field, operator: Operator): Criterion {
  const attribute = field.attribute;
  switch (operator) {
    case 'EQUALS':
      return { type: 'equals', attribute, value: field.valueSource === 'BOOLEAN' ? 'false' : '' };
    case 'IN':
      return { type: 'in', attribute, values: [] };
    case 'NUMBER_RANGE':
      return { type: 'numberRange', attribute, min: null, max: null };
    case 'DATE_RANGE':
      return { type: 'dateRange', attribute, from: null, toExclusive: null };
    case 'DESCENDANT_OF':
      return { type: 'descendantOf', attribute, code: '' };
    case 'LABELS_ANY':
      return { type: 'labelsAny', labels: [] };
    case 'LABELS_ALL':
      return { type: 'labelsAll', labels: [] };
    case 'VINTAGE':
      return { type: 'vintage', rule: { type: 'sameCalendarYearAsDelivery' } };
  }
}

/** Conditions that are not about one attribute. */
export function newSpecial(type: 'countryInRegion' | 'deviceAgeAtDelivery' | 'matchAll'): Criterion {
  if (type === 'countryInRegion') return { type, region: '' };
  if (type === 'deviceAgeAtDelivery') return { type, maxYears: 15, minYears: 0 };
  return { type: 'matchAll' };
}

/** Missing values the server would refuse; shown next to the condition before validation is asked for. */
export function incomplete(node: Criterion): string[] {
  const problems: string[] = [];
  const visit = (current: Criterion, path: string) => {
    switch (current.type) {
      case 'equals':
      case 'descendantOf':
        if (!(current.type === 'equals' ? current.value : current.code).trim()) problems.push(path);
        break;
      case 'in':
        if (current.values.length === 0) problems.push(path);
        break;
      case 'labelsAny':
      case 'labelsAll':
        if (current.labels.length === 0) problems.push(path);
        break;
      case 'numberRange':
        if (current.min == null && current.max == null) problems.push(path);
        break;
      case 'dateRange':
        if (!current.from && !current.toExclusive) problems.push(path);
        break;
      case 'countryInRegion':
        if (!current.region) problems.push(path);
        break;
      default:
        children(current).forEach((child, index) => visit(child, path ? `${path}.${index}` : String(index)));
    }
  };
  visit(node, '');
  return problems;
}

/** Hydro in the Nordics: a starting point that shows how groups work. */
export const EXAMPLE: Criterion = {
  type: 'all',
  of: [
    { type: 'countryInRegion', region: 'NORDIC' },
    { type: 'descendantOf', attribute: 'ENERGY_SOURCE', code: 'HYDRO' },
  ],
};
