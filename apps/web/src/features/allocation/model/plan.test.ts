import { groupLines, type Line } from './plan';

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
