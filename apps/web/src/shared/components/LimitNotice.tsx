import { Alert } from '@mantine/core';
import { IconListDetails } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { formatDecimal } from '@/shared/format/format';

/** A list that came back with exactly as many rows as it asked for has probably been cut off (W6). */
export function reachedLimit(shown: number, limit: number): boolean {
  return limit > 0 && shown >= limit;
}

/**
 * Says so when a list shows only the first rows because of the request's limit, so nobody takes a cut-off list,
 * or a total over it, for everything. Lists that page by cursor have a "Load more" instead.
 */
export function LimitNotice({
  shown,
  limit,
  testId = 'limit-notice',
}: {
  shown: number;
  limit: number;
  testId?: string;
}) {
  const { t } = useTranslation();
  if (!reachedLimit(shown, limit)) return null;
  return (
    <Alert
      color="yellow"
      variant="light"
      icon={<IconListDetails size={18} />}
      py="xs"
      role="status"
      data-testid={testId}
    >
      {t('limitNotice.text', { count: formatDecimal(limit) })}
    </Alert>
  );
}
