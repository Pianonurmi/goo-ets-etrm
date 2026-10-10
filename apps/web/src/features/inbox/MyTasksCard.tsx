import { Anchor, Badge, Card, Group, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { formatDateTime } from '@/shared/format/format';
import { useActionableCounts, useTaskTypes } from './hooks';
import { isOverdue } from './model/decisions';

/** The first tasks the user can decide, most urgent first. */
export function MyTasksCard() {
  const { t } = useTranslation();
  const types = useTaskTypes();
  const { total } = useActionableCounts();
  const { data } = $api.useQuery('get', '/api/v1/workflow/tasks', {
    params: { query: { actionable: true, sort: 'DUE', limit: 5 } },
  });
  const tasks = data?.items ?? [];
  return (
    <Card withBorder data-testid="my-tasks">
      <Group justify="space-between" mb="xs">
        <Text fw={600}>{t('inbox.myTasks')}</Text>
        <Badge variant="light">{total}</Badge>
      </Group>
      <Stack gap="xs">
        {tasks.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('inbox.noTasks')}
          </Text>
        )}
        {tasks.map((task) => (
          <Group key={task.id} justify="space-between" wrap="nowrap">
            <Stack gap={0} style={{ minWidth: 0 }}>
              <Anchor component={Link} to={`/inbox/${task.id}`} size="sm" truncate>
                {task.summary}
              </Anchor>
              <Text size="xs" c="dimmed">
                {types.get(task.type)?.displayName ?? task.type} · {task.requestedBy}
              </Text>
            </Stack>
            <Text size="xs" c={isOverdue(task) ? 'red' : 'dimmed'} style={{ whiteSpace: 'nowrap' }}>
              {formatDateTime(task.dueAt)}
            </Text>
          </Group>
        ))}
        <Anchor component={Link} to="/inbox" size="sm">
          {t('inbox.openInbox')}
        </Anchor>
      </Stack>
    </Card>
  );
}
