import type { Schema } from '@/shared/api/types';

export type Instruction = Schema<'RegistryInstructionView'>;
export type InstructionStatus = Instruction['status'];
export type Route = Schema<'TransferRoute'>;

export const STATUS_COLORS: Record<InstructionStatus, string> = {
  PENDING_APPROVAL: 'yellow',
  PENDING: 'blue',
  DISPATCHING: 'blue',
  SENT: 'indigo',
  UNKNOWN: 'orange',
  ACKNOWLEDGED: 'indigo',
  CONFIRMED: 'teal',
  FAILED: 'red',
  CANCELLED: 'gray',
};

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
