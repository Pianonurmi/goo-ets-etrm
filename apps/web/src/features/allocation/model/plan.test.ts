import type { Schema } from '@/shared/api/types';
import { coverageRows, groupLines, planSteps, type Line } from './plan';

const line = (obligationId: string, lineNo: number, quantityMwh: number) =>
  ({ obligationId, lineNo, quantityMwh, lotId: `lot-${lineNo}` }) as Line;

describe('groupLines', () => {
  it('groups by obligation in first-seen order and totals whole MWh', () => {
    const groups = groupLines([line('a', 1, 100), line('b', 2, 50), line('a', 3, 25)]);
    expect(groups.map((g) => [g.obligationId, g.totalMwh, g.lines.map((l) => l.lineNo)])).toEqual([
      ['a', 125, [1, 3]],
      ['b', 50, [2]],
    ]);
    expect(groupLines([])).toEqual([]);
  });
});

describe('planSteps', () => {
  const label = (key: string) => key;
  const plan = {
    status: 'PROPOSED' as const,
    proposedAt: '2026-10-11T08:00:00Z',
    proposedBy: 'trader',
    approvedAt: null,
    approvedBy: null,
    decidedBy: null,
    decisionReason: null,
  };

  it('waits for approval after the proposal', () => {
    expect(planSteps(plan, label).map((step) => [step.label, step.state])).toEqual([
      ['proposed', 'current'],
      ['approved', 'pending'],
      ['executing', 'pending'],
      ['executed', 'pending'],
    ]);
  });

  it('is done once the registry confirmed every instruction', () => {
    const steps = planSteps({ ...plan, status: 'CONFIRMED', approvedBy: 'middle' }, label);
    expect(steps.every((step) => step.state === 'done')).toBe(true);
    expect(steps[1]?.by).toBe('middle');
  });

  it('ends a rejected plan with who rejected it and why', () => {
    const steps = planSteps({ ...plan, status: 'REJECTED', decidedBy: 'middle', decisionReason: 'Too old' }, label);
    expect(steps.map((step) => [step.label, step.state])).toEqual([
      ['proposed', 'done'],
      ['rejected', 'failed'],
    ]);
    expect(steps[1]).toMatchObject({ by: 'middle', detail: 'Too old' });
  });

  it('marks a partly failed execution', () => {
    const steps = planSteps({ ...plan, status: 'PARTIALLY_FAILED', approvedBy: 'middle' }, label);
    expect(steps.map((step) => step.state)).toEqual(['done', 'done', 'current', 'failed']);
  });
});

describe('coverageRows', () => {
  it('labels each obligation by delivery and account, in delivery order, with the reported figures', () => {
    const coverage = {
      obligations: [
        { obligationId: 'b', demandMwh: 50, coveredMwh: 50, shortfallMwh: 0 },
        { obligationId: 'a', demandMwh: 100, coveredMwh: 40, shortfallMwh: 60 },
      ],
    } as unknown as Schema<'CoverageReport'>;
    const obligations = [
      { id: 'a', deliveryDate: '2026-11-15', targetAccountRef: 'SIM-1', kind: 'SALE' as const },
      { id: 'b', deliveryDate: '2026-12-15', targetAccountRef: null, kind: 'DISCLOSURE' as const },
    ];
    expect(coverageRows(coverage, obligations)).toEqual([
      { obligationId: 'a', label: 'SIM-1 · 11-15', covered: 40, shortfall: 60 },
      { obligationId: 'b', label: 'DISCLOSURE · 12-15', covered: 50, shortfall: 0 },
    ]);
    expect(coverageRows(undefined, obligations)).toEqual([]);
  });
});
