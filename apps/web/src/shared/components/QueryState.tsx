import { Alert, Center, Loader } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { asProblemError } from '@/shared/api/problem';

/** Loading spinner, error alert or the content of a query. */
export function QueryState({
  isPending,
  error,
  children,
}: {
  isPending: boolean;
  error: unknown;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  if (error) {
    const problem = asProblemError(error);
    return (
      <Alert color="red" icon={<IconAlertTriangle size={18} />} title={t('errors.requestFailed')}>
        {problem.message}
      </Alert>
    );
  }
  if (isPending) {
    return (
      <Center p="xl">
        <Loader size="sm" aria-label={t('app.loading')} />
      </Center>
    );
  }
  return children;
}
