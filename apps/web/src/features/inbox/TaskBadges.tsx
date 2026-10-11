import { Group, Text, Tooltip } from '@mantine/core';
import { IconArrowUp } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { token } from '@/app/tokens';
import { StatusBadge, statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import type { Task } from './model/decisions';
import { dueState, priorityLevel, type Span } from './model/time';

/** A span in words: `3 h`, `2 d`. */
export function useSpanText(): (span: Span) => string {
  const { t } = useTranslation();
  return (span) => t(`inbox.time.${span.unit}`, { count: span.count });
}

/** The task's status in the lifecycle colours. */
export function TaskStatus({ status }: { status: string }) {
  const { t } = useTranslation();
  return <StatusBadge status={status} label={t(`inbox.status.${status}`, { defaultValue: statusLabel(status) })} />;
}

/** When an open task is due, relative to now: red when overdue, amber when due within a few hours. */
export function DueLabel({ task }: { task: Task }) {
  const { t } = useTranslation();
  const words = useSpanText();
  if (task.status !== 'OPEN') {
    return (
      <Text size="sm" c="dimmed" span>
        {formatDateTime(task.dueAt)}
      </Text>
    );
  }
  const due = dueState(task.dueAt);
  const label = due.overdue
    ? t('inbox.overdueBy', { span: words(due.span) })
    : t('inbox.dueIn', { span: words(due.span) });
  return (
    <Group gap={6} wrap="nowrap" component="span">
      {due.overdue || due.soon ? (
        <StatusBadge status={due.overdue ? 'OVERDUE' : 'PENDING'} label={label} data-testid="task-due" />
      ) : (
        <Text size="sm" span data-testid="task-due">
          {label}
        </Text>
      )}
    </Group>
  );
}

/** Priority in three bands; the number stays in the tooltip. */
export function PriorityLabel({ priority }: { priority: number }) {
  const { t } = useTranslation();
  const level = priorityLevel(priority);
  return (
    <Tooltip label={t('inbox.priority', { priority })} withArrow>
      <Group gap={4} wrap="nowrap" component="span" display="inline-flex">
        {level === 'high' && <IconArrowUp size={14} color={token('warning')} aria-hidden="true" />}
        <Text
          span
          size="sm"
          fw={level === 'high' ? 600 : 400}
          c={level === 'high' ? undefined : 'dimmed'}
          style={level === 'high' ? { color: token('warning') } : undefined}
        >
          {t(`inbox.priorityLevel.${level}`)}
        </Text>
      </Group>
    </Tooltip>
  );
}

/** Status, escalation and priority of a task, for its page header. */
export function TaskBadges({ task }: { task: Task }) {
  const { t } = useTranslation();
  return (
    <Group gap={6}>
      <TaskStatus status={task.status} />
      {task.escalationLevel > 0 && (
        <StatusBadge status="WARNING" label={t('inbox.escalated', { level: task.escalationLevel })} />
      )}
      <PriorityLabel priority={task.priority} />
    </Group>
  );
}
