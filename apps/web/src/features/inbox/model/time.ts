/** Relative times for the inbox (ages and due times), as a count and a unit the locale words. */

export type SpanUnit = 'minute' | 'hour' | 'day';

export interface Span {
  unit: SpanUnit;
  count: number;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** The distance between two instants in the largest whole unit (at least one minute). */
export function span(fromMs: number, toMs: number): Span {
  const distance = Math.abs(toMs - fromMs);
  if (distance >= 2 * DAY) return { unit: 'day', count: Math.floor(distance / DAY) };
  if (distance >= HOUR) return { unit: 'hour', count: Math.floor(distance / HOUR) };
  return { unit: 'minute', count: Math.max(1, Math.floor(distance / MINUTE)) };
}

export interface Due {
  overdue: boolean;
  /** Due within the next four hours (and not yet overdue). */
  soon: boolean;
  span: Span;
}

/** How far the due time is from now, and whether it has passed. */
export function dueState(dueAt: string, now: Date = new Date()): Due {
  const due = new Date(dueAt).getTime();
  const current = now.getTime();
  return { overdue: due < current, soon: due >= current && due - current <= 4 * HOUR, span: span(current, due) };
}

export type PriorityLevel = 'high' | 'normal' | 'low';

/** Task priorities run 0..100 (higher is more urgent); the inbox shows them in three bands. */
export function priorityLevel(priority: number): PriorityLevel {
  if (priority >= 70) return 'high';
  if (priority < 50) return 'low';
  return 'normal';
}
