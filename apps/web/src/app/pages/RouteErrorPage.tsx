import { Button, Code, Group, Paper, Stack, Text, ThemeIcon, Title } from '@mantine/core';
import { IconAlertTriangle, IconRefresh } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { isRouteErrorResponse, Link, useRouteError } from 'react-router';
import { NotFoundPage } from './NotFoundPage';

/** Shown when a screen throws while rendering; the rest of the shell stays usable. */
export function RouteErrorPage() {
  const { t } = useTranslation();
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <Paper withBorder p="xl" maw={640} mx="auto" mt="xl" data-testid="route-error">
      <Stack gap="sm">
        <Group gap="sm" wrap="nowrap">
          <ThemeIcon size={40} radius="xl" variant="light" color="red">
            <IconAlertTriangle size={20} />
          </ThemeIcon>
          <Title order={2}>{t('errors.unexpectedTitle')}</Title>
        </Group>
        <Text c="dimmed" size="sm">
          {t('errors.unexpectedText')}
        </Text>
        <Stack gap={4}>
          <Text size="xs" fw={500} c="dimmed">
            {t('errors.technicalDetails')}
          </Text>
          <Code block style={{ maxHeight: 160, overflow: 'auto' }}>
            {message}
          </Code>
        </Stack>
        <Group gap="xs" mt="xs">
          <Button leftSection={<IconRefresh size={16} />} onClick={() => window.location.reload()}>
            {t('errors.reload')}
          </Button>
          <Button component={Link} to="/" variant="default">
            {t('errors.backHome')}
          </Button>
        </Group>
      </Stack>
    </Paper>
  );
}
