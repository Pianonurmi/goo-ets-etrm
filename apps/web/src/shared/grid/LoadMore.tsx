import { Button, Group, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

interface Paged {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => Promise<unknown>;
}

/** The count of rows shown under a keyset-paged grid, and the button that fetches the next page while there is one. */
export function LoadMore({ query, shown, testId }: { query: Paged; shown: number; testId?: string }) {
  const { t } = useTranslation();
  return (
    <Group justify="space-between">
      <Text size="sm" c="dimmed" data-testid={testId}>
        {t('common.shown', { count: shown })}
      </Text>
      {query.hasNextPage && (
        <Button variant="light" loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {t('inbox.loadMore')}
        </Button>
      )}
    </Group>
  );
}
