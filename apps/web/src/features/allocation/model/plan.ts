import type { Schema } from '@/shared/api/types';
import type { LifecycleStep } from '@/shared/components/LifecycleTimeline';

export type Line = Schema<'AllocationLineView'>;
type Plan = Schema<'AllocationPlanView'>;
type PlanStatus = Plan['status'];
type Coverage = Schema<'CoverageReport'>;
type Obligation = Pick<Schema<'ObligationView'>, 'id' | 'deliveryDate' | 'targetAccountRef' | 'kind'>;

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

export type PlanStepKey = 'proposed' | 'approved' | 'executing' | 'executed' | 'rejected' | 'ended' | 'partlyFailed';

const PATH: PlanStepKey[] = ['proposed', 'approved', 'executing', 'executed'];

const RANK: Partial<Record<PlanStatus, number>> = {
  PROPOSED: 0,
  APPROVED: 1,
  RESERVED: 1,
  INSTRUCTED: 2,
  PARTIALLY_FAILED: 2,
  CONFIRMED: 3,
  CLOSED: 3,
};

/**
 * A plan's way from proposal to execution: proposed, approved (by a second person), executing (registry instructions
 * out) and executed. A rejected, cancelled, expired or stale plan ends in a failed step after the last step reached.
 */
export function planSteps(
  plan: Pick<
    Plan,
    'status' | 'proposedAt' | 'proposedBy' | 'approvedAt' | 'approvedBy' | 'decidedBy' | 'decisionReason'
  >,
  label: (key: PlanStepKey) => string,
): LifecycleStep[] {
  const rank = RANK[plan.status];
  const reached = rank ?? (plan.approvedBy ? 1 : 0);
  const when: Partial<Record<PlanStepKey, Pick<LifecycleStep, 'at' | 'by'>>> = {
    proposed: { at: plan.proposedAt, by: plan.proposedBy },
    approved: { at: plan.approvedAt, by: plan.approvedBy },
  };
  const steps: LifecycleStep[] = [];
  PATH.forEach((key, index) => {
    if (rank === undefined && index > reached) return;
    const last = index === PATH.length - 1;
    if (index < reached || (index === reached && (last || rank === undefined))) {
      steps.push({ label: label(key), state: 'done', ...when[key] });
    } else if (index === reached) {
      steps.push({ label: label(key), state: 'current', ...when[key] });
    } else {
      steps.push({ label: label(key), state: 'pending' });
    }
  });
  if (plan.status === 'PARTIALLY_FAILED') {
    steps.splice(3, 1, { label: label('partlyFailed'), state: 'failed' });
  }
  if (rank === undefined) {
    const key: PlanStepKey = plan.status === 'REJECTED' ? 'rejected' : 'ended';
    steps.push({ label: label(key), state: 'failed', by: plan.decidedBy, detail: plan.decisionReason });
  }
  return steps;
}

export interface CoverageRow {
  obligationId: string;
  label: string;
  covered: number;
  shortfall: number;
}

/**
 * Covered and short MWh of each open obligation, as the coverage report states them (nothing is computed here), in
 * delivery order, labelled by delivery date and target account.
 */
export function coverageRows(
  coverage: Coverage | undefined,
  obligations: Obligation[],
  kindLabel: (kind: Obligation['kind']) => string = (kind) => kind,
): CoverageRow[] {
  if (!coverage) return [];
  const byId = new Map(obligations.map((obligation) => [obligation.id, obligation]));
  return coverage.obligations
    .map((row) => ({ row, obligation: byId.get(row.obligationId) }))
    .sort((a, b) => (a.obligation?.deliveryDate ?? '').localeCompare(b.obligation?.deliveryDate ?? ''))
    .map(({ row, obligation }) => ({
      obligationId: row.obligationId,
      label: obligation
        ? `${obligation.targetAccountRef ?? kindLabel(obligation.kind)} · ${obligation.deliveryDate.slice(5)}`
        : row.obligationId.slice(-8),
      covered: row.coveredMwh,
      shortfall: row.shortfallMwh,
    }));
}
