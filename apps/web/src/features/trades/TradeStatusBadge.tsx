import { Badge } from '@mantine/core';

const COLORS: Record<string, string> = {
  DRAFT: 'gray',
  PENDING_APPROVAL: 'yellow',
  APPROVED: 'blue',
  CONFIRMED: 'teal',
  PARTIALLY_DELIVERED: 'cyan',
  DELIVERED: 'green',
  SETTLED: 'green',
  CANCELLED: 'gray',
  CLOSED: 'gray',
  DISPUTED: 'red',
};

export function TradeStatusBadge({ status }: { status: string }) {
  return (
    <Badge color={COLORS[status] ?? 'gray'} variant="light" data-testid="trade-status">
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}
