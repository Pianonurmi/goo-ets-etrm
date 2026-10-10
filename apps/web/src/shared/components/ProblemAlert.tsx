import { Alert, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { asProblemError } from '@/shared/api/problem';

/** A failed call shown where it happened (inside a modal or form), with the server's violations. */
export function ProblemAlert({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (!error) return null;
  const problem = asProblemError(error);
  return (
    <Alert
      color="red"
      title={problem.status === 403 ? t('errors.forbidden') : t('errors.requestFailed')}
      data-testid="problem-alert"
    >
      <Text size="sm">{problem.message}</Text>
      {(problem.problem.violations ?? []).map((violation) => (
        <Text key={violation.field} size="sm">
          {violation.field}: {violation.message}
        </Text>
      ))}
    </Alert>
  );
}
