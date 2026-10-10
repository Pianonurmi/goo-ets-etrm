import { Button, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <Stack align="flex-start" gap="sm" data-testid="not-found">
      <Title order={2}>{t('errors.notFoundTitle')}</Title>
      <Text c="dimmed">{t('errors.notFoundText')}</Text>
      <Button component={Link} to="/" variant="light">
        {t('errors.backHome')}
      </Button>
    </Stack>
  );
}
