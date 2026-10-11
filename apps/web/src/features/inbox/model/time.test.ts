import { dueState, priorityLevel, span } from './time';

const at = (iso: string) => new Date(iso).getTime();

describe('inbox times', () => {
  it('measures a span in its largest whole unit', () => {
    expect(span(at('2026-10-11T10:00:00Z'), at('2026-10-11T10:00:20Z'))).toEqual({ unit: 'minute', count: 1 });
    expect(span(at('2026-10-11T10:00:00Z'), at('2026-10-11T10:45:00Z'))).toEqual({ unit: 'minute', count: 45 });
    expect(span(at('2026-10-11T10:00:00Z'), at('2026-10-11T13:30:00Z'))).toEqual({ unit: 'hour', count: 3 });
    expect(span(at('2026-10-11T10:00:00Z'), at('2026-10-12T22:00:00Z'))).toEqual({ unit: 'hour', count: 36 });
    expect(span(at('2026-10-14T10:00:00Z'), at('2026-10-11T10:00:00Z'))).toEqual({ unit: 'day', count: 3 });
  });

  it('tells overdue and soon-due tasks apart', () => {
    const now = new Date('2026-10-11T12:00:00Z');
    expect(dueState('2026-10-11T10:00:00Z', now)).toEqual({
      overdue: true,
      soon: false,
      span: { unit: 'hour', count: 2 },
    });
    expect(dueState('2026-10-11T14:00:00Z', now)).toMatchObject({ overdue: false, soon: true });
    expect(dueState('2026-10-13T12:00:00Z', now)).toMatchObject({ overdue: false, soon: false, span: { unit: 'day' } });
  });

  it('bands priorities', () => {
    expect(priorityLevel(90)).toBe('high');
    expect(priorityLevel(70)).toBe('high');
    expect(priorityLevel(60)).toBe('normal');
    expect(priorityLevel(50)).toBe('normal');
    expect(priorityLevel(40)).toBe('low');
  });
});
