import { BarChart } from '@mantine/charts';
import { Group, Paper, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconChartBar } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { Money } from '@/shared/components/Figures';
import { KpiCard } from '@/shared/components/KpiCard';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { formatDate, formatMoney } from '@/shared/format/format';
import { useLatestOfficialDates } from './hooks';
import { explainBars } from './model/explain';
import { PortfolioExplain } from './PortfolioTab';

export function Explain({ entityId }: { entityId: string }) {
  const { dates, ready } = useLatestOfficialDates(entityId);
  if (!ready) return <CardSkeleton height={320} />;
  return <ExplainFor entityId={entityId} initial={dates} />;
}

export function ExplainFor({ entityId, initial }: { entityId: string; initial: [string, string] | null }) {
  const { t } = useTranslation();
  const [from, setFrom] = useState(initial?.[0] ?? dayjs().subtract(1, 'month').format('YYYY-MM-DD'));
  const [to, setTo] = useState(initial?.[1] ?? dayjs().format('YYYY-MM-DD'));
  const explain = $api.useQuery(
    'get',
    '/api/v1/valuation/pnl-explain',
    { params: { query: { legalEntityId: entityId, from, to } } },
    { retry: false },
  );
  const data = explain.data;
  const bars = data ? explainBars(data.components, (component) => t(`valuation.components.${component}`)) : [];
  return (
    <Stack>
      <Group align="flex-end">
        <TextInput
          type="date"
          label={t('valuation.fromRun')}
          value={from}
          onChange={(e) => setFrom(e.currentTarget.value)}
        />
        <TextInput type="date" label={t('valuation.toRun')} value={to} onChange={(e) => setTo(e.currentTarget.value)} />
      </Group>
      {explain.error ? (
        <EmptyState
          icon={<IconChartBar size={22} />}
          title={t('valuation.noExplain')}
          description={t('valuation.noExplainHint')}
        />
      ) : !data ? (
        <CardSkeleton height={320} />
      ) : (
        <Stack>
          <SimpleGrid cols={{ base: 1, xs: 2 }} spacing="md">
            <KpiCard
              label={t('valuation.mtmFrom', { date: formatDate(data.fromDate) })}
              value=<Money value={data.mtmFrom} />
              unit={data.baseCurrency}
              to={`/valuation/runs/${data.fromRunId}`}
            />
            <KpiCard
              label={t('valuation.mtmTo', { date: formatDate(data.toDate) })}
              value=<Money value={data.mtmTo} />
              unit={data.baseCurrency}
              to={`/valuation/runs/${data.toRunId}`}
            />
          </SimpleGrid>
          <Paper withBorder radius="lg" p="lg">
            <Title order={3} size="h5">
              {t('valuation.explain')}
            </Title>
            <Text size="sm" c="dimmed" mb="md">
              {t('valuation.explainHint', { currency: data.baseCurrency })}
            </Text>
            <BarChart
              h={Math.max(160, bars.length * 48 + 40)}
              data={bars}
              dataKey="component"
              orientation="vertical"
              series={[{ name: 'amount', label: data.baseCurrency, color: 'brand.6' }]}
              getBarColor={(value) => (value < 0 ? 'red.7' : 'teal.7')}
              valueFormatter={(value) => formatMoney(value)}
              xAxisProps={{ domain: [(min: number) => Math.min(0, min), (max: number) => Math.max(0, max)] }}
              yAxisProps={{ width: 110 }}
              referenceLines={[{ x: 0, color: 'gray.5' }]}
              gridAxis="x"
              tickLine="x"
              data-testid="explain-chart"
            />
          </Paper>
        </Stack>
      )}
      <PortfolioExplain entityId={entityId} from={from} to={to} />
    </Stack>
  );
}
