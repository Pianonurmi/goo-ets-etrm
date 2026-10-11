import { Paper, Skeleton, Stack } from '@mantine/core';

/** Placeholder rows while a list or grid loads; keeps the layout from jumping. */
export function TableSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <Stack gap={10} p="md" aria-hidden="true" data-testid="skeleton">
      <Skeleton height={14} width="40%" />
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} height={28} />
      ))}
    </Stack>
  );
}

export function CardSkeleton({ height = 120 }: { height?: number }) {
  return (
    <Paper withBorder radius="lg" p="lg" aria-hidden="true">
      <Skeleton height={12} width="35%" mb="md" />
      <Skeleton height={height - 60} />
    </Paper>
  );
}
