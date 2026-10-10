import { Alert, Button, Code, Stack } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { isRouteErrorResponse, useRouteError } from 'react-router';
import { NotFoundPage } from './NotFoundPage';

/** Shown when a screen throws while rendering; the rest of the shell stays usable. */
export function RouteErrorPage() {
  const { t } = useTranslation();
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <Stack p="md" maw={720}>
      <Alert color="red" title={t('errors.unexpectedTitle')}>
        <Code block>{message}</Code>
      </Alert>
      <Button variant="light" onClick={() => window.location.reload()} w="fit-content">
        {t('errors.reload')}
      </Button>
    </Stack>
  );
}
