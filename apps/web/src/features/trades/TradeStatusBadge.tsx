import { StatusBadge } from '@/shared/components/StatusBadge';

export function TradeStatusBadge({ status }: { status: string }) {
  return <StatusBadge status={status} data-testid="trade-status" />;
}
