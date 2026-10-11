import { Group, Paper, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';

/** A titled part of a record drawer, with an optional action on the right and a line when it is empty. */
export function Section({
  title,
  action,
  empty,
  children,
}: {
  title: string;
  action?: ReactNode;
  /** Shown instead of the children when set (the section has nothing yet). */
  empty?: string | null;
  children?: ReactNode;
}) {
  return (
    <Paper withBorder p="md">
      <Group justify="space-between" mb="sm" wrap="nowrap">
        <Title order={4} size="h6">
          {title}
        </Title>
        {action}
      </Group>
      {empty ? (
        <Text size="sm" c="dimmed">
          {empty}
        </Text>
      ) : (
        children
      )}
    </Paper>
  );
}
