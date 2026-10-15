import { type Schema } from '@/shared/api/types';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDecimal } from '@/shared/format/format';

export type Period = Schema<'AccountingPeriodView'>;

export type GlExport = Schema<'GlExportView'>;

export const amount = (value: number | null | undefined) => (value == null ? '–' : formatDecimal(value, 2, 2));

export function PeriodStatusBadge({ status }: { status: Period['status'] }) {
  return <StatusBadge status={status} data-testid="period-status" />;
}
