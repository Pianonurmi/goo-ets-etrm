import type { Schema } from '@/shared/api/types';
import { Money } from '@/shared/components/Figures';
import { StatusBadge } from '@/shared/components/StatusBadge';

export type Run = Schema<'ValuationRunView'>;

export type Result = Schema<'ValuationResultView'>;

export function RunStatusBadge({ status }: { status: string }) {
  return <StatusBadge status={status} data-testid="run-status" />;
}

/** An MtM figure in a grid cell, green or red by sign. */
export function MtmCell({ value, currency }: { value: number | null | undefined; currency?: string }) {
  return <Money value={value} currency={currency} signed size="sm" />;
}
