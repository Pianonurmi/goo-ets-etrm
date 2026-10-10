import type { Schema } from '@/shared/api/types';

export type Line = Schema<'AllocationLineView'>;

export interface LineGroup {
  obligationId: string;
  lines: Line[];
  /** Whole MWh, so the sum is exact. */
  totalMwh: number;
}

/** Lines grouped by obligation in the order the plan lists them; each group keeps its line order. */
export function groupLines(lines: Line[]): LineGroup[] {
  const groups = new Map<string, LineGroup>();
  for (const line of lines) {
    const group = groups.get(line.obligationId) ?? { obligationId: line.obligationId, lines: [], totalMwh: 0 };
    group.lines.push(line);
    group.totalMwh += line.quantityMwh;
    groups.set(line.obligationId, group);
  }
  return [...groups.values()];
}
