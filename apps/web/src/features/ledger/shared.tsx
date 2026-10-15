import type { Schema } from '@/shared/api/types';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDecimal } from '@/shared/format/format';

export type Entry = Schema<'JournalEntryView'>;

export const amount = (value: number) => formatDecimal(value, 2, 2);

/** Posted is done, a reversed entry is history, a reversal stands out as a correction. */
export const ENTRY_TONE: Record<Entry['status'], string> = {
  POSTED: 'COMPLETED',
  REVERSED: 'SUPERSEDED',
  REVERSAL: 'WARNING',
};

export function EntryStatusBadge({ status }: { status: Entry['status'] }) {
  return <StatusBadge status={ENTRY_TONE[status]} label={statusLabel(status)} />;
}

export function ActiveBadge({ active }: { active: boolean | undefined }) {
  return <StatusBadge status={active ? 'ACTIVE' : 'INACTIVE'} />;
}
