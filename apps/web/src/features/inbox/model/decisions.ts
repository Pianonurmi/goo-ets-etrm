import type { Role, Schema } from '@/shared/api/types';

export type Task = Schema<'ApprovalTaskView'>;
export type TaskType = Schema<'TaskTypeDefinition'>;
export type Decision = 'APPROVE' | 'REJECT' | 'ACKNOWLEDGE';

export interface Viewer {
  userId: string;
  roles: readonly Role[];
}

export type DecisionBlock = 'closed' | 'not-candidate' | 'own-request';

/** Why the viewer may not decide the task, or null when they may (mirrors the workflow service's checks). */
export function decisionBlock(task: Task, type: TaskType | undefined, viewer: Viewer): DecisionBlock | null {
  if (task.status !== 'OPEN') return 'closed';
  const admin = viewer.roles.includes('ADMIN');
  if (!admin && !task.candidateRoles.some((role) => viewer.roles.includes(role))) return 'not-candidate';
  if ((type?.fourEyes ?? true) && task.requestedBy === viewer.userId) return 'own-request';
  return null;
}

/** The decisions a task type offers, in a stable order. */
export function decisionsOf(type: TaskType | undefined): Decision[] {
  const allowed = new Set(type?.allowedDecisions ?? ['APPROVE', 'REJECT']);
  return (['APPROVE', 'REJECT', 'ACKNOWLEDGE'] as const).filter((decision) => allowed.has(decision));
}

export function reasonRequired(type: TaskType | undefined, decision: Decision): boolean {
  return (type?.reasonRequiredFor ?? ['REJECT']).includes(decision);
}

/** Overdue when the due time has passed (open tasks only). */
export function isOverdue(task: Task, now: Date = new Date()): boolean {
  return task.status === 'OPEN' && new Date(task.dueAt).getTime() < now.getTime();
}

/**
 * Whether the viewer may cancel the open task (mirrors `WorkflowService.cancel`): the requester withdraws their own
 * request; the assignee, an escalation role of the type or an administrator may cancel it too.
 */
/**
 * Task types whose request can be withdrawn (TaskHandler.onWithdraw): cancelling a trade approval cancels the trade.
 * Other approvals are decided, so their subject never waits for a decision that will not come.
 */
export const WITHDRAWABLE_TYPES: ReadonlySet<string> = new Set(['TRADE_APPROVAL']);

export function canCancel(task: Task, type: TaskType | undefined, viewer: Viewer): boolean {
  if (task.status !== 'OPEN' || !WITHDRAWABLE_TYPES.has(task.type)) return false;
  if (task.requestedBy === viewer.userId || task.assignee === viewer.userId) return true;
  if (viewer.roles.includes('ADMIN')) return true;
  return (type?.escalationRoles ?? []).some((role) => viewer.roles.includes(role));
}

/** Whether the viewer may claim the task (an assignee only marks who is working on it; deciding is open to all candidates). */
export function canWorkOn(task: Task, viewer: Viewer): boolean {
  return (
    task.status === 'OPEN' &&
    (viewer.roles.includes('ADMIN') || task.candidateRoles.some((role) => viewer.roles.includes(role)))
  );
}
