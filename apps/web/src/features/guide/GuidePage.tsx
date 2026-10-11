import { Badge, Button, Card, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { IconCircleCheck, IconPlayerPlay } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { personas } from '@/shared/auth/personas';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { startTour, useDoneTours } from '@/shared/tour/store';
import { loadTourTexts } from '@/shared/tour/texts';
import { TOURS } from '@/shared/tour/tours';

/** The demo guide: short guided scenarios as cards, with how long they take, who plays them and what is done. */
export function GuidePage() {
  const { t } = useTranslation('tour');
  const texts = useQuery({
    queryKey: ['tour-texts'],
    queryFn: async () => (await loadTourTexts(), true),
    staleTime: Infinity,
  });
  const done = useDoneTours();
  const names = new Map(personas().map((persona) => [persona.id, persona.name]));
  return (
    <Stack>
      <QueryState isPending={texts.isPending} error={texts.error}>
        {texts.data && (
          <>
            <PageHeader title={t('ui.guideTitle')} description={t('ui.guideDescription')} />
            <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
              {TOURS.map((tour) => {
                const finished = done.includes(tour.id);
                return (
                  <Card key={tour.id} withBorder padding="lg" data-testid={`guide-${tour.id}`}>
                    <Stack gap="xs" h="100%">
                      <Group justify="space-between" align="flex-start" wrap="nowrap">
                        <Title order={5}>{t(`${tour.id}.title`)}</Title>
                        {finished && (
                          <Badge color="teal" variant="light" leftSection={<IconCircleCheck size={12} />}>
                            {t('ui.done')}
                          </Badge>
                        )}
                      </Group>
                      <Text size="sm" c="dimmed" style={{ flexGrow: 1 }}>
                        {t(`${tour.id}.summary`)}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {t('ui.minutes', { count: tour.minutes })} ·{' '}
                        {t('ui.personas', { names: tour.personas.map((id) => names.get(id) ?? id).join(', ') })}
                      </Text>
                      <Group>
                        <Button
                          size="xs"
                          variant={finished ? 'default' : 'filled'}
                          leftSection={<IconPlayerPlay size={14} />}
                          onClick={() => startTour(tour.id)}
                          data-testid={`guide-start-${tour.id}`}
                        >
                          {finished ? t('ui.again') : t('ui.start')}
                        </Button>
                      </Group>
                    </Stack>
                  </Card>
                );
              })}
            </SimpleGrid>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
