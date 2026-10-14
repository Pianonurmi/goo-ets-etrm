import type { LifecycleStep } from '@/shared/components/LifecycleTimeline';
import type { Schema } from '@/shared/api/types';
import type { Task, TaskType, Viewer } from './decisions';

export type TaskEvent = Schema<'TaskEventView'>;
type Translate = (key: string, options?: Record<string, unknown>) => string;

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : typeof value === 'number' ? String(value) : null;
}

/**
 * The task's history as lifecycle steps: what happened, by whom and when, and the decision still to come while the
 * task is open. Comments are left out (they have their own list). Labels stay unique, so the timeline keys hold.
 */
export function taskSteps(task: Task, history: readonly TaskEvent[], t: Translate): LifecycleStep[] {
  const seen = new Map<string, number>();
  const steps: LifecycleStep[] = history
    .filter((event) => event.eventType !== 'COMMENTED')
    .map((event) => {
      const decision = text(event.data.decision);
      const status = text(event.data.status);
      const reason = text(event.data.reason);
      const base = decision
        ? t(`inbox.event.DECIDED_${decision}`)
        : event.eventType === 'CLOSED' && status
          ? t(`inbox.status.${status}`)
          : t(`inbox.event.${event.eventType}`, { defaultValue: event.eventType });
      const count = (seen.get(base) ?? 0) + 1;
      seen.set(base, count);
      const to = text(event.data.to);
      const level = text(event.data.level);
      const detail =
        reason ??
        (event.eventType === 'ASSIGNED' && to ? t('inbox.event.assignedTo', { user: to }) : null) ??
        (event.eventType === 'ESCALATED' && level ? t('inbox.event.level', { level }) : null);
      return {
        label: count > 1 ? `${base} (${count})` : base,
        state: decision === 'REJECT' || status === 'REJECTED' ? 'failed' : 'done',
        at: event.at,
        by: event.actor,
        detail,
      } satisfies LifecycleStep;
    });
  if (task.status === 'OPEN') {
    steps.push({ label: t('inbox.event.awaiting'), state: 'current', detail: t('inbox.event.awaitingHint') });
  }
  return steps;
}

export interface DecisionCheck {
  key: 'role' | 'fourEyes';
  ok: boolean;
}

/**
 * The rules that decide whether the viewer may decide an open task, each with its outcome: holding one of the
 * deciding roles, and (for four-eyes types) not being the requester. Mirrors `decisionBlock`.
 */
export function decisionChecks(task: Task, type: TaskType | undefined, viewer: Viewer): DecisionCheck[] {
  const role = task.candidateRoles.some((r) => viewer.roles.includes(r));
  const checks: DecisionCheck[] = [{ key: 'role', ok: role }];
  if (type?.fourEyes ?? true) checks.push({ key: 'fourEyes', ok: task.requestedBy !== viewer.userId });
  return checks;
}
