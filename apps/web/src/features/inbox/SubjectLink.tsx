import { Anchor, Text } from '@mantine/core';
import { Link } from 'react-router';
import { statusLabel } from '@/shared/components/StatusBadge';
import { subjectLink } from './model/subjects';

/** The task's subject: a link when its screen exists, its type and short reference otherwise. */
export function SubjectLink({ subjectType, subjectRef }: { subjectType: string; subjectRef: string }) {
  const href = subjectLink(subjectType, subjectRef);
  const label = (
    <>
      {statusLabel(subjectType)}{' '}
      <Text span ff="monospace" size="xs" c="dimmed">
        {subjectRef.slice(-8)}
      </Text>
    </>
  );
  return href ? (
    <Anchor component={Link} to={href} size="sm" onClick={(event) => event.stopPropagation()}>
      {label}
    </Anchor>
  ) : (
    <Text size="sm" span>
      {label}
    </Text>
  );
}
