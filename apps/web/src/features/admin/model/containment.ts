import type { Schema } from '@/shared/api/types';

type BucketNode = Schema<'BucketNode'>;

/** Where a bucket sits in the hierarchy of product buckets. */
export interface Containment {
  /** Whether the bucket is in the hierarchy at all (an inactive or future bucket may not be). */
  known: boolean;
  /** The buckets that directly contain it; empty for a top-level bucket. */
  parents: string[];
  /** Buckets proven to hold exactly the same certificates, which the hierarchy shows as one node. */
  sameAs: string[];
}

/**
 * Reads a bucket's place from the hierarchy. A bucket that is the node itself has the node's parents and aliases; one
 * that is an alias of another node has that node's parents and is "the same as" the node and its other aliases.
 */
export function containment(code: string, nodes: readonly BucketNode[]): Containment {
  const own = nodes.find((node) => node.code === code);
  if (own) return { known: true, parents: own.parents, sameAs: own.aliases };
  const owner = nodes.find((node) => node.aliases.includes(code));
  if (owner) {
    return {
      known: true,
      parents: owner.parents,
      sameAs: [owner.code, ...owner.aliases.filter((alias) => alias !== code)],
    };
  }
  return { known: false, parents: [], sameAs: [] };
}

/** The text of the "Contained in" cell, for display, sorting and filtering; [wording] gives the two phrases. */
export function containmentText(
  value: Containment,
  wording: { topLevel: string; sameAs: (codes: string) => string },
): string {
  if (!value.known) return '';
  const where = value.parents.length > 0 ? value.parents.join(', ') : wording.topLevel;
  return value.sameAs.length > 0 ? `${where} · ${wording.sameAs(value.sameAs.join(', '))}` : where;
}
