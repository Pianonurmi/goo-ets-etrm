import { Center, Loader, Progress, Stack, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UiConfig } from '@/shared/auth/session';

const POLL_MS = 2_000;

/** Shown while the demo data is being created (after a start or a reset); reloads once it is ready. */
export function PreparingDemo({ config }: { config: UiConfig }) {
  const { t } = useTranslation();
  const [seed, setSeed] = useState(config.demoSeed);
  useEffect(() => {
    const timer = window.setInterval(() => {
      fetch('/api/v1/ui-config', { headers: { Accept: 'application/json' } })
        .then((response) => (response.ok ? (response.json() as Promise<UiConfig>) : null))
        .then((next) => {
          if (!next) return;
          if (next.demoSeed?.state !== 'RUNNING') window.location.reload();
          else setSeed(next.demoSeed);
        })
        .catch(() => undefined);
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, []);
  const done = seed?.completedSteps ?? 0;
  const total = Math.max(seed?.stepCount ?? 1, 1);
  return (
    <Center h="100vh" p="md">
      <Stack w="100%" maw={420} align="center" gap="sm" data-testid="preparing-demo">
        <Loader />
        <Title order={1} size="h3">
          {t('demoSeed.title')}
        </Title>
        <Text c="dimmed" ta="center">
          {t('demoSeed.text')}
        </Text>
        <Progress value={(done / total) * 100} w="100%" aria-label={t('demoSeed.progress', { done, total })} />
        <Text size="sm">
          {t('demoSeed.progress', { done, total })}
          {seed?.step ? ` · ${seed.step}` : ''}
        </Text>
      </Stack>
    </Center>
  );
}
