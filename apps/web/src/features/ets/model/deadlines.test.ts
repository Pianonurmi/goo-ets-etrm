import { describe, expect, it } from 'vitest';
import { deadlineCalendar, deadlineState, type Deadline } from './deadlines';

const deadline = (dueOn: string, met: boolean, installationName = 'Plant'): Deadline => ({
  complianceYear: 2026,
  dueOn,
  installationId: 'i-1',
  installationName,
  kind: 'SURRENDER',
  met,
});

describe('deadlines', () => {
  it('classifies a deadline against today', () => {
    expect(deadlineState(deadline('2026-01-01', true), '2026-06-01')).toBe('met');
    expect(deadlineState(deadline('2026-05-31', false), '2026-06-01')).toBe('overdue');
    expect(deadlineState(deadline('2026-06-01', false), '2026-06-01')).toBe('dueSoon');
    expect(deadlineState(deadline('2026-07-01', false), '2026-06-01')).toBe('dueSoon');
    expect(deadlineState(deadline('2026-07-02', false), '2026-06-01')).toBe('upcoming');
  });

  it('groups by month in date order', () => {
    const calendar = deadlineCalendar(
      [
        deadline('2027-09-30', false, 'B'),
        deadline('2027-03-31', false, 'B'),
        deadline('2027-03-31', true, 'A'),
        deadline('2027-02-28', false, 'A'),
      ],
      '2027-03-15',
    );
    expect(calendar.map((m) => m.month)).toEqual(['2027-02', '2027-03', '2027-09']);
    expect(calendar[1]?.deadlines.map((d) => [d.installationName, d.state])).toEqual([
      ['A', 'met'],
      ['B', 'dueSoon'],
    ]);
    expect(calendar[0]?.deadlines[0]?.state).toBe('overdue');
  });
});
