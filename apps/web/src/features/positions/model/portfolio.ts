import type { Schema } from '@/shared/api/types';

export type PortfolioView = Schema<'PortfolioView'>;
export type PortfolioNode = Schema<'PortfolioNode'>;

/** The tree is open down to this many levels at first: roots and their children are expanded, so three levels show. */
export const DEFAULT_EXPANDED_LEVELS = 2;

/** One row of the grid: a node at a place in the tree. A node with several parents has one row under each. */
export interface PortfolioRow {
  /** The path of bucket codes from the root, joined; unique per place in the tree. */
  key: string;
  /** Number of ancestors on this path, which is what indents the row (a node's own `depth` may count another path). */
  level: number;
  node: PortfolioNode;
  hasChildren: boolean;
  expanded: boolean;
  /** For a repeated node: the parent under which it was listed first. Absent on the first occurrence. */
  alsoUnder?: { code: string; name: string };
}

/** Which nodes the user opened or closed, by row key; the rest follow the default. */
export type ExpandState = Readonly<Record<string, boolean>>;

const SEPARATOR = '>';

/** Whether the row at [key] and [level] is open, given what the user did. */
export function isExpanded(state: ExpandState, key: string, level: number): boolean {
  return state[key] ?? level < DEFAULT_EXPANDED_LEVELS;
}

/** The state after the user toggles the row at [key]. */
export function toggleExpanded(state: ExpandState, key: string, level: number): ExpandState {
  return { ...state, [key]: !isExpanded(state, key, level) };
}

/**
 * Flattens the portfolio into the rows to show: depth first, parents before children, a child only when every ancestor
 * on its path is open. Nodes are placed under each of their parents; the repeats keep the node's figures and say where
 * the node was listed first. A parent that is not in [nodes] makes the node a root, and a loop in the data is cut at the
 * node that closes it, so a bad hierarchy cannot hang the screen or hide nodes.
 */
export function flattenPortfolio(nodes: readonly PortfolioNode[], state: ExpandState = {}): PortfolioRow[] {
  const byCode = new Map(nodes.map((node) => [node.bucketCode, node]));
  const children = new Map<string, PortfolioNode[]>();
  const roots: PortfolioNode[] = [];
  for (const node of nodes) {
    const parents = [...new Set(node.parents)].filter((parent) => byCode.has(parent) && parent !== node.bucketCode);
    if (parents.length === 0) roots.push(node);
    for (const parent of parents) children.set(parent, [...(children.get(parent) ?? []), node]);
  }

  const rows: PortfolioRow[] = [];
  // Where each node was first listed, whether or not that row is showing.
  const firstParent = new Map<string, string | null>();

  const visit = (
    node: PortfolioNode,
    parentKey: string | null,
    parent: string | null,
    ancestors: Set<string>,
    show: boolean,
  ) => {
    const code = node.bucketCode;
    const key = parentKey === null ? code : `${parentKey}${SEPARATOR}${code}`;
    const level = ancestors.size;
    const kids = (children.get(code) ?? []).filter((child) => !ancestors.has(child.bucketCode));
    const expanded = kids.length > 0 && isExpanded(state, key, level);
    const first = firstParent.get(code);
    if (first === undefined) firstParent.set(code, parent);
    if (show) {
      const alsoUnder =
        first !== undefined && first !== null && first !== parent
          ? { code: first, name: byCode.get(first)?.name ?? first }
          : undefined;
      rows.push({ key, level, node, hasChildren: kids.length > 0, expanded, ...(alsoUnder && { alsoUnder }) });
    }
    const below = new Set(ancestors).add(code);
    for (const child of kids) visit(child, key, code, below, show && expanded);
  };

  for (const root of roots) visit(root, null, null, new Set(), true);
  // A loop with no way in from a root would otherwise vanish: list its nodes at the top.
  for (const node of nodes) if (!firstParent.has(node.bucketCode)) visit(node, null, null, new Set(), true);
  return rows;
}
