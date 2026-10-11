import dayjs from 'dayjs';
import type { Schema } from '@/shared/api/types';

export type Deadline = Schema<'DeadlineView'>;
export type DeadlineState = 'met' | 'overdue' | 'dueSoon' | 'upcoming';

/** Days ahead within which an unmet deadline counts as due soon (the first reminder stage). */
export const DUE_SOON_DAYS = 30;

export function deadlineState(deadline: Pick<Deadline, 'dueOn' | 'met'>, today: string): DeadlineState {
  if (deadline.met) return 'met';
  const days = dayjs(deadline.dueOn).diff(dayjs(today), 'day');
  if (days < 0) return 'overdue';
  return days <= DUE_SOON_DAYS ? 'dueSoon' : 'upcoming';
}

/** The status each deadline state is shown with (its colour in `StatusBadge`). */
export const DEADLINE_TONES: Record<DeadlineState, string> = {
  met: 'COMPLETED',
  overdue: 'OVERDUE',
  dueSoon: 'WARNING',
  upcoming: 'OPEN',
};

/** The next deadline not yet met, overdue ones first: what the compliance team acts on next. */
export function nextDeadline(deadlines: Deadline[], today: string): (Deadline & { state: DeadlineState }) | undefined {
  return deadlineCalendar(deadlines, today)
    .flatMap((month) => month.deadlines)
    .find((deadline) => deadline.state !== 'met');
}

export interface DeadlineMonth {
  /** `YYYY-MM` */
  month: string;
  deadlines: (Deadline & { state: DeadlineState })[];
}

/** Deadlines by calendar month, earliest first; within a month by date, then installation. */
export function deadlineCalendar(deadlines: Deadline[], today: string): DeadlineMonth[] {
  const sorted = [...deadlines].sort(
    (a, b) => a.dueOn.localeCompare(b.dueOn) || a.installationName.localeCompare(b.installationName),
  );
  const months = new Map<string, DeadlineMonth>();
  for (const deadline of sorted) {
    const month = deadline.dueOn.slice(0, 7);
    const entry = months.get(month) ?? { month, deadlines: [] };
    entry.deadlines.push({ ...deadline, state: deadlineState(deadline, today) });
    months.set(month, entry);
  }
  return [...months.values()];
}
