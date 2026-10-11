import type { Schema } from '@/shared/api/types';
import type { LifecycleStep } from '@/shared/components/LifecycleTimeline';

type LotEvent = Pick<Schema<'LotEventView'>, 'seq' | 'eventType' | 'actor' | 'recordedAt'>;

/** `LotTransferInitiated` → `Transfer initiated`: the journal's event names, readable. */
export function eventLabel(eventType: string): string {
  const words = eventType
    .replace(/^Lot(?=[A-Z])/, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The lot's journal as a timeline, oldest first: each event with who recorded it and when, the event name kept as
 * the detail (it is what the audit trail and the hash chain refer to). The latest event is the lot's current state.
 */
export function historySteps(events: LotEvent[]): LifecycleStep[] {
  const sorted = [...events].sort((a, b) => a.seq - b.seq);
  return sorted.map((event, index) => ({
    label: eventLabel(event.eventType),
    state: index === sorted.length - 1 ? 'current' : 'done',
    at: event.recordedAt,
    by: event.actor,
    detail: event.eventType,
  }));
}
