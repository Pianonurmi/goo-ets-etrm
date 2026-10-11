import { BarChart } from '@mantine/charts';
import { Group, Paper, Stack, Text, Title } from '@mantine/core';
import dayjs from 'dayjs';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { formatDecimal } from '@/shared/format/format';
import { ladderRows } from './model/lotOps';

const MONTHS = 18;

/** MWh of held and reserved certificates by the month they expire in, for the next eighteen months. */
export function ExpiryLadder({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const from = dayjs().format('YYYY-MM-DD');
  const ladder = $api.useQuery('get', '/api/v1/inventory/expiry-ladder', {
    params: { query: { holderEntityId: entityId, from, months: MONTHS } },
  });
  const rows = useMemo(() => ladderRows(ladder.data, from, MONTHS), [ladder.data, from]);
  const empty = rows.every((row) => row.mwh === 0);
  return (
    <Paper withBorder radius="lg" p="md" data-testid="expiry-ladder">
      <Stack gap="xs">
        <Group justify="space-between" align="baseline">
          <Title order={5}>{t('inventory.ladder.title')}</Title>
          <Text size="xs" c="dimmed">
            {t('inventory.ladder.hint', { months: MONTHS })}
          </Text>
        </Group>
        {ladder.isPending ? (
          <CardSkeleton height={180} />
        ) : ladder.error ? (
          <Text size="sm" c="red">
            {t('errors.requestFailed')}
          </Text>
        ) : empty ? (
          <EmptyState title={t('inventory.ladder.empty')} testId="expiry-ladder-empty" />
        ) : (
          <BarChart
            h={180}
            data={rows}
            dataKey="label"
            series={[{ name: 'mwh', label: t('inventory.columns.quantity'), color: 'brand.7' }]}
            tickLine="y"
            valueFormatter={(value) => formatDecimal(value)}
          />
        )}
      </Stack>
    </Paper>
  );
}
