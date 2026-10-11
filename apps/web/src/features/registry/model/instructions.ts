import type { Schema } from '@/shared/api/types';
import type { LifecycleStep as TimelineStep } from '@/shared/components/LifecycleTimeline';

export type Instruction = Schema<'RegistryInstructionView'>;
export type InstructionStatus = Instruction['status'];
export type Route = Schema<'TransferRoute'>;

/** Queue views, each a set of statuses for the search endpoint. */
export const QUEUE_VIEWS = ['open', 'attention', 'done', 'all'] as const;
export type QueueView = (typeof QUEUE_VIEWS)[number];

const VIEW_STATUSES: Record<QueueView, InstructionStatus[] | undefined> = {
  open: ['PENDING_APPROVAL', 'PENDING', 'DISPATCHING', 'SENT', 'ACKNOWLEDGED', 'UNKNOWN'],
  attention: ['UNKNOWN', 'FAILED'],
  done: ['CONFIRMED', 'CANCELLED'],
  all: undefined,
};

export function queueStatuses(view: QueueView): InstructionStatus[] | undefined {
  return VIEW_STATUSES[view];
}

export function asQueueView(value: string | null): QueueView {
  return QUEUE_VIEWS.find((view) => view === value) ?? 'open';
}

export interface InstructionActions {
  /** Executed in the registry: an operator records it with the registry's reference. */
  confirm: boolean;
  fail: boolean;
  cancel: boolean;
  /** Back through approval after a failure or an outcome nobody knows. */
  retry: boolean;
  awaitingApproval: boolean;
}

/**
 * What an operator may do in each state (mirrors `RegistryInstruction`). An instruction being dispatched is left to
 * the dispatcher; a pending one is offered for manual confirmation only on the manual channel.
 */
export function instructionActions(instruction: Pick<Instruction, 'status' | 'channel'>): InstructionActions {
  const { status } = instruction;
  const withRegistry = status === 'SENT' || status === 'ACKNOWLEDGED' || status === 'UNKNOWN';
  const manualPending = status === 'PENDING' && (instruction.channel ?? 'MANUAL') === 'MANUAL';
  return {
    confirm: withRegistry || manualPending,
    fail: withRegistry || manualPending,
    cancel: status === 'PENDING_APPROVAL' || status === 'PENDING' || status === 'FAILED',
    retry: status === 'FAILED' || status === 'UNKNOWN',
    awaitingApproval: status === 'PENDING_APPROVAL',
  };
}

export interface LifecycleStep {
  key: 'submitted' | 'approved' | 'sent' | 'confirmed' | 'failed' | 'cancelled';
  at?: string | null;
  by?: string | null;
  done: boolean;
}

/** The steps an instruction has passed, from its own timestamps; the last one is the current state. */
export function lifecycle(instruction: Instruction): LifecycleStep[] {
  const steps: LifecycleStep[] = [
    { key: 'submitted', at: instruction.createdAt, by: instruction.submittedBy, done: true },
  ];
  if (instruction.approvedBy || instruction.status === 'PENDING_APPROVAL') {
    steps.push({
      key: 'approved',
      at: instruction.approvedAt,
      by: instruction.approvedBy,
      done: Boolean(instruction.approvedBy),
    });
  }
  if (instruction.sentAt) steps.push({ key: 'sent', at: instruction.sentAt, done: true });
  if (instruction.status === 'FAILED') steps.push({ key: 'failed', done: true });
  else if (instruction.status === 'CANCELLED') steps.push({ key: 'cancelled', done: true });
  else
    steps.push({
      key: 'confirmed',
      at: instruction.confirmedAt,
      by: instruction.confirmedBy,
      done: instruction.status === 'CONFIRMED',
    });
  return steps;
}

/**
 * The lifecycle for the timeline: the steps passed, the step the instruction waits in, and the steps still to come
 * (submitted → approved → sent → confirmed); a failed or cancelled instruction ends in a failed step with the reason.
 */
export function instructionSteps(
  instruction: Instruction,
  label: (key: LifecycleStep['key']) => string,
): TimelineStep[] {
  const steps = lifecycle(instruction);
  const ended = instruction.status === 'FAILED' || instruction.status === 'CANCELLED';
  if (!ended && !instruction.sentAt) steps.splice(steps.length - 1, 0, { key: 'sent', done: false });
  const last = steps.reduce((reached, step, index) => (step.done ? index : reached), -1);
  return steps.map((step, index): TimelineStep => {
    const failed = step.key === 'failed' || step.key === 'cancelled';
    const current = !ended && index === last && step.key !== 'confirmed';
    return {
      label: label(step.key),
      state: failed ? 'failed' : !step.done ? 'pending' : current ? 'current' : 'done',
      at: step.done && !failed ? step.at : null,
      by: step.done && !failed ? step.by : null,
      detail: failed ? instruction.lastError : undefined,
    };
  });
}

/** What an instruction moves: certificates of a lot (with their range) or allowances. */
export function subject(instruction: Instruction): string {
  if (instruction.assetKind === 'ALLOWANCE') return `${instruction.quantity} t ${instruction.productCode ?? ''}`.trim();
  const range = instruction.range ? ` (${instruction.range.start}–${instruction.range.end})` : '';
  return `${instruction.quantity} MWh${range}`;
}

/** Whether a route can be used, and what it costs; `null` when the registries have no route on record. */
export function routeSummary(route: Route | undefined): { feasible: boolean; same: boolean } | null {
  if (!route) return null;
  return { feasible: route.feasible, same: route.fromRegistryCode === route.toRegistryCode };
}
