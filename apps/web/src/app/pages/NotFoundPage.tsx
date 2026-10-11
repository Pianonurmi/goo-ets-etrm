import { Button, Group, Paper, Stack, Text, ThemeIcon, Title } from '@mantine/core';
import { IconCompass } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="xl" maw={560} mx="auto" mt="xl" data-testid="not-found">
      <Stack align="center" gap="sm" ta="center">
        <ThemeIcon size={48} radius="xl" variant="light">
          <IconCompass size={24} />
        </ThemeIcon>
        <Text size="xs" c="dimmed" ff="monospace">
          404
        </Text>
        <Title order={2}>{t('errors.notFoundTitle')}</Title>
        <Text c="dimmed" size="sm" maw={400}>
          {t('errors.notFoundText')}
        </Text>
        <Group gap="xs" mt="xs">
          <Button component={Link} to="/">
            {t('errors.backHome')}
          </Button>
          <Button component={Link} to="/inbox" variant="default">
            {t('errors.openInbox')}
          </Button>
        </Group>
      </Stack>
    </Paper>
  );
}
