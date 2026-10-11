import { Stack, Text, ThemeIcon } from '@mantine/core';
import { IconInbox } from '@tabler/icons-react';
import type { ReactNode } from 'react';

/** Nothing to show yet: what would appear here, why it is empty, and the action that fills it. */
export function EmptyState({
  title,
  description,
  icon,
  action,
  testId = 'empty-state',
}: {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
  testId?: string;
}) {
  return (
    <Stack align="center" justify="center" gap="xs" p="xl" ta="center" className="etrm-empty" data-testid={testId}>
      <ThemeIcon size={44} radius="xl" variant="light">
        {icon ?? <IconInbox size={22} />}
      </ThemeIcon>
      <Text fw={600}>{title}</Text>
      {description && (
        <Text size="sm" c="dimmed" maw={360}>
          {description}
        </Text>
      )}
      {action}
    </Stack>
  );
}
