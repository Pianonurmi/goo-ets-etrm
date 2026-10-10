import { Badge, Group } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { isOverdue, type Task } from './model/decisions';

const STATUS_COLORS: Record<string, string> = {
  OPEN: 'blue',
  APPROVED: 'teal',
  ACKNOWLEDGED: 'teal',
  COMPLETED: 'teal',
  REJECTED: 'red',
  CANCELLED: 'gray',
};

export function StatusBadge({ status }: { status: string }) {
  const { t } = useTranslation();
  return (
    <Badge color={STATUS_COLORS[status] ?? 'gray'} variant="light">
      {t(`inbox.status.${status}`, { defaultValue: status })}
    </Badge>
  );
}

export function TaskBadges({ task }: { task: Task }) {
  const { t } = useTranslation();
  return (
    <Group gap={6}>
      <StatusBadge status={task.status} />
      {isOverdue(task) && (
        <Badge color="red" variant="filled">
          {t('inbox.overdue')}
        </Badge>
      )}
      {task.escalationLevel > 0 && (
        <Badge color="orange" variant="light">
          {t('inbox.escalated', { level: task.escalationLevel })}
        </Badge>
      )}
      <Badge color="gray" variant="outline">
        {t('inbox.priority', { priority: task.priority })}
      </Badge>
    </Group>
  );
}
